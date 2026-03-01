package com.bunty.clipsync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * FCMTokenManager handles registration and storage of FCM tokens in Firestore.
 *
 * Tokens are stored in the `fcmTokens` collection with device metadata:
 * - platform: "android"
 * - projectId: "clipsyncind" or "clipsync1-c3c3c" (detected from build config)
 * - userId: Optional user identifier
 * - deviceName: Human-readable device name
 * - appVersion: Current app version
 * - lastUpdated: Timestamp
 */
object FCMTokenManager {

    private const val TAG = "FCMTokenManager"
    private const val COLLECTION_FCM_TOKENS = "fcmTokens"

    /**
     * Requests FCM token and stores it in Firestore.
     * Called from MainActivity on app launch.
     */
    suspend fun registerFCMToken(context: Context) {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d(TAG, "FCM token retrieved")
            storeFCMToken(context, token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve FCM token", e)
        }
    }

    /**
     * Stores FCM token in Firestore with device metadata.
     *
     * @param context Application context
     * @param token FCM registration token
     */
    suspend fun storeFCMToken(context: Context, token: String) {
        try {
            val deviceId = DeviceManager.getDeviceId(context)
            val deviceName = DeviceManager.getAndroidDeviceName()
            val targetRegion = DeviceManager.getTargetRegion(context)
            
            // Map region to project ID
            val projectId = when (targetRegion) {
                "US" -> "clipsync1-c3c3c"
                else -> "clipsyncind"
            }

            val tokenData = hashMapOf(
                "token" to token,
                "platform" to "android",
                "projectId" to projectId,
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "appVersion" to "1.0.0", // TODO: Read from BuildConfig
                "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            // Store in Firestore
            val db = FirestoreManager.getDb(context)
            db.collection(COLLECTION_FCM_TOKENS)
                .document(deviceId)
                .set(tokenData, SetOptions.merge())
                .await()

            Log.d(TAG, "FCM token stored in Firestore (projectId: $projectId)")

            // Subscribe to topic so console can target all devices at once
            FirebaseMessaging.getInstance().subscribeToTopic("all_devices")
                .addOnSuccessListener { Log.d(TAG, "Subscribed to all_devices topic") }
                .addOnFailureListener { Log.e(TAG, "Failed to subscribe to topic", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store FCM token", e)
        }
    }

    /**
     * Deletes FCM token from Firestore.
     * Called when user logs out or unpairs device.
     */
    suspend fun deleteFCMToken(context: Context) {
        try {
            val deviceId = DeviceManager.getDeviceId(context)
            val db = FirestoreManager.getDb(context)
            
            db.collection(COLLECTION_FCM_TOKENS)
                .document(deviceId)
                .delete()
                .await()

            Log.d(TAG, "FCM token deleted from Firestore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete FCM token", e)
        }
    }
}
