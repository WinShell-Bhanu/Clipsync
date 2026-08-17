// FCMTokenManager.swift
// Stores and removes the FCM device token in Firestore under fcmTokens/{deviceId}.
// Used so a Cloud Function or the Firebase console can look up tokens by device
// to send targeted push notifications.

import Foundation
import Firebase
import FirebaseFirestore

// MARK: - FCMTokenManager

class FCMTokenManager {
    static let shared = FCMTokenManager()

    private let COLLECTION_FCM_TOKENS = "fcmTokens"

    private init() {}

    // MARK: - Token Management

    /// Upserts the FCM token document with platform, projectId, deviceId, deviceName,
    /// and appVersion. Uses merge so existing fields are not overwritten.
    func storeFCMToken(token: String) async {
        guard UserDefaults.standard.string(forKey: "sync_mode") != "local" else {
            return
        }

        let deviceId = DeviceManager.shared.getDeviceId()
        let deviceName = DeviceManager.shared.getFriendlyMacName()

        // Get projectId from active Firebase configuration
        let projectId = FirebaseApp.app()?.options.projectID ?? "clipsyncind"
        
        let tokenData: [String: Any] = [
            "token": token,
            "platform": "mac",
            "projectId": projectId,
            "deviceId": deviceId,
            "deviceName": deviceName,
            "appVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0",
            "lastUpdated": FieldValue.serverTimestamp()
        ]
        
        do {
            try await Firestore.firestore()
                .collection(COLLECTION_FCM_TOKENS)
                .document(deviceId)
                .setData(tokenData, merge: true)
            
        } catch {
        }
    }
    
    
    /// Deletes the token document when the device is unpaired so stale tokens
    /// don't accumulate in Firestore.
    func deleteFCMToken() async {
        guard UserDefaults.standard.string(forKey: "sync_mode") != "local" else {
            return
        }

        let deviceId = DeviceManager.shared.getDeviceId()
        
        do {
            try await Firestore.firestore()
                .collection(COLLECTION_FCM_TOKENS)
                .document(deviceId)
                .delete()
            
        } catch {
        }
    }
}
