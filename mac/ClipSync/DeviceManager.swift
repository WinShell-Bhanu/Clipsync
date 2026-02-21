


import Foundation
import IOKit


// Purpose: Coordinator component that centralizes state, integration calls, and orchestration.
// Responsibilities: Encapsulates device manager behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
class DeviceManager {
    static let shared = DeviceManager()

    private let deviceIdKey = "mac_device_id"


    // Purpose: Returns computed or stored device id.
    // Parameters: No parameters.
    // Returns: String.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func getDeviceId() -> String {
        if let existingId = UserDefaults.standard.string(forKey: deviceIdKey) {
            return existingId
        }

        let deviceId = UUID().uuidString
        UserDefaults.standard.set(deviceId, forKey: deviceIdKey)
        return deviceId
    }


    // Purpose: Returns computed or stored mac name.
    // Parameters: No parameters.
    // Returns: String.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func getMacName() -> String {
        return Host.current().localizedName ?? "Mac"
    }


    // Purpose: Returns computed or stored friendly mac name.
    // Parameters: No parameters.
    // Returns: String.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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
