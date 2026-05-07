// ImageTransferManagerMac.swift
// Tier 1 orchestrator for image clipboard sync on macOS.
//
// Responsibilities:
//   1. Detects image content in NSPasteboard (separate from text monitoring in ClipboardManager).
//   2. Sends images to the paired Android device via NWBrowser service discovery + HTTP POST.
//   3. Manages the LocalImageServer lifecycle (receives images FROM Android).
//   4. Owns the transfer state machine with aggressive timeouts and explicit error reporting.
//
// Architecture:
//   Mac copies image  → ImageTransferManagerMac detects it → discovers Android via mDNS
//                      → encrypts (AES-GCM) → POST /receive-image → Android pastes
//   Android copies image → POST /receive-image to Mac → LocalImageServer receives
//                         → verifies HMAC → decrypts → pastes to NSPasteboard

import Foundation
import Network
import CryptoKit
import AppKit
import Combine
import os

// MARK: - Transfer State Machine

enum ImageTransferState: Equatable {
    case idle
    case discovering              // mDNS lookup in progress
    case connecting               // HTTP POST in progress
    case completed                // transfer succeeded
    case failed(ImageTransferError)
}

enum ImageTransferError: String, Equatable {
    case notPaired               = "Pair with an Android device first"
    case noEncryptionKey         = "Security error — re-pair required"
    case discoveryTimeout        = "Android not found on this network"
    case connectionRefused       = "Could not reach Android — check firewall"
    case uploadTimeout           = "Transfer timed out"
    case httpError               = "Transfer failed"
    case encryptionFailed        = "Image encryption failed"
    case localNetworkDenied      = "Local network permission denied — check System Settings"
    case noImageData             = "No image data to send"
}

// MARK: - ImageTransferManagerMac

final class ImageTransferManagerMac: ObservableObject {

    static let shared = ImageTransferManagerMac()

    // MARK: - Published state

    @Published private(set) var state: ImageTransferState = .idle
    @Published private(set) var lastError: String?

    // MARK: - Private properties

    private var browser: NWBrowser?
    private var discoveredEndpoint: NWEndpoint?
    private var discoveryTimer: DispatchWorkItem?
    private var pasteboardTimer: DispatchSourceTimer?
    private var lastImageChangeCount: Int = 0

    /// Tracks whether we placed the last image to prevent echo detection.
    private var ignoreNextImageChange = false

    private let transferQueue = DispatchQueue(label: "com.clipsync.image.transfer", qos: .userInitiated)
    private let logger = Logger(subsystem: "com.OP.ClipSync", category: "ImageTransfer")
    private var retryImageData: Data? = nil
    private var hasRetried = false

    // MARK: - Timeouts

    private static let discoveryTimeoutSeconds: TimeInterval = 2.0
    private static let connectionTimeoutSeconds: TimeInterval = 5.0
    private static let uploadTimeoutSeconds: TimeInterval = 30.0

    private init() {}

    // MARK: - Lifecycle

    /// Starts both the LocalImageServer (for receiving) and the pasteboard image monitor (for sending).
    /// Safe to call multiple times.
    func start() {
        LocalImageServer.shared.start()
        startImageMonitoring()
    }

    func stop() {
        LocalImageServer.shared.stop()
        stopImageMonitoring()
        cancelDiscovery()
    }

    // MARK: - Image clipboard monitoring

