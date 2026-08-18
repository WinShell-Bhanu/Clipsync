// ClipSyncServer.swift
// Always-on TCP server that listens on port 8765 for encrypted clipboard payloads
// from the paired Android device.  Also advertises itself via Bonjour/mDNS so
// Android's NSD layer can discover the Mac's IP without manual configuration.
//
// Wire protocol (matches ClipSyncSender.kt exactly):
//   Header (24 bytes):
//     [4]  magic  0x43_4C_53_59 ("CLSY")
//     [1]  version 0x01
//     [1]  type    0x01=text 0x02=image 0x03=file
//     [2]  reserved
//     [8]  total payload size (Int64, big-endian)
//     [8]  chunk size         (Int64, big-endian)
//   Payload:
//     Repeated: [4-byte Int32 chunk-length][encrypted chunk bytes]
//
// Each chunk is decrypted independently with AES-256-GCM.

import Foundation
import Network
import CryptoKit
import AppKit
import Combine
import UserNotifications
import FirebaseCrashlytics

class ClipSyncServer: ObservableObject {

    // MARK: - Constants
    static let shared   = ClipSyncServer()
    @Published private(set) var dynamicPort: Int = 8765

    private let magic: UInt32 = 0x434C5359   // "CLSY"
    private let queue = DispatchQueue(label: "com.clipsync.tcpserver", qos: .userInitiated)
    private let diskWriteQueue = DispatchQueue(label: "com.clipsync.diskwrite", qos: .userInitiated)
    private let diskWriteSemaphore = DispatchSemaphore(value: 8)

    // MARK: - State (observed by UI)
    @Published var isListening       = false
    @Published var hasActiveClient   = false
    @Published var lastError: String?      = nil
    @Published var bytesReceived: Int64    = 0
    @Published var transferProgress: Double = 0.0

    // Send-side state
    @Published var isSendingFile: Bool     = false
    @Published var sendFileProgress: Double = 0.0
    @Published var transferSpeedString: String = ""
    @Published var transferTotalBytes: Int64 = 0
    @Published var currentTransferFileName: String? = nil

    private var lastBytesSnapshot: Int64 = 0
    private var speedTimer: Timer?
    private var queuedAndroidFiles: [URL] = []

    private var activeReceiveConnection: NWConnection?
    private var activeSendConnection: NWConnection?
    private var activeReceiveFileHandle: FileHandle?
    private var activeReceiveFileURL: URL?
    private var activeSendFileURL: URL?

    private var listener: NWListener?
    // MARK: - Start / Stop

    func start() {
        guard listener == nil else {
            return
        }


        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        let params = NWParameters(tls: nil, tcp: tcpOptions)
        params.allowLocalEndpointReuse = true
        // Explicitly force IPv4 so Android (192.168.x.x) can reach us.
        // NWParameters.tcp defaults to dual-stack but macOS often binds
        // to IPv6 only in practice. Setting ip.version = .v4 on the
        // protocol stack is the reliable way to ensure IPv4 binding.
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }

        do {
            let tcpListener = try NWListener(using: params) // Binds to any available ephemeral port
            advertiseBonjour(on: tcpListener)
            listener = tcpListener
        } catch {
            Crashlytics.crashlytics().record(error: error)
            lastError = "Failed to create listener: \(error.localizedDescription)"
            return
        }

