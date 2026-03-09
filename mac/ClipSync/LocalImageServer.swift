// LocalImageServer.swift
// Tier 1 image reception on the Mac side:
//   - Advertises "_clipsync._tcp" via Bonjour so Android can find the Mac on the LAN.
//   - Runs an NWListener on port 58485 that accepts exactly one endpoint: POST /receive-image.
//   - Validates the X-Pairing-Id header before reading the body.
//   - Verifies the X-Signature (HMAC-SHA256) before passing bytes to the clipboard.
//
// macOS local network permission:
//   The first time NWListener starts, macOS shows: "ClipSync wants to find devices on
//   your local network." If the user denies it, NWListener fails with NWError.
//   ImageTransferManager observes this and emits .localNetworkPermissionDenied,
//   which the UI surfaces as a one-time informational banner (not a crash).

import Foundation
import Network
import CryptoKit
import AppKit
import os

final class LocalImageServer {

    static let shared = LocalImageServer()

    private let port: NWEndpoint.Port = 58485
    private var listener: NWListener?
    private let logger = Logger(subsystem: "com.OP.ClipSync", category: "ImageTransfer")

    /// `true` once the listener is running and the service has been registered.
    private(set) var isRunning = false

    /// Set to `false` permanently if the user denies the local network permission.
    /// ImageTransferManager reads this before deciding to attempt Tier 1.
    private(set) var localNetworkPermissionGranted = true

    private init() {}

    // MARK: - Start / Stop

