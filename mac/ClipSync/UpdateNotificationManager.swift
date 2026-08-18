// UpdateNotificationManager.swift
// Persists pending app update info (version, download URL, release notes) across
// app restarts using UserDefaults. Data is written when an FCM push arrives and
// cleared after the user acts on the update dialog.

import Foundation
import UserNotifications

// MARK: - UpdateNotificationManager

class UpdateNotificationManager {
    static let shared = UpdateNotificationManager()

    private let defaults = UserDefaults.standard
    private let KEY_PENDING_VERSION = "pending_update_version"
    private let KEY_PENDING_URL = "pending_update_url"
    private let KEY_PENDING_NOTES = "pending_update_notes"
    private let KEY_HAS_PENDING = "has_pending_update"

    private init() {}

    // MARK: - Update Info

    /// Stores update metadata so it survives an app relaunch before the user taps "Download".
    func savePendingUpdate(version: String, downloadUrl: String, releaseNotes: String) {
        defaults.set(version, forKey: KEY_PENDING_VERSION)
        defaults.set(downloadUrl, forKey: KEY_PENDING_URL)
        defaults.set(releaseNotes, forKey: KEY_PENDING_NOTES)
        defaults.set(true, forKey: KEY_HAS_PENDING)
        defaults.synchronize()
        

    }
    
    
    /// Returns the stored update details, or nil if no pending update exists.
    func getPendingUpdate() -> UpdateInfo? {
        guard defaults.bool(forKey: KEY_HAS_PENDING),
              let version = defaults.string(forKey: KEY_PENDING_VERSION),
              let url = defaults.string(forKey: KEY_PENDING_URL) else {
            return nil
        }
        
        let notes = defaults.string(forKey: KEY_PENDING_NOTES) ?? "New update available!"
        return UpdateInfo(version: version, downloadUrl: url, releaseNotes: notes)
    }
    
    
    /// Clears all stored update keys after the user has dismissed or acted on the dialog.
    func clearPendingUpdate() {
        defaults.removeObject(forKey: KEY_PENDING_VERSION)
        defaults.removeObject(forKey: KEY_PENDING_URL)
        defaults.removeObject(forKey: KEY_PENDING_NOTES)
        defaults.set(false, forKey: KEY_HAS_PENDING)
        defaults.synchronize()
    }
    
    
    // MARK: - UpdateInfo

    /// Value type holding the metadata for a pending app update.
    struct UpdateInfo {
        let version: String
        let downloadUrl: String
        let releaseNotes: String
    }
}
