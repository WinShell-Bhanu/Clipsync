//
// DeviceManager.swift
// ClipSync
//

import Foundation
import IOKit

class DeviceManager {
    static let shared = DeviceManager()
    
    private let deviceIdKey = "mac_device_id"
    
    func getDeviceId() -> String {
        if let existingId = UserDefaults.standard.string(forKey: deviceIdKey) {
            return existingId
        }
        
        let deviceId = UUID().uuidString
        UserDefaults.standard.set(deviceId, forKey: deviceIdKey)
        return deviceId
    }
    
    func getMacName() -> String {
        return Host.current().localizedName ?? "Mac"
    }
    
    func getFriendlyMacName() -> String {
        let fullName = getMacName()
        let components = fullName.split(separator: " ")
        if let firstWord = components.first {
            let name = String(firstWord)
            if name.hasSuffix("'s") || name.hasSuffix("’s") {
                return "\(name) Mac"
            }
            return "\(name)'s Mac"
        }
        return "My Mac"
    }
}
