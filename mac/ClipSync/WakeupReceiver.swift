// WakeupReceiver.swift
// The ONE and ONLY Mac-side BLE peripheral (GATT server) for ClipSync.
//
// This used to be two competing CBPeripheralManager instances:
//   - BLEAdvertiser (BLEDiscover.swift) -> served DeviceName (read)
//   - WakeupReceiver (this file)        -> served Wakeup (write)
// Both registered the SAME service UUID independently, which caused CoreBluetooth's
// per-process GATT database to have two owners fighting over one service. Android's
// GATT service-discovery could resolve against whichever registration "won" the race,
// silently missing the wakeup characteristic and leaving the Mac stuck on QRGen.swift.
//
// Fix: exactly one CBPeripheralManager, one CBMutableService, both characteristics
// added together, started once at app launch and never torn down mid-flow.

import Foundation
import CoreBluetooth
import Combine

class WakeupReceiver: NSObject, ObservableObject, CBPeripheralManagerDelegate {

    // MARK: - Constants
    static let shared = WakeupReceiver()

    /// Single ClipSync BLE Service UUID — must match BLEScanner.kt on Android.
    private let serviceUUID = CBUUID(string: "C11C5AC0-0001-1000-8000-00805F9B34FB")
    /// Readable — serves the Mac's device name during initial discovery.
    private let deviceNameCharUUID = CBUUID(string: "C11C5AC1-0001-1000-8000-00805F9B34FB")
    /// Writable — receives pairing_ack / wakeup pings from Android.
    private let wakeupCharUUID = CBUUID(string: "C11C5AC2-0001-1000-8000-00805F9B34FB")
    /// Notify — Mac pushes text/file signals to Android (Mac→Android path).
    private let sendRequestCharUUID = CBUUID(string: "C11C5AC3-0001-1000-8000-00805F9B34FB")

    // MARK: - State
    @Published var lastPing: WakeupPayload? = nil
    @Published var isReady = false

    /// Fired once Android successfully reads the DeviceName characteristic —
    /// replaces BLEAdvertiser.onDeviceConnected from the old dual-manager design.
    var onDeviceConnected: (() -> Void)?

    /// Last Android IP received from a wakeup ping — used by Mac for initiating TCP to Android.
    private(set) var lastAndroidIp: String? = nil
    private var lastAndroidIpDate: Date? = nil

    private var peripheralManager: CBPeripheralManager?
    private var deviceNameCharacteristic: CBMutableCharacteristic?
    private var wakeupCharacteristic: CBMutableCharacteristic?
    private var sendRequestCharacteristic: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []
    private var hasAddedService = false

    /// Returns Android's IP if it was seen within the last 2 minutes, otherwise nil.
    func getFreshAndroidIp() -> String? {
        guard let ip = lastAndroidIp, let date = lastAndroidIpDate else { return nil }
        return Date().timeIntervalSince(date) < 120 ? ip : nil
    }

    /// Dynamically builds the JSON payload served when Android reads the DeviceName characteristic.
    /// Format: {"name": "Bunty's MacBook", "ip": "192.168.x.x"}
    private func buildDeviceInfoJSON() -> Data {
        let name = Host.current().localizedName ?? "Mac"
        let ip = getLocalWifiIp() ?? ""
        let port = ClipSyncServer.shared.dynamicPort
        let json = "{\"name\":\"\(name)\",\"ip\":\"\(ip)\",\"port\":\(port)}"
        return json.data(using: .utf8) ?? Data()
    }