    /// Polls NSPasteboard every 1 second for image content changes.
    /// Runs on the main queue because NSPasteboard requires main thread access.
    private func startImageMonitoring() {
        stopImageMonitoring()
        lastImageChangeCount = NSPasteboard.general.changeCount

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now() + .seconds(1), repeating: .seconds(1), leeway: .milliseconds(200))
        timer.setEventHandler { [weak self] in
            self?.checkPasteboardForImage()
        }
        timer.resume()
        pasteboardTimer = timer
    }

    private func stopImageMonitoring() {
        pasteboardTimer?.cancel()
        pasteboardTimer = nil
    }

    private func checkPasteboardForImage() {
        let pasteboard = NSPasteboard.general
        let currentCount = pasteboard.changeCount
        guard currentCount != lastImageChangeCount else { return }
        lastImageChangeCount = currentCount

        if ignoreNextImageChange {
            ignoreNextImageChange = false
            return
        }

        // Only proceed if the pasteboard contains image data but NOT text (text is handled by ClipboardManager).
        let imageTypes: [NSPasteboard.PasteboardType] = [.png, .tiff]
        guard pasteboard.availableType(from: imageTypes) != nil else { return }

        // If text is also present, let ClipboardManager handle it — skip image transfer.
        if pasteboard.string(forType: .string) != nil { return }

        guard let imageData = extractImageData(from: pasteboard) else { return }

        // Bypass sending if not paired or sync is disabled.
        let syncEnabled = UserDefaults.standard.object(forKey: "syncFromMac") as? Bool ?? true
        guard PairingManager.shared.isPaired, syncEnabled else {
            logger.warning("ImageTransfer: skipping — isPaired=\(PairingManager.shared.isPaired), syncFromMac=\(syncEnabled)")
            return
        }

        logger.info("ImageTransfer: image detected in pasteboard (\(imageData.count) bytes) — starting send")
        sendImageToAndroid(imageData)
    }

    /// Extracts raw PNG bytes from the pasteboard, converting from TIFF if needed.
    private func extractImageData(from pasteboard: NSPasteboard) -> Data? {
        // Prefer PNG directly.
        if let pngData = pasteboard.data(forType: .png) {
            return pngData
        }
        // Fall back to TIFF → convert to PNG.
        if let tiffData = pasteboard.data(forType: .tiff),
           let image = NSImage(data: tiffData),
           let tiffRep = image.tiffRepresentation,
           let bitmap = NSBitmapImageRep(data: tiffRep),
           let pngData = bitmap.representation(using: .png, properties: [:]) {
            return pngData
        }
        return nil
    }

    // MARK: - Send image to Android (Tier 1: mDNS → HTTP)

    private func sendImageToAndroid(_ imageData: Data) {
        guard state == .idle || state == .completed || isFailed(state) else {
            logger.warning("ImageTransfer: transfer already in progress — skipping")
            return
        }

        guard PairingManager.shared.pairingId != nil else {
            transitionTo(.failed(.notPaired))
            return
        }

        guard KeychainHelper.load(for: "encryption_key") != nil else {
            transitionTo(.failed(.noEncryptionKey))
            return
        }

        retryImageData = imageData
        hasRetried = false
        transitionTo(.discovering)
        discoverAndroidService(imageData: imageData)
    }

    // MARK: - mDNS Discovery (NWBrowser)

    private func discoverAndroidService(imageData: Data) {
        cancelDiscovery()

        let params = NWParameters()
        params.includePeerToPeer = true
        let browser = NWBrowser(for: .bonjour(type: "_clipsync._tcp", domain: nil), using: params)

        // Timeout: if nothing found in 2 seconds, give up.
        let timeout = DispatchWorkItem { [weak self] in
            guard let self = self, case .discovering = self.state else { return }
            self.cancelDiscovery()
            self.transitionTo(.failed(.discoveryTimeout))
        }
        DispatchQueue.global().asyncAfter(
            deadline: .now() + Self.discoveryTimeoutSeconds,
            execute: timeout
        )
        self.discoveryTimer = timeout

        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self = self else { return }
            // Find the first result that matches our service.
            for result in results {
                if case .service(let name, let type, _, _) = result.endpoint {
                    // Accept any ClipSync service — there should only be one Android on the LAN.
                    self.logger.info("ImageTransfer: found service: \(name) (\(type))")
                    self.discoveredEndpoint = result.endpoint
                    self.cancelDiscovery()
                    self.performHTTPSend(to: result.endpoint, imageData: imageData)
                    return
                }
            }
        }

        browser.stateUpdateHandler = { [weak self] newState in
            if case .failed(let error) = newState {
                self?.logger.error("ImageTransfer: browser failed: \(error)")
                self?.cancelDiscovery()
                self?.transitionTo(.failed(.discoveryTimeout))
            }
        }

        browser.start(queue: transferQueue)
        self.browser = browser
    }

    private func cancelDiscovery() {
        discoveryTimer?.cancel()
        discoveryTimer = nil
        browser?.cancel()
        browser = nil
    }

    // MARK: - HTTP POST to Android

    private func performHTTPSend(to endpoint: NWEndpoint, imageData: Data) {
        transitionTo(.connecting)

        // Resolve the endpoint to host:port via NWConnection, then POST.
        let connection = NWConnection(to: endpoint, using: .tcp)

        var didComplete = false
        let connectionTimeout = DispatchWorkItem { [weak self] in
            guard !didComplete else { return }
            didComplete = true
            connection.cancel()
            self?.transitionTo(.failed(.connectionRefused))
        }
        DispatchQueue.global().asyncAfter(
            deadline: .now() + Self.uploadTimeoutSeconds,
            execute: connectionTimeout
        )

        connection.stateUpdateHandler = { [weak self] connState in
            guard let self = self, !didComplete else { return }
            switch connState {
            case .ready:
                guard let pairingId = PairingManager.shared.pairingId,
                      let keyHex = KeychainHelper.load(for: "encryption_key") else {
                    didComplete = true
                    connectionTimeout.cancel()
                    connection.cancel()
                    self.transitionTo(.failed(.noEncryptionKey))
                    return
                }

                // Encrypt the image data with AES-256-GCM.
                guard let encryptedData = self.encryptImageData(imageData, keyHex: keyHex) else {
                    didComplete = true
                    connectionTimeout.cancel()
                    connection.cancel()
                    self.transitionTo(.failed(.encryptionFailed))
                    return
                }

                // Compute HMAC-SHA256 signature over the encrypted bytes.
                let signature = self.computeHMAC(data: encryptedData, keyHex: keyHex)

                // Build raw HTTP/1.1 request.
                var header = "POST /receive-image HTTP/1.1\r\n"
                header += "Host: localhost\r\n"
                header += "Content-Type: application/octet-stream\r\n"
                header += "Content-Length: \(encryptedData.count)\r\n"
                header += "X-Pairing-Id: \(pairingId)\r\n"
                header += "X-Signature: \(signature)\r\n"
                header += "Connection: close\r\n"
                header += "\r\n"

                var requestData = Data(header.utf8)
                requestData.append(encryptedData)

                connection.send(content: requestData, completion: .contentProcessed { [weak self] error in
                    guard !didComplete else { return }
                    if let error = error {
                        didComplete = true
                        connectionTimeout.cancel()
                        connection.cancel()
                        print("[ImageTransfer] Send error: \(error)")
                        self?.transitionTo(.failed(.httpError))
                        return
                    }

                    // Read the response to confirm 200 OK.
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, _, recvError in
                        didComplete = true
                        connectionTimeout.cancel()
                        defer { connection.cancel() }

                        if let recvError = recvError {
                            print("[ImageTransfer] Response error: \(recvError)")
                            self?.transitionTo(.failed(.httpError))
                            return
                        }

                        if let data = data,
                           let response = String(data: data, encoding: .utf8),
                           response.contains("200") {
                            self?.logger.info("ImageTransfer: ✅ image sent successfully (\(imageData.count) bytes)")
                            self?.transitionTo(.completed)
                        } else {
                            self?.logger.error("ImageTransfer: non-200 response")
                            self?.transitionTo(.failed(.httpError))
                        }
                    }
                })

            case .failed(let error):
                guard !didComplete else { return }
                didComplete = true
                connectionTimeout.cancel()
                connection.cancel()
                self.logger.error("ImageTransfer: connection failed: \(error)")
                self.transitionTo(.failed(.connectionRefused))

            default:
                break
            }
        }

        connection.start(queue: transferQueue)
    }

    // MARK: - Encryption

    /// Encrypts raw image bytes with AES-256-GCM. Returns [IV (12) + ciphertext + tag (16)].
    private func encryptImageData(_ data: Data, keyHex: String) -> Data? {
        do {
            let keyBytes = hexToData(keyHex)
            let key = SymmetricKey(data: keyBytes)
            let sealedBox = try AES.GCM.seal(data, using: key)
            return sealedBox.combined
        } catch {
            print("[ImageTransfer] Encryption error: \(error)")
            return nil
        }
    }

    /// Computes HMAC-SHA256 over `data` and returns the result as a Base64 string.
    private func computeHMAC(data: Data, keyHex: String) -> String {
        let keyBytes = hexToData(keyHex)
        let key = SymmetricKey(data: keyBytes)
        let mac = HMAC<SHA256>.authenticationCode(for: data, using: key)
        return Data(mac).base64EncodedString()
    }

    // MARK: - State transitions

    private func transitionTo(_ newState: ImageTransferState) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.state = newState

            switch newState {
            case .failed(let error):
                self.lastError = error.rawValue
                self.logger.error("ImageTransfer: ❌ \(error.rawValue, privacy: .public)")
                // Auto-retry once on transient errors (Android NSD registration just refreshed)
                if !self.hasRetried,
                   (error == .connectionRefused || error == .discoveryTimeout),
                   let data = self.retryImageData {
                    self.hasRetried = true
                    self.logger.info("ImageTransfer: retrying in 3s after \(error.rawValue, privacy: .public)...")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
                        guard let self = self, self.isFailed(self.state) else { return }
                        self.state = .idle
                        self.sendImageToAndroid(data)
                    }
                }
                // Auto-reset to idle after 3 seconds so the next image copy can be attempted.
                DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
                    if case .failed = self?.state { self?.state = .idle }
                }

            case .completed:
                self.lastError = nil
                print("[ImageTransfer] ✅ Transfer complete")
                // Record in clipboard history (lightweight — no image data stored).
                ClipboardManager.shared.addImageHistoryEntry(direction: .sent, deviceName: "Mac")
                // Auto-reset to idle after 2 seconds.
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                    if self?.state == .completed { self?.state = .idle }
                }

            default:
                break
            }
        }
    }

    private func isFailed(_ state: ImageTransferState) -> Bool {
        if case .failed = state { return true }
        return false
    }

    // MARK: - Local network permission callback

    /// Called by LocalImageServer when macOS denies local network access.
    func onLocalNetworkPermissionDenied() {
        transitionTo(.failed(.localNetworkDenied))
    }

    /// Called by LocalImageServer when an image is received from Android,
    /// to suppress echo detection in the pasteboard monitor.
    func suppressNextImageChange() {
        ignoreNextImageChange = true
    }

    // MARK: - Hex helper

    private func hexToData(_ hex: String) -> Data {
        var data = Data()
        var temp = ""
        for char in hex {
            temp.append(char)
            if temp.count == 2 {
                if let byte = UInt8(temp, radix: 16) { data.append(byte) }
                temp = ""
            }
        }
        return data
    }
}
