// DeviceManager.swift
// Manages the stable device identity for this Mac instance and provides
// human-readable device names used in pairing and Firestore documents.

import Foundation
import IOKit

// MARK: - DeviceManager

class DeviceManager {
    static let shared = DeviceManager()

    private let deviceIdKey = "mac_device_id"

    // MARK: - Identity

    /// Returns the persisted device UUID, generating and saving one on first call.
    func getDeviceId() -> String {
        if let existingId = UserDefaults.standard.string(forKey: deviceIdKey) {
            return existingId
        }

        let deviceId = UUID().uuidString
        UserDefaults.standard.set(deviceId, forKey: deviceIdKey)
        return deviceId
    }


    /// Returns the macOS host name (e.g. "John's MacBook Pro").
    func getMacName() -> String {
        return Host.current().localizedName ?? "Mac"
    }


    /// Formats the host name into a "John's Mac" style string suitable for the paired device UI.
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
