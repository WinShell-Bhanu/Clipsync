// QRCodeGenerator.swift
// Generates the QR code that the Android app scans to initiate pairing.
// The payload is a JSON object containing macId, deviceName, server region,
// and the shared AES-256 encryption key (hex-encoded).

import SwiftUI
import CoreImage.CIFilterBuiltins
import Combine
import CryptoKit

// MARK: - QRCodeGenerator

class QRCodeGenerator: ObservableObject {
    static let shared = QRCodeGenerator()

    @Published var qrImage: NSImage?
    @Published var pairingCode: String = ""
    @Published var currentPairingId: String = UserDefaults.standard.string(forKey: "ble_pairing_uuid") ?? ""

    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    private var sharedSecretHex: String? {
        get {
            if let savedKey = KeychainHelper.getEncryptionKey() {
                return savedKey
            }
            guard let newKey = generateRandomHexKey() else { return nil }
            KeychainHelper.setEncryptionKey(newKey)
            return newKey
        }
    }

    // MARK: - QR Generation

    /// Builds the JSON pairing payload, encodes it as a QR using CoreImage,
    /// and scales the result to a sharp NSImage.
    func generateQRCode() {
        let macDeviceId = DeviceManager.shared.getDeviceId()
        let macName = DeviceManager.shared.getMacName()
        let currentRegion = UserDefaults.standard.string(forKey: "server_region") ?? "IN"
        // Read the sync mode selected by the user on the SyncMode screen.
        // Defaults to "hybrid" if none has been chosen yet.
        let syncMode = UserDefaults.standard.string(forKey: "sync_mode") ?? "hybrid"
        
        // Generate a stable pairing UUID — reuse if one was already generated this session.
        let pairingId = getPairingId()
        currentPairingId = pairingId
        
        // Generate a one-time BLE auth challenge token for this pairing session.
        let bleAuthToken = generateRandomBase64Token(byteCount: 32)

        guard let secretHex = sharedSecretHex else {
            return
        }

        var jsonDict: [String: Any] = [
            "pairingId": pairingId,
            "macId": macDeviceId,
            "deviceName": macName,
            "secret": secretHex,
            "bleAuthToken": bleAuthToken
        ]

        if syncMode != "local" {
            jsonDict["server"] = currentRegion
            jsonDict["syncMode"] = syncMode
        }

        var plainTextData: Data?
        if let jsonData = try? JSONSerialization.data(withJSONObject: jsonDict, options: []) {
            plainTextData = jsonData
        } else {
            // Fallback: hand-craft minimal JSON if serialization fails
            let jsonString = syncMode == "local"
                ? "{\"pairingId\":\"\(pairingId)\",\"macId\":\"\(macDeviceId)\",\"deviceName\":\"\(macName)\",\"secret\":\"\(secretHex)\"}"
                : "{\"pairingId\":\"\(pairingId)\",\"macId\":\"\(macDeviceId)\",\"deviceName\":\"\(macName)\",\"server\":\"\(currentRegion)\",\"secret\":\"\(secretHex)\",\"syncMode\":\"\(syncMode)\"}"
            plainTextData = jsonString.data(using: .utf8)
        }

        guard let dataToEncrypt = plainTextData,
              let jsonString = String(data: dataToEncrypt, encoding: .utf8) else {
            return
        }

        // Encrypt the minified payload to hide the session key from casual scanners
        pairingCode = encryptQRPayload(jsonString)

        let data = Data(pairingCode.utf8)
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("L", forKey: "inputCorrectionLevel")

        guard let outputImage = filter.outputImage else {
            return
        }

        let transform = CGAffineTransform(scaleX: 10, y: 10)
        let scaledImage = outputImage.transformed(by: transform)

        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
            return
        }

        qrImage = NSImage(cgImage: cgImage, size: NSSize(
            width: scaledImage.extent.width,
            height: scaledImage.extent.height
        ))
    }


    /// Returns a stable pairing UUID for this session, persisting it in UserDefaults.
    private func getPairingId() -> String {
        let key = "ble_pairing_uuid"
        if let saved = UserDefaults.standard.string(forKey: key) {
            return saved
        }
        let newId = UUID().uuidString
        UserDefaults.standard.set(newId, forKey: key)
        return newId
    }

    /// Generates a cryptographically random token of `byteCount` bytes, Base64-encoded.
    private func generateRandomBase64Token(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
    }

    /// Converts a hex string to Data (used for key derivation).
    private func startHexToData(hex: String) -> Data {
        var data = Data()
        var temp = ""
        for char in hex {
            temp.append(char)
            if temp.count == 2 {
                if let byte = UInt8(temp, radix: 16) {
                    data.append(byte)
                }
                temp = ""
            }
        }
        return data
    }


    /// Generates a cryptographically random 256-bit key as a hex string via SecRandomCopyBytes.
    private func generateRandomHexKey() -> String? {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)

        if status == errSecSuccess {
            return bytes.map { String(format: "%02hhX", $0) }.joined()
        }
        return nil
    }

    /// Encrypts QR payload using AES-256-GCM with a application pairing secret
    private func encryptQRPayload(_ jsonString: String) -> String {
        guard let passKeyData = "ClipSync-QR-Payload-V1-Secret-PassKey".data(using: .utf8) else { return jsonString }
        let symKey = SymmetricKey(data: SHA256.hash(data: passKeyData))
        guard let jsonBytes = jsonString.data(using: .utf8),
              let sealed = try? AES.GCM.seal(jsonBytes, using: symKey),
              let combined = sealed.combined else {
            return jsonString
        }
        return "CLIPS1:" + combined.base64EncodedString()
    }
}