        listener?.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                switch state {
                case .ready:
                    self?.isListening = true
                    self?.lastError   = nil
                    if let p = self?.listener?.port?.rawValue {
                        self?.dynamicPort = Int(p)
                    }
                    if self?.listener?.service != nil {
                    }
                case .failed(let err):
                    self?.isListening = false
                    self?.lastError   = err.localizedDescription
                default:
                    break
                }
            }
        }

        listener?.newConnectionHandler = { [weak self] connection in
            DispatchQueue.main.async { self?.hasActiveClient = true }
            self?.handleConnection(connection)
        }

        listener?.start(queue: queue)
    }

    func stop() {
        listener?.cancel()
        listener = nil
        DispatchQueue.main.async { 
            self.isListening = false 
            self.hasActiveClient = false
        }
    }

    /// Handles an incoming small payload transferred directly via BLE.
    func handleDirectBLEPayload(base64Encrypted: String, type: String) {
        guard ClipboardManager.shared.syncToMac else {
            return
        }
        guard let data = Data(base64Encoded: base64Encrypted),
              let decrypted = decryptChunk(data) else {
            return
        }
        
        let typeCode: UInt8
        switch type {
        case "text": typeCode = 0x01
        case "image": typeCode = 0x02
        case "file": typeCode = 0x03
        default: typeCode = 0x01
        }
        
        deliver(data: decrypted, typeCode: typeCode, fileName: nil, fileUrl: nil)
    }

    // MARK: - mDNS / Bonjour advertisement

    private let bonjourType = "_clipsync._tcp"

    /// Advertises "_clipsync._tcp." with a TXT record containing the pairingId.
    /// This allows Android to find exactly this Mac (not any other ClipSync Mac on
    /// the same network) by matching the pairingId from the QR code.
    private func advertiseBonjour(on listener: NWListener) {
        let macName = Host.current().localizedName ?? "ClipSync Mac"
        let pairingId = PairingManager.shared.pairingId ?? ""

        var txt = NWTXTRecord()
        txt["v"]         = "1"
        txt["pairingId"] = pairingId
        txt["device"]    = macName

        listener.service = NWListener.Service(
            name:      macName,
            type:      bonjourType,
            domain:    nil,
            txtRecord: txt
        )
    }

    // MARK: - Connection handling

    private func handleConnection(_ connection: NWConnection) {
        guard ClipboardManager.shared.syncToMac else {
            connection.cancel()
            return
        }
        
        connection.start(queue: queue)
        activeReceiveConnection = connection

        // Read the 24-byte header first
        readExact(connection: connection, length: 24) { [weak self] headerData in
            guard let self, let headerData else {
                connection.cancel()
                return
            }
            self.processHeader(headerData, connection: connection)
        }
    }

    private func processHeader(_ data: Data, connection: NWConnection) {
        guard data.count == 24 else { connection.cancel(); return }

        var offset = 0

        let actualMagic = data.readUInt32BE(at: offset); offset += 4
        guard actualMagic == magic else {
            connection.cancel()
            return
        }

        let _  = data[offset]; offset += 1
        let typeCode = data[offset]; offset += 1
        offset += 2  // reserved

        let totalSize = data.readInt64BE(at: offset); offset += 8
        let _ = data.readInt64BE(at: offset)   // offset += 8


        if typeCode == 0x99 {
            connection.cancel()
            DispatchQueue.main.async {
                NotificationCenter.default.post(name: NSNotification.Name("DiagnosticTCPPingReceived"), object: nil)
            }
            return
        }

        DispatchQueue.main.async {
            self.bytesReceived = 0
            self.transferProgress = 0
            self.transferTotalBytes = totalSize
            self.transferSpeedString = ""
            self.currentTransferFileName = nil
            self.lastBytesSnapshot = 0
            self.startSpeedTimer()
        }

        // Read filename length (4 bytes) and then filename for ALL types (Android always sends it)
        readExact(connection: connection, length: 4) { [weak self] lenData in
            guard let self, let lenData else { connection.cancel(); return }
            let nameLen = Int(lenData.readUInt32BE(at: 0))
            if nameLen > 0 && nameLen < 2048 {
                self.readExact(connection: connection, length: nameLen) { nameData in
                    let fileName = nameData.flatMap { String(data: $0, encoding: .utf8) } ?? "ClipSync_\(Int(Date().timeIntervalSince1970))"
                    DispatchQueue.main.async { self.currentTransferFileName = fileName }
                    
                    var handle: FileHandle? = nil
                    var destUrl: URL? = nil
                    if typeCode == 0x03 || typeCode == 0x04 {
                        let prefPath = UserDefaults.standard.string(forKey: "PreferredFileStorageLocation") ?? ""
                        let downloads = prefPath.isEmpty ? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first! : URL(fileURLWithPath: prefPath)
                        let dest = downloads.appendingPathComponent(fileName)
                        if !FileManager.default.fileExists(atPath: dest.path) {
                            FileManager.default.createFile(atPath: dest.path, contents: nil, attributes: nil)
                        }
                        handle = try? FileHandle(forWritingTo: dest)
                        destUrl = dest
                        self.activeReceiveFileHandle = handle
                        self.activeReceiveFileURL = destUrl
                    }
                    
                    if typeCode == 0x04 {
                        self.readZeroCopyStream(connection: connection, totalSize: totalSize, typeCode: typeCode, fileName: fileName, fileHandle: handle, fileUrl: destUrl, accumulatedReceived: 0)
                    } else {
                        self.readChunks(connection: connection, totalSize: totalSize, typeCode: typeCode, buffer: Data(), fileName: fileName, fileHandle: handle, fileUrl: destUrl, accumulatedReceived: 0)
                    }
                }
            } else {
                var handle: FileHandle? = nil
                var destUrl: URL? = nil
                let fallbackName = "ClipSync_\(Int(Date().timeIntervalSince1970))"
                DispatchQueue.main.async { self.currentTransferFileName = fallbackName }
                if typeCode == 0x03 || typeCode == 0x04 {
                    let prefPath = UserDefaults.standard.string(forKey: "PreferredFileStorageLocation") ?? ""
                    let downloads = prefPath.isEmpty ? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first! : URL(fileURLWithPath: prefPath)
                    let dest = downloads.appendingPathComponent(fallbackName)
                    if !FileManager.default.fileExists(atPath: dest.path) {
                        FileManager.default.createFile(atPath: dest.path, contents: nil, attributes: nil)
                    }
                    handle = try? FileHandle(forWritingTo: dest)
                    destUrl = dest
                    self.activeReceiveFileHandle = handle
                    self.activeReceiveFileURL = destUrl
                }
                if typeCode == 0x04 {
                    self.readZeroCopyStream(connection: connection, totalSize: totalSize, typeCode: typeCode, fileName: fallbackName, fileHandle: handle, fileUrl: destUrl, accumulatedReceived: 0)
                } else {
                    self.readChunks(connection: connection, totalSize: totalSize, typeCode: typeCode, buffer: Data(), fileName: fallbackName, fileHandle: handle, fileUrl: destUrl, accumulatedReceived: 0)
                }
            }
        }
    }

    private func readChunks(
        connection:          NWConnection,
        totalSize:           Int64,
        typeCode:            UInt8,
        buffer:              Data,
        fileName:            String?,
        fileHandle:          FileHandle?,
        fileUrl:             URL?,
        accumulatedReceived: Int64
    ) {
        // Read 4-byte chunk length prefix
        readExact(connection: connection, length: 4) { [weak self] lenData in
            guard let self = self, let lenData = lenData else {
                connection.cancel()
                fileHandle?.closeFile()
                if let fileUrl = fileUrl { try? FileManager.default.removeItem(at: fileUrl) }
                self?.activeReceiveFileHandle = nil
                self?.activeReceiveFileURL = nil
                return
            }

            let chunkLen = Int(lenData.readUInt32BE(at: 0))
            guard chunkLen > 0, chunkLen <= 5 * 1024 * 1024 + 512 /* max chunk + tag */ else {
                connection.cancel()
                fileHandle?.closeFile()
                if let fileUrl { try? FileManager.default.removeItem(at: fileUrl) }
                self.activeReceiveFileHandle = nil
                self.activeReceiveFileURL = nil
                return
            }

            self.readExact(connection: connection, length: chunkLen) { [weak self] chunkData in
                guard let self = self else { return }
                guard let chunkData else {
                    connection.cancel()
                    fileHandle?.closeFile()
                    if let fileUrl { try? FileManager.default.removeItem(at: fileUrl) }
                    self.activeReceiveFileHandle = nil
                    self.activeReceiveFileURL = nil
                    return
                }

                var accumulated = buffer
                var bytesToCount = 0
                if let decrypted = self.decryptChunk(chunkData) {
                    if let handle = fileHandle {
                        guard self.activeReceiveFileHandle != nil else { return } // Cancelled mid-read
                        
                        // Decouple disk write from network read
                        self.diskWriteSemaphore.wait()
                        self.diskWriteQueue.async {
                            defer { self.diskWriteSemaphore.signal() }
                            if #available(macOS 10.15.4, *) {
                                handle.write(decrypted)
                            } else {
                                handle.write(decrypted)
                            }
                        }
                        bytesToCount = decrypted.count
                    } else {
                        accumulated.append(decrypted)
                    }
                } else {
                    fileHandle?.closeFile()
                    if let fileUrl { try? FileManager.default.removeItem(at: fileUrl) }
                    self.activeReceiveFileHandle = nil
                    self.activeReceiveFileURL = nil
                    connection.cancel()
                    return
                }

                let received = fileHandle != nil ? accumulatedReceived + Int64(bytesToCount) : Int64(accumulated.count)
                DispatchQueue.main.async {
                    self.bytesReceived = received
                    self.transferProgress = totalSize > 0
                        ? Double(received) / Double(totalSize)
                        : 1.0
                }

                if received >= totalSize {
                    // All chunks received
                    self.diskWriteQueue.async {
                        if typeCode != 0x03 && typeCode != 0x04 {
                            self.deliver(data: accumulated, typeCode: typeCode, fileName: fileName, fileUrl: fileUrl)
                        } else {
                            self.deliver(data: Data(), typeCode: typeCode, fileName: fileName, fileUrl: fileUrl)
                        }
                        fileHandle?.closeFile()
                        DispatchQueue.main.async {
                            self.activeReceiveFileHandle = nil
                            self.activeReceiveFileURL = nil
                        }
                    }
                } else {
                    // More chunks to read
                    self.readChunks(
                        connection:          connection,
                        totalSize:           totalSize,
                        typeCode:            typeCode,
                        buffer:              accumulated,
                        fileName:            fileName,
                        fileHandle:          fileHandle,
                        fileUrl:             fileUrl,
                        accumulatedReceived: received
                    )
                }
            }
        }
    }

    private func readZeroCopyStream(
        connection:          NWConnection,
        totalSize:           Int64,
        typeCode:            UInt8,
        fileName:            String?,
        fileHandle:          FileHandle?,
        fileUrl:             URL?,
        accumulatedReceived: Int64
    ) {
        if accumulatedReceived >= totalSize {
            fileHandle?.closeFile()
            self.activeReceiveFileHandle = nil
            self.activeReceiveFileURL = nil
            connection.cancel()
            if let url = fileUrl {
                self.deliver(data: Data(), typeCode: typeCode, fileName: fileName, fileUrl: url)
            } else {
                self.deliver(data: Data(), typeCode: typeCode, fileName: fileName, fileUrl: nil)
            }
            return
        }

        connection.receive(minimumIncompleteLength: 1, maximumLength: 4 * 1024 * 1024) { [weak self] content, context, isComplete, error in
            guard let self = self else { return }
            
            var newlyReceived = 0
            if let data = content, !data.isEmpty {
                newlyReceived = data.count
                if let handle = fileHandle {
                    guard self.activeReceiveFileHandle != nil else { return } // Cancelled mid-read
                    self.diskWriteSemaphore.wait()
                    self.diskWriteQueue.async {
                        defer { self.diskWriteSemaphore.signal() }
                        if #available(macOS 10.15.4, *) {
                            try? handle.write(contentsOf: data)
                        } else {
                            handle.write(data)
                        }
                    }
                }
                
                DispatchQueue.main.async {
                    self.bytesReceived += Int64(newlyReceived)
                    if self.transferTotalBytes > 0 {
                        self.transferProgress = Double(self.bytesReceived) / Double(self.transferTotalBytes)
                    }
                }
            }
            
            if error != nil {
                fileHandle?.closeFile()
                if let fileUrl { try? FileManager.default.removeItem(at: fileUrl) }
                self.activeReceiveFileHandle = nil
                self.activeReceiveFileURL = nil
                connection.cancel()
                return
            }
            
            self.readZeroCopyStream(
                connection: connection,
                totalSize: totalSize,
                typeCode: typeCode,
                fileName: fileName,
                fileHandle: fileHandle,
                fileUrl: fileUrl,
                accumulatedReceived: accumulatedReceived + Int64(newlyReceived)
            )
        }
    }

    // MARK: - Decryption

    private func decryptChunk(_ data: Data) -> Data? {
        guard let hexKey = KeychainHelper.getEncryptionKey(),
              hexKey.count == 64,
              let keyData = hexKey.hexToData() else {
            return nil
        }

        guard data.count > 12 else { return nil }

        let ivData         = data.prefix(12)
        let ciphertextData = data.dropFirst(12)

        do {
            let key       = SymmetricKey(data: keyData)
            let sealed    = try AES.GCM.SealedBox(combined: ivData + ciphertextData)
            let plaintext = try AES.GCM.open(sealed, using: key)
            return plaintext
        } catch _ {
            return nil
        }
    }

    // MARK: - Clipboard delivery

    private func deliver(data: Data, typeCode: UInt8, fileName: String?, fileUrl: URL?) {
        DispatchQueue.main.async {
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()

            var historyContent: String? = nil

            switch typeCode {
            case 0x01: // text
                if let text = String(data: data, encoding: .utf8) {
                    pasteboard.setString(text, forType: .string)
                    historyContent = text

                    // If the received text looks like a standalone OTP (4–8 digits),
                    // fire the OTP bubble — covers local-synced OTPs (BLE/TCP route)
                    // that never go through Firestore and thus bypass OTPNotificationManager.
                    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if trimmed.range(of: #"^\d{4,8}$"#, options: .regularExpression) != nil {
                        OTPNotificationManager.shared.triggerBubble(otpCode: trimmed)
                    }
                }
            case 0x02: // image
                if let image = NSImage(data: data) {
                    pasteboard.writeObjects([image])
                    historyContent = "Image received"
                }
            case 0x03, 0x04: // file — saved to Downloads (0x04 = UltraFast unencrypted)
                if let url = fileUrl {
                    historyContent = url.lastPathComponent

                    let content = UNMutableNotificationContent()
                    content.title = "File Received"
                    content.body = "\(url.lastPathComponent) saved to Downloads"
                    content.userInfo = ["type": "file", "path": url.path]
                    content.sound = .default
                    let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
                    UNUserNotificationCenter.current().add(request)
                }
            default:
                if let text = String(data: data, encoding: .utf8) {
                    pasteboard.setString(text, forType: .string)
                    historyContent = text
                }
            }

            if let content = historyContent {
                let deviceName = PairingManager.shared.pairedDeviceName
                let isImage = (typeCode == 0x02)
                let isFile = (typeCode == 0x03 || typeCode == 0x04)
                let newItem = ClipboardItem(
                    content: content,
                    timestamp: Date(),
                    deviceName: deviceName,
                    direction: .received,
                    isImage: isImage,
                    isFile: isFile,
                    filePath: isFile ? fileUrl?.path : nil
                )
                ClipboardManager.shared.history.insert(newItem, at: 0)
                ClipboardManager.shared.ignoreNextChange = true
                ClipboardManager.shared.lastCopiedText = (typeCode == 0x01 || typeCode == 0x03) ? content : ""
            }

            self.transferProgress = 1.0
            self.currentTransferFileName = nil
            self.stopSpeedTimer()
            UserDefaults.standard.set(false, forKey: "UltraFastTransfer")
            // Also reset send-side state if this was a receive-complete call
            // (send-side cleaned up in finishAndroidFileSend)
        }
    }

    // MARK: - Speed Timer

    private func startSpeedTimer() {
        stopSpeedTimer()
        lastBytesSnapshot = 0
        speedTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            let current = self.bytesReceived
            let delta = current - self.lastBytesSnapshot
            self.lastBytesSnapshot = current
            let mbps = Double(delta) / 1_048_576.0
            DispatchQueue.main.async {
                if mbps > 0.01 {
                    self.transferSpeedString = String(format: "%.1f MB/s", mbps)
                } else {
                    self.transferSpeedString = ""
                }
            }
        }
    }

    private func stopSpeedTimer() {
        speedTimer?.invalidate()
        speedTimer = nil
        transferSpeedString = ""
    }

    // MARK: - Manual Cancellation

    func cancelReceive() {
        activeReceiveConnection?.cancel()
        activeReceiveConnection = nil

        activeReceiveFileHandle?.closeFile()
        activeReceiveFileHandle = nil
        if let url = activeReceiveFileURL {
            try? FileManager.default.removeItem(at: url)
            activeReceiveFileURL = nil
        }

        DispatchQueue.main.async {
            self.transferProgress = 0
            self.bytesReceived = 0
            self.currentTransferFileName = nil
        }
    }

    func cancelSend() {
        for url in queuedAndroidFiles {
            if url.path.contains("PendingDrops") {
                try? FileManager.default.removeItem(at: url)
            }
        }
        queuedAndroidFiles.removeAll()

        if let currentURL = activeSendFileURL, currentURL.path.contains("PendingDrops") {
            try? FileManager.default.removeItem(at: currentURL)
        }
        activeSendFileURL = nil

        activeSendConnection?.cancel()
        activeSendConnection = nil
        DispatchQueue.main.async {
            self.isSendingFile = false
            self.sendFileProgress = 0
            self.currentTransferFileName = nil
        }
    }

    // MARK: - Outbound Sending (Mac -> Android) (Local BLE + TCP path)

    /// Send plain text to Android.
    /// Payloads whose encrypted JSON fits in a single BLE notify (≤ 500 B) are sent inline.
    /// Larger payloads are streamed over TCP using the same encrypted-chunk protocol as file
    /// transfers, signalled with `text_incoming` so Android sets the clipboard instead of
    /// saving to Downloads.
    func sendTextToAndroid(_ text: String, completion: @escaping (Bool) -> Void = { _ in }) {
        guard ClipboardManager.shared.syncFromMac else {
            completion(false)
            return
        }
        guard let hexKey = KeychainHelper.getEncryptionKey(),
              let keyData = hexKey.hexToData() else {
            completion(false)
            return
        }

        queue.async { [weak self] in
            guard let self else { completion(false); return }
            guard let plainData = text.data(using: .utf8) else { completion(false); return }

            // Encrypt the payload
            let key = SymmetricKey(data: keyData)
            let nonce = AES.GCM.Nonce()
            guard let sealed = try? AES.GCM.seal(plainData, using: key, nonce: nonce) else {
                completion(false)
                return
            }
            let encryptedData = sealed.combined! // nonce(12) + ciphertext + tag(16)
            let base64 = encryptedData.base64EncodedString()

            // Build the BLE JSON payload
            let payload: [String: Any] = [
                "type": "text",
                "content": base64
            ]

            if let jsonData = try? JSONSerialization.data(withJSONObject: payload),
               jsonData.count <= 500 {
                // Fast path: fits in a single BLE notify
                let pushed = WakeupReceiver.shared.pushToAndroid(jsonData)
                if pushed {
                    completion(true)
                } else {
                    completion(false)
                }
            } else {
                // Payload too large for BLE — stream raw UTF-8 bytes over TCP.
                // Android's `text_incoming` handler reads the bytes and sets the clipboard
                // instead of saving to Downloads (cf. file_incoming).
                self.sendLargeTextViaTCP(text: text, completion: completion)
            }
        }
    }

    /// Streams [text] to Android over TCP using the same encrypted-chunk protocol as file
    /// transfers. Sends a `text_incoming` BLE signal (instead of `file_incoming`) so Android
    /// knows to push the received bytes to the system clipboard rather than save to Downloads.
    private func sendLargeTextViaTCP(text: String, completion: @escaping (Bool) -> Void) {
        guard WakeupReceiver.shared.hasAndroidSubscriber else {
            completion(false)
            return
        }

        guard let textData = text.data(using: .utf8) else {
            completion(false)
            return
        }

        // Write to a temp file so we can hand a FileHandle to the existing streaming path.
        let tmpURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("clipsync_text_\(UUID().uuidString).tmp")
        do {
            try textData.write(to: tmpURL)
        } catch _ {
            completion(false)
            return
        }

        let fileSize = Int64(textData.count)
        let androidTcpPort = 8766

        // 1. Pre-ping Android to get a fresh IP and wake it from doze.
        //    Clear lastPing FIRST — otherwise the wait loop below can match
        //    a stale ping_ack from a previous transfer and exit immediately,
        //    causing Mac to dial Android before it's actually awake.
        DispatchQueue.main.sync { WakeupReceiver.shared.lastPing = nil }
        let prePing: [String: Any] = ["type": "ping"]
        WakeupReceiver.shared.pushToAndroid(json: prePing)

        let pingDeadline = Date().addingTimeInterval(5.0)
        while Date() < pingDeadline {
            if let p = WakeupReceiver.shared.lastPing, p.payloadType == "ping_ack" { break }
            usleep(50_000)
        }

        guard let androidIp = WakeupReceiver.shared.getFreshAndroidIp() else {
            try? FileManager.default.removeItem(at: tmpURL)
            completion(false)
            return
        }

        // 2. Signal Android: text is incoming (clipboard, not a file download)
        let signal: [String: Any] = [
            "type": "text_incoming",
            "size": fileSize,
            "port": androidTcpPort
        ]
        WakeupReceiver.shared.pushToAndroid(json: signal)

        // 3. Wait for tcp_ready ACK
        let tcpDeadline = Date().addingTimeInterval(3.0)
        while Date() < tcpDeadline {
            if let ping = WakeupReceiver.shared.lastPing, ping.payloadType == "tcp_ready" { break }
            usleep(50_000)
        }

        guard let fileHandle = try? FileHandle(forReadingFrom: tmpURL) else {
            try? FileManager.default.removeItem(at: tmpURL)
            completion(false)
            return
        }

        // Reuse the existing encrypted-chunk TCP streaming path.
        streamFileToAndroid(
            fileHandle: fileHandle,
            fileSize:   fileSize,
            fileName:   "__clipsync_text__",  // sentinel; Android reads this, ignores name
            ip:         androidIp,
            port:       androidTcpPort
        ) {
            // Clean up temp file after send (success or failure)
            try? FileManager.default.removeItem(at: tmpURL)
            completion(true)
        }
    }

    // MARK: - Share Extension Integration
    // The Darwin notification listener and pending file draining are handled in ClipSyncApp.swift (AppDelegate)
    // to allow it to simultaneously open the Menu Bar popover so the user sees the transfer progress.

    /// Send a file to the paired Android device via local Wi-Fi.
    /// Flow: BLE Notify {type:"file_incoming"} → Android starts TCP server →
    ///        Mac waits for BLE ACK → Mac streams file bytes over TCP.
    func sendFiles(urls: [URL]) {
        let files = urls.filter { !$0.hasDirectoryPath }
        guard !files.isEmpty else { return }

        DispatchQueue.main.async {
            self.queuedAndroidFiles.append(contentsOf: files)
            self.sendNextQueuedFileIfNeeded()
        }
    }

    private func sendNextQueuedFileIfNeeded() {
        guard !isSendingFile, !queuedAndroidFiles.isEmpty else { return }
        
        // Mark as sending synchronously so re-entrant calls don't pop multiple files at once.
        self.isSendingFile = true
        
        let next = queuedAndroidFiles.removeFirst()
        sendFileToAndroid(url: next) { [weak self] in
            // Clean up App Group share files after send
            if next.path.contains("PendingDrops") {
                try? FileManager.default.removeItem(at: next)
            }
            
            DispatchQueue.main.async {
                self?.sendNextQueuedFileIfNeeded()
            }
        }
    }

    func sendFileToAndroid(url: URL) {
        sendFiles(urls: [url])
    }

    private func sendFileToAndroid(url: URL, completion: @escaping () -> Void) {
        guard ClipboardManager.shared.syncFromMac else {
            DispatchQueue.main.async { self.isSendingFile = false }
            completion()
            return
        }
        guard WakeupReceiver.shared.hasAndroidSubscriber else {
            DispatchQueue.main.async {
                self.isSendingFile = false
                self.lastError = "Android is not reachable. Open the ClipSync app on your phone."
            }
            completion()
            return
        }

        DispatchQueue.main.async {
            self.sendFileProgress = 0
            self.transferSpeedString = ""
            self.activeSendFileURL = url
        }

        queue.async { [weak self] in
            guard let self else { return }

            // Security-scoped URLs from fileImporter / drag-and-drop require this
            // before the sandbox will permit reading the file.
            let accessing = url.startAccessingSecurityScopedResource()

            var fileName  = url.lastPathComponent
            
            // Share Extension prepends a UUID and an underscore to avoid collisions.
            // Strip it so the user and the Android device see the original file name.
            let components = fileName.components(separatedBy: "_")
            if components.count > 1, UUID(uuidString: components[0]) != nil {
                // Re-join the rest in case originalName had underscores
                fileName = components.dropFirst().joined(separator: "_")
            }
            
            let fileSize  = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
            DispatchQueue.main.async {
                self.currentTransferFileName = fileName
                self.transferTotalBytes = fileSize
            }

            guard let fileHandle = try? FileHandle(forReadingFrom: url) else {
                if accessing { url.stopAccessingSecurityScopedResource() }
                DispatchQueue.main.async {
                    self.isSendingFile = false
                    self.currentTransferFileName = nil
                    self.lastError = "Could not read file. Try moving it to Downloads first."
                    completion()
                }
                return
            }

            // Stop the scope once the handle is open — the kernel file descriptor
            // keeps the file accessible for the lifetime of the handle.
            if accessing { url.stopAccessingSecurityScopedResource() }

            let androidTcpPort = 8766

            // 1. Pre-ping Android to get a fresh IP and wake it from doze.
            //    Android responds with a "ping_ack" wakeup ping containing its current IP.
            //    Clear lastPing FIRST — otherwise the wait loop below can match
            //    a stale ping_ack from a previous transfer and exit immediately,
            //    causing Mac to dial Android before it's actually awake.
            DispatchQueue.main.sync { WakeupReceiver.shared.lastPing = nil }
            let prePing: [String: Any] = ["type": "ping"]
            WakeupReceiver.shared.pushToAndroid(json: prePing)

            // Wait up to 5s for ping_ack. 3s was too tight for dozing Android
            // whose BLE stack can take 2-4s to fully process the wakeup.
            let pingDeadline = Date().addingTimeInterval(5.0)
            while Date() < pingDeadline {
                if let p = WakeupReceiver.shared.lastPing, p.payloadType == "ping_ack" { break }
                usleep(50_000)
            }

            guard let androidIp = WakeupReceiver.shared.getFreshAndroidIp() else {
                fileHandle.closeFile()
                DispatchQueue.main.async {
                    self.isSendingFile = false
                    self.currentTransferFileName = nil
                    self.transferSpeedString = ""
                    self.lastError = "Could not reach Android. Make sure both devices are on the same Wi-Fi."
                    completion()
                }
                return
            }

            // 2. BLE Notify: tell Android a file is incoming (now Android starts TCP server)
            let signal: [String: Any] = [
                "type": "file_incoming",
                "filename": fileName,
                "size": fileSize,
                "port": androidTcpPort
            ]
            WakeupReceiver.shared.pushToAndroid(json: signal)

            // 3. Wait for tcp_ready ACK (Android confirms its TCP server is up)
            let tcpDeadline = Date().addingTimeInterval(3.0)
            while Date() < tcpDeadline {
                if let ping = WakeupReceiver.shared.lastPing, ping.payloadType == "tcp_ready" { break }
                usleep(50_000)
            }

            // 4. Dial Android's TCP server and stream
            self.streamFileToAndroid(
                fileHandle: fileHandle,
                fileSize:   fileSize,
                fileName:   fileName,
                ip:         androidIp,
                port:       androidTcpPort,
                completion: completion
            )
        }
    }

    /// Alias kept for backwards compatibility with MenuBarView and other callers.
    func sendFile(url: URL) {
        sendFiles(urls: [url])
    }

    // MARK: - TCP stream to Android (streaming — no full RAM load)

    private func streamFileToAndroid(
        fileHandle: FileHandle,
        fileSize:   Int64,
        fileName:   String,
        ip:         String,
        port:       Int,
        completion: @escaping () -> Void
    ) {
        guard let hexKey = KeychainHelper.getEncryptionKey(),
              let keyData = hexKey.hexToData() else {
            fileHandle.closeFile()
            DispatchQueue.main.async {
                self.isSendingFile = false
                self.currentTransferFileName = nil
                self.transferSpeedString = ""
                completion()
            }
            return
        }

        let key = SymmetricKey(data: keyData)
        let chunkSize  = 1024 * 1024
        let magic: UInt32 = 0x434C5359
        let isUltraFast = UserDefaults.standard.bool(forKey: "UltraFastTransfer")
        let typeCode: UInt8 = isUltraFast ? 0x04 : 0x03

        // Build 24-byte header
        var header = Data(capacity: 24)
        var magicBE  = magic.bigEndian;    header.append(contentsOf: withUnsafeBytes(of: &magicBE)  { Array($0) })
        header.append(0x01)               // version
        header.append(typeCode)
        header.append(contentsOf: [0x00, 0x00]) // reserved
        var totalBE  = fileSize.bigEndian; header.append(contentsOf: withUnsafeBytes(of: &totalBE)  { Array($0) })
        var chunkBE  = Int64(chunkSize).bigEndian; header.append(contentsOf: withUnsafeBytes(of: &chunkBE)  { Array($0) })

        // Filename preamble
        let nameData = fileName.data(using: .utf8) ?? Data()
        var nameLenBE = UInt32(nameData.count).bigEndian
        var preamble = Data()
        preamble.append(contentsOf: withUnsafeBytes(of: &nameLenBE) { Array($0) })
        preamble.append(nameData)

        let host       = NWEndpoint.Host(ip)
        let nwPort     = NWEndpoint.Port(rawValue: UInt16(port))!
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        let outgoingParams = NWParameters(tls: nil, tcp: tcpOptions)
        let connection = NWConnection(host: host, port: nwPort, using: outgoingParams)
        activeSendConnection = connection

        let startTime  = Date()

        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:

                // Send header + preamble first, then stream chunks
                let initialPayload = header + preamble
                connection.send(content: initialPayload, completion: .contentProcessed { [weak self] error in
                    guard let self else { return }
                    if let error {
                        fileHandle.closeFile()
                        self.finishAndroidFileSend(connection: connection, fileName: fileName, error: error, completion: completion)
                        return
                    }
                    if typeCode == 0x04 {
                        self.sendNextZeroCopyChunk(
                            connection: connection,
                            fileHandle: fileHandle,
                            fileSize: fileSize,
                            fileName: fileName,
                            totalSent: 0,
                            startTime: startTime,
                            completion: completion
                        )
                    } else {
                        // Kick off recursive chunk streaming
                        self.sendNextChunk(
                            connection:  connection,
                            fileHandle:  fileHandle,
                            fileSize:    fileSize,
                            fileName:    fileName,
                            key:         key,
                            chunkSize:   chunkSize,
                            totalSent:   0,
                            startTime:   startTime,
                            completion:  completion
                        )
                    }
                })

            case .failed(let err):
                fileHandle.closeFile()
                DispatchQueue.main.async {
                    self.isSendingFile = false
                    self.currentTransferFileName = nil
                    self.transferSpeedString = ""
                    self.lastError = "Transfer failed: \(err.localizedDescription)"
                    completion()
                }
                connection.cancel()
            default: break
            }
        }
        connection.start(queue: queue)

        // Safety timeout — 5 min for large files
        queue.asyncAfter(deadline: .now() + 300) {
            connection.cancel()
        }
    }

    /// Reads one chunk from FileHandle, encrypts it, and sends it.
    /// Calls itself recursively until EOF, then calls finishAndroidFileSend.
    private func sendNextChunk(
        connection: NWConnection,
        fileHandle: FileHandle,
        fileSize:   Int64,
        fileName:   String,
        key:        SymmetricKey,
        chunkSize:  Int,
        totalSent:  Int64,
        startTime:  Date,
        completion: @escaping () -> Void
    ) {
        // Read next chunk from disk — only chunkSize bytes at a time
        let rawChunk: Data
        if #available(macOS 10.15.4, *) {
            rawChunk = (try? fileHandle.read(upToCount: chunkSize)) ?? Data()
        } else {
            rawChunk = fileHandle.readData(ofLength: chunkSize)
        }

        guard !rawChunk.isEmpty else {
            // EOF reached — we're done
            fileHandle.closeFile()
            finishAndroidFileSend(connection: connection, fileName: fileName, error: nil, completion: completion)
            return
        }

        let nonce = AES.GCM.Nonce()
        guard let sealed = try? AES.GCM.seal(rawChunk, using: key, nonce: nonce),
              let combined = sealed.combined else {
            fileHandle.closeFile()
            finishAndroidFileSend(connection: connection, fileName: fileName, error: ClipSyncServerError.encryptionFailed, completion: completion)
            return
        }

        var packet = Data(capacity: 4 + combined.count)
        var lenBE  = UInt32(combined.count).bigEndian
        packet.append(contentsOf: withUnsafeBytes(of: &lenBE) { Array($0) })
        packet.append(combined)

        let newTotalSent = totalSent + Int64(rawChunk.count)
        let elapsed = max(Date().timeIntervalSince(startTime), 0.001)
        let mbps = Double(newTotalSent) / elapsed / 1_048_576.0
        DispatchQueue.main.async {
            self.sendFileProgress = Double(newTotalSent) / Double(max(fileSize, 1))
            self.transferSpeedString = mbps > 0.01 ? String(format: "%.1f MB/s", mbps) : ""
        }

        connection.send(content: packet, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if let error {
                fileHandle.closeFile()
                self.finishAndroidFileSend(connection: connection, fileName: fileName, error: error, completion: completion)
                return
            }
            // Send next chunk — recurse
            self.sendNextChunk(
                connection: connection,
                fileHandle: fileHandle,
                fileSize:   fileSize,
                fileName:   fileName,
                key:        key,
                chunkSize:  chunkSize,
                totalSent:  newTotalSent,
                startTime:  startTime,
                completion: completion
            )
        })
    }

    private func sendNextZeroCopyChunk(
        connection: NWConnection,
        fileHandle: FileHandle,
        fileSize:   Int64,
        fileName:   String,
        totalSent:  Int64,
        startTime:  Date,
        completion: @escaping () -> Void
    ) {
        if totalSent >= fileSize {
            self.finishAndroidFileSend(connection: connection, fileName: fileName, error: nil, completion: completion)
            return
        }

        let chunkData = fileHandle.readData(ofLength: 4 * 1024 * 1024)   // ~2-5ms, negligible vs the send below
        if chunkData.isEmpty {
            self.finishAndroidFileSend(connection: connection, fileName: fileName, error: nil, completion: completion)
            return
        }

        connection.send(content: chunkData, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if let error {
                fileHandle.closeFile()
                self.finishAndroidFileSend(connection: connection, fileName: fileName, error: error, completion: completion)
                return
            }

            let newTotalSent = totalSent + Int64(chunkData.count)
            DispatchQueue.main.async {
                self.sendFileProgress = fileSize > 0 ? Double(newTotalSent) / Double(fileSize) : 0
                let elapsed = max(0.001, Date().timeIntervalSince(startTime))
                self.transferSpeedString = String(format: "%.1f MB/s", Double(newTotalSent) / elapsed / 1_048_576.0)
            }

            self.sendNextZeroCopyChunk(
                connection: connection, fileHandle: fileHandle, fileSize: fileSize,
                fileName: fileName, totalSent: newTotalSent, startTime: startTime, completion: completion
            )
        })
    }

    private func finishAndroidFileSend(connection: NWConnection, fileName: String, error: Error?, completion: @escaping () -> Void) {
        let sentFileURL = activeSendFileURL
        if let currentURL = activeSendFileURL, currentURL.path.contains("PendingDrops") {
            try? FileManager.default.removeItem(at: currentURL)
        }
        activeSendFileURL = nil

        if let error {
        } else {
        }

        DispatchQueue.main.async {
            if let error {
                self.lastError = "Transfer error: \(error.localizedDescription)"
            } else {
                let isImg = fileName.lowercased().hasSuffix(".png") || fileName.lowercased().hasSuffix(".jpg") || fileName.lowercased().hasSuffix(".jpeg")
                let deviceName = PairingManager.shared.pairedDeviceName
                let newItem = ClipboardItem(
                    content: fileName,
                    timestamp: Date(),
                    deviceName: deviceName,
                    direction: .sent,
                    isImage: isImg,
                    isFile: true,
                    filePath: sentFileURL?.path
                )
                ClipboardManager.shared.history.insert(newItem, at: 0)
            }
            self.isSendingFile = false
            self.sendFileProgress = error == nil ? 1.0 : 0.0
            self.currentTransferFileName = nil
            self.transferSpeedString = ""
            UserDefaults.standard.set(false, forKey: "UltraFastTransfer")
            completion()
        }
        connection.cancel()
    }

    // MARK: - File Receive (unchanged)

    private func saveFileToDisk(data: Data, fileName: String?) {
        // Intentionally left blank as file saving is now incremental in readChunks
    }

    // MARK: - Low-level read helpers

    /// Reads exactly [length] bytes, calling [completion] on success or nil on failure.
    private func readExact(
        connection: NWConnection,
        length:     Int,
        completion: @escaping (Data?) -> Void
    ) {
        connection.receive(
            minimumIncompleteLength: length,
            maximumLength:           length
        ) { data, _, isDone, error in
            if let error {
                completion(nil)
                return
            }
            guard let data, data.count == length else {
                completion(nil)
                return
            }
            completion(data)
        }
    }
}

