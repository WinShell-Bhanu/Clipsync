import Foundation
import Firebase
import FirebaseFirestore

// Purpose: FCMTokenManager handles registration and storage of FCM tokens in Firestore.
// Responsibilities: Stores and manages FCM tokens with device metadata for manual notification sending.
// Usage: Call storeFCMToken() from AppDelegate when token is received.
class FCMTokenManager {
    static let shared = FCMTokenManager()
    
    private let COLLECTION_FCM_TOKENS = "fcmTokens"
    
    private init() {}
    
    
    // Purpose: Stores FCM token in Firestore with device metadata.
    // Parameters: token.
    // Returns: Async completion.
    // Notes: Determines Firebase project ID from active Firebase app.
    func storeFCMToken(token: String) async {
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
            
            print("✅ FCM token stored in Firestore (projectId: \(projectId))")
        } catch {
            print("❌ Failed to store FCM token: \(error)")
        }
    }
    
    
    // Purpose: Deletes FCM token from Firestore.
    // Parameters: No parameters.
    // Returns: Async completion.
    // Notes: Called when user unpairs device.
    func deleteFCMToken() async {
        let deviceId = DeviceManager.shared.getDeviceId()
        
        do {
            try await Firestore.firestore()
                .collection(COLLECTION_FCM_TOKENS)
                .document(deviceId)
                .delete()
            
            print("✅ FCM token deleted from Firestore")
        } catch {
            print("❌ Failed to delete FCM token: \(error)")
        }
    }
}
