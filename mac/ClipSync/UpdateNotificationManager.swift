import Foundation
import UserNotifications

// Purpose: UpdateNotificationManager handles pending update storage and notification display.
// Responsibilities: Manages update notification state for dialog display.
// Usage: Save pending updates when FCM notification is received, retrieve in HomeScreen.
class UpdateNotificationManager {
    static let shared = UpdateNotificationManager()
    
    private let defaults = UserDefaults.standard
    private let KEY_PENDING_VERSION = "pending_update_version"
    private let KEY_PENDING_URL = "pending_update_url"
    private let KEY_PENDING_NOTES = "pending_update_notes"
    private let KEY_HAS_PENDING = "has_pending_update"
    
    private init() {}
    
    
    // Purpose: Saves pending update information.
    // Parameters: version, downloadUrl, releaseNotes.
    // Returns: Void unless returned explicitly.
    // Notes: Data persists until cleared by user.
    func savePendingUpdate(version: String, downloadUrl: String, releaseNotes: String) {
        defaults.set(version, forKey: KEY_PENDING_VERSION)
        defaults.set(downloadUrl, forKey: KEY_PENDING_URL)
        defaults.set(releaseNotes, forKey: KEY_PENDING_NOTES)
        defaults.set(true, forKey: KEY_HAS_PENDING)
        defaults.synchronize()
        
        print("✅ Pending update saved: \(version)")
    }
    
    
    // Purpose: Retrieves pending update information if available.
    // Parameters: No parameters.
    // Returns: UpdateInfo? - update details or nil.
    // Notes: Returns nil if no pending update.
    func getPendingUpdate() -> UpdateInfo? {
        guard defaults.bool(forKey: KEY_HAS_PENDING),
              let version = defaults.string(forKey: KEY_PENDING_VERSION),
              let url = defaults.string(forKey: KEY_PENDING_URL) else {
            return nil
        }
        
        let notes = defaults.string(forKey: KEY_PENDING_NOTES) ?? "New update available!"
        return UpdateInfo(version: version, downloadUrl: url, releaseNotes: notes)
    }
    
    
    // Purpose: Clears pending update information.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Call after user dismisses update dialog.
    func clearPendingUpdate() {
        defaults.removeObject(forKey: KEY_PENDING_VERSION)
        defaults.removeObject(forKey: KEY_PENDING_URL)
        defaults.removeObject(forKey: KEY_PENDING_NOTES)
        defaults.set(false, forKey: KEY_HAS_PENDING)
        defaults.synchronize()
    }
    
    
    // Purpose: Data structure for update information.
    // Responsibilities: Holds update metadata.
    // Usage: Return type for getPendingUpdate().
    struct UpdateInfo {
        let version: String
        let downloadUrl: String
        let releaseNotes: String
    }
}