private enum ClipSyncServerError: LocalizedError {
    case encryptionFailed

    var errorDescription: String? {
        switch self {
        case .encryptionFailed:
            return "Could not encrypt file chunk"
        }
    }
}

// MARK: - Data helpers

private extension Data {
    func readUInt32BE(at offset: Int) -> UInt32 {
        let slice = self[offset ..< offset + 4]
        return slice.reversed().enumerated().reduce(0) { acc, pair in
            acc | (UInt32(pair.element) << (pair.offset * 8))
        }
    }

    func readInt64BE(at offset: Int) -> Int64 {
        let slice = self[offset ..< offset + 8]
        return slice.reversed().enumerated().reduce(0) { acc, pair in
            acc | (Int64(pair.element) << (pair.offset * 8))
        }
    }
}

private extension String {
    func hexToData() -> Data? {
        let clean = self.lowercased()
        guard clean.count % 2 == 0 else { return nil }
        var data = Data(capacity: clean.count / 2)
        var idx = clean.startIndex
        while idx < clean.endIndex {
            let nextIdx = clean.index(idx, offsetBy: 2)
            guard let byte = UInt8(clean[idx ..< nextIdx], radix: 16) else { return nil }
            data.append(byte)
            idx = nextIdx
        }
        return data
    }
}