    /// Starts the HTTP listener and registers the Bonjour service.
    /// Safe to call multiple times; subsequent calls are no-ops if already running.
    func start() {
        guard !isRunning else { return }

        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true

        guard let listener = try? NWListener(using: params, on: port) else {
            logger.error("LocalImageServer: Failed to create NWListener on port \(self.port.debugDescription)")
            return
        }

        listener.service = NWListener.Service(name: "ClipSyncMac", type: "_clipsyncmac._tcp")

        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.isRunning = true
                self?.logger.info("LocalImageServer: ✅ Bonjour service _clipsyncmac._tcp ready on port \(self?.port.debugDescription ?? "?")")
            case .failed(let error):
                self?.isRunning = false
                // NWError.posix(.EPERM) is the signal for "local network permission denied".
                if case .posix(let code) = error, code == .EPERM {
                    self?.localNetworkPermissionGranted = false
                    self?.logger.error("LocalImageServer: Local network permission denied by user")
                    ImageTransferManagerMac.shared.onLocalNetworkPermissionDenied()
                } else {
                    self?.logger.error("LocalImageServer: Listener failed: \(error)")
                }
            case .cancelled:
                self?.isRunning = false
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] connection in
            self?.handleConnection(connection)
        }

        listener.start(queue: .global(qos: .userInitiated))
        self.listener = listener
    }

    func stop() {
        listener?.cancel()
        listener  = nil
        isRunning = false
    }

    // MARK: - Connection handling

    private func handleConnection(_ connection: NWConnection) {
        let remote = connection.endpoint.debugDescription
        logger.info("LocalImageServer: New connection from \(remote)")
        connection.start(queue: .global(qos: .userInitiated))
        receiveHTTPRequest(connection: connection)
    }

    /// Reads the raw HTTP request bytes, accumulating across TCP segments until the
    /// full header block (terminated by `\r\n\r\n`) is available.
    private func receiveHTTPRequest(connection: NWConnection, buffer: Data = Data()) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self = self else { connection.cancel(); return }

            var accumulated = buffer
            if let data = data { accumulated.append(data) }

            guard !accumulated.isEmpty else {
                if let error = error { self.logger.error("LocalImageServer: Receive error: \(error)") }
                connection.cancel()
                return
            }

            if let (headers, bodyStart) = self.parseHTTPHeaders(accumulated) {

                // Security gate 1: reject anything that isn't our custom endpoint.
                guard headers["request-line"]?.contains("POST /receive-image") == true else {
                    let line = headers["request-line"] ?? "(none)"
                    self.logger.warning("LocalImageServer: Rejected: unexpected endpoint — \(line)")
                    self.sendResponse(connection: connection, status: 404, close: true)
                    return
                }

                // Security gate 2: check pairing ID before reading the body at all.
                guard let incomingPairingId = headers["x-pairing-id"],
                      let currentPairingId  = PairingManager.shared.pairingId,
                      incomingPairingId == currentPairingId else {
                    let incoming = headers["x-pairing-id"] ?? "nil"
                    self.logger.error("LocalImageServer: Rejected: pairing ID mismatch (incoming=\(incoming))")
                    self.sendResponse(connection: connection, status: 403, close: true)
                    return
                }

                let partialBody = accumulated[bodyStart...]
                let contentLength = Int(headers["content-length"] ?? "0") ?? 0
                self.logger.info("LocalImageServer: Headers OK — expecting \(contentLength) encrypted bytes, \(accumulated.count - bodyStart) already buffered")

                self.receiveBody(
                    connection: connection,
                    accumulated: Data(partialBody),
                    expected: contentLength,
                    signature: headers["x-signature"] ?? ""
                )
            } else if isComplete || accumulated.count > 1_048_576 {
                // Stream ended or 1 MB without finding headers — give up.
                self.logger.error("LocalImageServer: Incomplete HTTP headers (buffered=\(accumulated.count) bytes, streamEnded=\(isComplete))")
                connection.cancel()
            } else {
                // Headers split across TCP segments — keep accumulating.
                self.receiveHTTPRequest(connection: connection, buffer: accumulated)
            }
        }
    }

    private func receiveBody(
        connection: NWConnection,
        accumulated: Data,
        expected: Int,
        signature: String
    ) {
        if accumulated.count >= expected {
            // We have all the bytes — now verify integrity.
            let imageBytes = accumulated.prefix(expected)
            processReceivedImage(
                connection: connection,
                imageBytes: imageBytes,
                signature: signature
            )
            return
        }

        // Still waiting for more body bytes.
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, _, error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("LocalImageServer: Body receive error: \(error)")
                connection.cancel()
                return
            }
            var next = accumulated
            if let data = data { next.append(data) }
            self.receiveBody(connection: connection, accumulated: next, expected: expected, signature: signature)
        }
    }

    // MARK: - Image processing

    private func processReceivedImage(connection: NWConnection, imageBytes: Data, signature: String) {
        // Security gate 3: verify HMAC-SHA256 signature over the encrypted payload.
        guard verifySignature(data: imageBytes, base64Signature: signature) else {
            logger.error("LocalImageServer: Rejected: HMAC signature mismatch — possible tampering")
            sendResponse(connection: connection, status: 401, close: true)
            return
        }

        // Security gate 4: decrypt the AES-256-GCM encrypted image.
        guard let decryptedData = decryptImageData(imageBytes) else {
            logger.error("LocalImageServer: Rejected: decryption failed")
            sendResponse(connection: connection, status: 400, close: true)
            return
        }

        logger.info("LocalImageServer: ✅ Received \(decryptedData.count) bytes from Android — writing to pasteboard")

        // Tell the image monitor to ignore the next pasteboard change (echo prevention).
        ImageTransferManagerMac.shared.suppressNextImageChange()

        DispatchQueue.main.async {
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            if let image = NSImage(data: decryptedData) {
                // Write as both NSImage and raw PNG/JPEG so the target app can choose.
                pasteboard.writeObjects([image])
                pasteboard.setData(decryptedData, forType: .png)
            } else {
                // Unknown format — write raw bytes; the user can paste into a hex editor etc.
                pasteboard.setData(decryptedData, forType: NSPasteboard.PasteboardType("public.data"))
            }

            // Record in clipboard history (no image data retained — RAM friendly).
            ClipboardManager.shared.addImageHistoryEntry(
                direction: .received,
                deviceName: PairingManager.shared.pairedDeviceName
            )
        }

        sendResponse(connection: connection, status: 200, close: true)
    }

    // MARK: - AES-GCM Decryption

    /// Decrypts AES-256-GCM encrypted data. Expected format: [12-byte IV][ciphertext][16-byte tag].
    private func decryptImageData(_ encryptedData: Data) -> Data? {
        guard encryptedData.count >= 28 else { return nil } // 12 (IV) + 16 (tag) minimum
        guard let keyHex = KeychainHelper.load(for: "encryption_key") else { return nil }

        do {
            let keyBytes = hexToData(keyHex)
            let key = SymmetricKey(data: keyBytes)
            let sealedBox = try AES.GCM.SealedBox(combined: encryptedData)
            return try AES.GCM.open(sealedBox, using: key)
        } catch {
            logger.error("LocalImageServer: Decryption error: \(error)")
            return nil
        }
    }

    // MARK: - HMAC verification

    private func verifySignature(data: Data, base64Signature: String) -> Bool {
        guard let sigData = Data(base64Encoded: base64Signature) else { return false }
        guard let keyHex = KeychainHelper.load(for: "encryption_key") else { return false }

        let keyBytes = hexToData(keyHex)
        let key = SymmetricKey(data: keyBytes)
        // CryptoKit's isValidAuthenticationCode is constant-time (safe against timing attacks).
        return HMAC<SHA256>.isValidAuthenticationCode(sigData, authenticating: data, using: key)
    }

    // MARK: - HTTP response writer

    private func sendResponse(connection: NWConnection, status: Int, close: Bool) {
        let statusLine = status == 200 ? "200 OK" : "\(status) Error"
        let response   = "HTTP/1.1 \(statusLine)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        let data       = Data(response.utf8)
        connection.send(content: data, completion: .contentProcessed { _ in
            if close { connection.cancel() }
        })
    }

    // MARK: - Minimal HTTP header parser

    /// Parses HTTP/1.1 request headers from raw bytes.
    /// Returns a dict of lowercased header names → values, plus "request-line",
    /// and the byte offset where the body begins. Returns nil if headers are incomplete.
    private func parseHTTPHeaders(_ data: Data) -> ([String: String], Int)? {
        // Find \r\n\r\n in raw bytes — the body after this boundary is encrypted
        // binary data that MUST NOT be interpreted as UTF-8.
        let separator = Data([0x0D, 0x0A, 0x0D, 0x0A])
        guard let range = data.range(of: separator) else { return nil }

        let headerData = data[data.startIndex..<range.lowerBound]
        guard let headerString = String(data: headerData, encoding: .utf8) else { return nil }

        var headers: [String: String] = [:]
        let lines = headerString.components(separatedBy: "\r\n")

        guard let requestLine = lines.first else { return nil }
        headers["request-line"] = requestLine

        for line in lines.dropFirst() {
            if let colon = line.firstIndex(of: ":") {
                let name  = String(line[line.startIndex..<colon]).lowercased().trimmingCharacters(in: .whitespaces)
                let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                headers[name] = value
            }
        }

        let bodyStart = range.upperBound - data.startIndex
        return (headers, bodyStart)
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