    /// Returns the Mac's current Wi-Fi IPv4 address, or nil if not on Wi-Fi.
    private func getLocalWifiIp() -> String? {
        var address: String? = nil
        var ifaddr: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&ifaddr) == 0 else { return nil }
        defer { freeifaddrs(ifaddr) }
        var ptr = ifaddr
        while let current = ptr {
            let iface = current.pointee
            if iface.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: iface.ifa_name)
                // en0 is the primary Wi-Fi interface on Mac
                if name == "en0" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(iface.ifa_addr, socklen_t(iface.ifa_addr.pointee.sa_len),
                                &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
                    address = String(cString: hostname)
                }
            }
            ptr = iface.ifa_next
        }
        return address
    }

    // MARK: - Start / Stop

    /// Idempotent — safe to call multiple times (from PairingManager, QRGenScreen,
    /// BLEDiscover, etc). Only creates the peripheral manager once; subsequent calls
    /// are no-ops if already running, and re-advertise if the manager exists but
    /// advertising was stopped.
    func start() {
        if peripheralManager == nil {
            // Triggers the one-time system Bluetooth permission dialog on first run.
            peripheralManager = CBPeripheralManager(
                delegate: self,
                queue: DispatchQueue(label: "com.clipsync.ble"),
                options: [CBPeripheralManagerOptionShowPowerAlertKey: true]
            )
            return
        }

        if peripheralManager?.state == .poweredOn {
            addServiceIfNeeded(peripheralManager!)
            startAdvertisingIfNeeded()
        }
    }

    /// Stops advertising but keeps the peripheral manager and service registered.
    /// We deliberately do NOT call removeAllServices() here — tearing the service
    /// down and re-adding it is exactly the race condition that caused this bug.
    /// Only call stop() on full app teardown / explicit unpair, never mid-onboarding.
    func stop() {
        peripheralManager?.stopAdvertising()
        DispatchQueue.main.async { self.isReady = false }
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            addServiceIfNeeded(peripheral)
        case .unauthorized, .poweredOff:
            break
        default:
            break
        }
    }

    private func addServiceIfNeeded(_ peripheral: CBPeripheralManager) {
        guard !hasAddedService else {
            startAdvertisingIfNeeded()
            return
        }


        let deviceNameChar = CBMutableCharacteristic(
            type: deviceNameCharUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        deviceNameCharacteristic = deviceNameChar

        let wakeupChar = CBMutableCharacteristic(
            type: wakeupCharUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        wakeupCharacteristic = wakeupChar

        // Mac→Android push characteristic: Android subscribes once and receives
        // notify events whenever Mac calls pushToAndroid().
        let sendRequestChar = CBMutableCharacteristic(
            type: sendRequestCharUUID,
            properties: [.notify, .indicate],
            value: nil,
            permissions: []
        )
        sendRequestCharacteristic = sendRequestChar

        // All three characteristics on ONE service, added ONCE.
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [deviceNameChar, wakeupChar, sendRequestChar]
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if error != nil {
            return
        }

        hasAddedService = true
        startAdvertisingIfNeeded()
    }

    private func startAdvertisingIfNeeded() {
        guard let peripheral = peripheralManager, hasAddedService else { return }

        let macName = Host.current().localizedName ?? "ClipSync"
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: macName
        ])
        DispatchQueue.main.async { self.isReady = true }
    }

    /// Android connected and issued a GATT read on the DeviceName characteristic.
    /// Now serves a JSON payload containing the Mac's name AND current Wi-Fi IP,
    /// so Android can extract the IP and use it for TCP without mDNS discovery.
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == deviceNameCharUUID else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }

        let value = buildDeviceInfoJSON()
        guard request.offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }

        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
    }

    /// Called when Android writes to the Wakeup characteristic (pairing_ack, handshake,
    /// or a real transfer wakeup ping).
    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for req in requests {
            if req.characteristic.uuid != wakeupCharUUID {
                continue
            }
            
            guard let data = req.value else {
                if req.characteristic.properties.contains(.write) {
                    peripheral.respond(to: req, withResult: .success)
                }
                continue
            }
            
            let _ = String(data: data, encoding: .utf8) ?? "invalid utf8"
            
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                if req.characteristic.properties.contains(.write) {
                    peripheral.respond(to: req, withResult: .success)
                }
                continue
            }
            
            if let type = json["type"] as? String, type == "pair" {
                DispatchQueue.main.async { self.onDeviceConnected?() }
                if req.characteristic.properties.contains(.write) {
                    peripheral.respond(to: req, withResult: .success)
                }
                continue
            }

            let androidIp = json["ip"] as? String ?? ""
            let ping = WakeupPayload(
                ip: androidIp,
                port: json["p"] as? Int ?? 8765,
                payloadSize: json["s"] as? Int64 ?? 0,
                payloadType: json["t"] as? String ?? "text",
                directPayload: json["d"] as? String,
                battery: json["b"] as? Int,
                network: json["n"] as? String,
                deviceName: json["dev"] as? String,
                isDiagnostic: json["diagnostic"] as? Bool ?? false
            )

            if ping.isDiagnostic {
                self.pushToAndroid(json: ["diagnostic_ack": true])
                DispatchQueue.main.async {
                    NotificationCenter.default.post(name: NSNotification.Name("DiagnosticPingReceived"), object: nil)
                }
                if req.characteristic.properties.contains(.write) {
                    peripheral.respond(to: req, withResult: .success)
                }
                continue
            }

            if let devName = ping.deviceName,
               shouldUpdatePairedDeviceName(current: PairingManager.shared.pairedDeviceName, incoming: devName) {
                DispatchQueue.main.async {
                    PairingManager.shared.pairedDeviceName = devName
                    UserDefaults.standard.set(devName, forKey: "paired_device_name")
                }
            }

            // Cache Android's IP for Mac-initiated transfers (2-min freshness window)
            if !androidIp.isEmpty {
                self.lastAndroidIp = androidIp
                self.lastAndroidIpDate = Date()
            }


            DispatchQueue.main.async {
                self.lastPing = ping

                if let directData = ping.directPayload {
                    ClipSyncServer.shared.handleDirectBLEPayload(
                        base64Encrypted: directData,
                        type: ping.payloadType
                    )
                } else {
                    if !ClipSyncServer.shared.isListening {
                        ClipSyncServer.shared.start()
                    }
                }
            }

            if req.characteristic.properties.contains(.write) {
                peripheral.respond(to: req, withResult: .success)
            }
        }
    }

    private func shouldUpdatePairedDeviceName(current: String, incoming: String) -> Bool {
        let trimmedIncoming = incoming.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedIncoming.isEmpty else { return false }
        if current.isEmpty || current == "Android Device" { return true }
        if current == trimmedIncoming { return false }

        let modelLike = current.range(
            of: #"^[A-Za-z]?\d{3,}[A-Za-z0-9-]*$"#,
            options: .regularExpression
        ) != nil
        let incomingLooksFriendly = trimmedIncoming.contains(" ") || trimmedIncoming.contains("'")
        return modelLike && incomingLooksFriendly
    }

    // MARK: - Subscription tracking

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == sendRequestCharUUID else { return }
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == sendRequestCharUUID else { return }
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
    }

    // MARK: - Mac → Android push

    /// Returns true if at least one Android central is subscribed to the SendRequest characteristic.
    var hasAndroidSubscriber: Bool { !subscribedCentrals.isEmpty }

    /// Pushes [payload] to all subscribed Android centrals via BLE Notify.
    /// Must be called from any thread — dispatches to the BLE queue internally.
    /// Returns true if the update was queued successfully.
    @discardableResult
    func pushToAndroid(_ payload: Data) -> Bool {
        guard let peripheral = peripheralManager,
              let char = sendRequestCharacteristic,
              !subscribedCentrals.isEmpty else {
            return false
        }
        let success = peripheral.updateValue(payload, for: char, onSubscribedCentrals: nil)
        return success
    }

    /// Pushes a JSON-serialisable dictionary to Android via BLE Notify.
    @discardableResult
    func pushToAndroid(json: [String: Any]) -> Bool {
        guard let data = try? JSONSerialization.data(withJSONObject: json) else { return false }
        return pushToAndroid(data)
    }
}

// MARK: - Payload model

struct WakeupPayload {
    let ip: String
    let port: Int
    let payloadSize: Int64
    let payloadType: String
    let directPayload: String?
    let battery: Int?
    let network: String?
    let deviceName: String?
    let isDiagnostic: Bool
}
