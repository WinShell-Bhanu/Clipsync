import SwiftUI
import CoreImage.CIFilterBuiltins
import Combine
import CryptoKit

class QRCodeGenerator: ObservableObject {
    static let shared = QRCodeGenerator()
    
    @Published var qrImage: NSImage?
    @Published var pairingCode: String = ""
    
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()
    
    private var sharedSecretHex: String {
        get {
            if let savedKey = UserDefaults.standard.string(forKey: "encryption_key") {
                return savedKey
            }
            let newKey = generateRandomHexKey()
            UserDefaults.standard.set(newKey, forKey: "encryption_key")
            return newKey
        }
    }
    
    func generateQRCode() {
        let macDeviceId = DeviceManager.shared.getDeviceId()
        let macName = DeviceManager.shared.getMacName()
        let currentRegion = UserDefaults.standard.string(forKey: "server_region") ?? "IN"
        
        let jsonDict: [String: String] = [
            "macId": macDeviceId,
            "deviceName": macName,
            "server": currentRegion,
            "secret": sharedSecretHex
        ]
        
        var plainTextData: Data?
        if let jsonData = try? JSONSerialization.data(withJSONObject: jsonDict) {
            plainTextData = jsonData
        } else {
             let jsonString = "{\"macId\":\"\(macDeviceId)\",\"deviceName\":\"\(macName)\",\"server\":\"\(currentRegion)\",\"secret\":\"\(sharedSecretHex)\"}"
             plainTextData = jsonString.data(using: .utf8)
        }
        
        guard let dataToEncrypt = plainTextData,
              let jsonString = String(data: dataToEncrypt, encoding: .utf8) else {
            return
        }

        pairingCode = jsonString
        
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
    
    // Helper to convert Hex String to Data
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
    // Fixed Helper naming conflict potentially - ensuring it's private
    private func generateRandomHexKey() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        
        if status == errSecSuccess {
            return bytes.map { String(format: "%02hhX", $0) }.joined()
        }
        print("Failed to generate random key, falling back to legacy default (NOT SECURE)")
        return Secrets.fallbackEncryptionKey
    }
}

