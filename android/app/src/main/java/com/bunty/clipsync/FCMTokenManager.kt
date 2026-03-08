package com.bunty.clipsync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Singleton responsible for the full lifecycle of an FCM (Firebase Cloud Messaging) token
 * on this Android device — from initial retrieval through Firestore persistence to deletion.
 *
 * Each device is identified by a stable [DeviceManager.getDeviceId] key. The corresponding
 * Firestore document in the `fcmTokens` collection carries enough metadata for the backend
 * (or Firebase Console) to target specific platforms, regions, or devices when sending pushes:
 *
 *   Field         | Example value
 *   --------------|-----------------------------
 *   token         | "dK3f…"  (the raw FCM token)
 *   platform      | "android"
 *   projectId     | "clipsyncind" | "clipsync1-c3c3c"
 *   deviceId      | stable unique device identifier
 *   deviceName    | "Pixel 7 Pro"
 *   appVersion    | "1.0.0"
 *   lastUpdated   | Firestore server timestamp
 *
 * Region-to-project mapping:
 *   US region  → Firebase project "clipsync1-c3c3c"
 *   All others → Firebase project "clipsyncind"
 */
object FCMTokenManager {

    // ── Logging / Firestore identifiers ────────────────────────────────────────
    private const val TAG = "FCMTokenManager"
    private const val COLLECTION_FCM_TOKENS = "fcmTokens"

    /**
     * Entry-point for FCM token registration, called once on every app launch from
     * [MainActivity]. Fetches the current FCM registration token asynchronously and
     * hands it off to [storeFCMToken] for Firestore persistence.
     *
     * The token is issued by Google Play Services and may rotate periodically; calling
     * this on every launch ensures the stored token is always fresh.
     *
     * @param context Application context, forwarded to [storeFCMToken] for device resolution.
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
     * Persists the given FCM token and associated device metadata to Firestore, then
     * subscribes the device to the `all_devices` FCM topic so broadcast messages from
     * the Firebase Console reach every registered device simultaneously.
     *
     * The document is written with [SetOptions.merge] so that any extra fields stored
     * by other parts of the app are not overwritten — only the fields listed below are
     * touched on each call.
     *
     * @param context Application context used to resolve the device ID, name, and region.
     * @param token   The raw FCM registration token string returned by Firebase Messaging.
     */
    suspend fun storeFCMToken(context: Context, token: String) {
        try {
            val deviceId = DeviceManager.getDeviceId(context)
            val deviceName = DeviceManager.getAndroidDeviceName()
            val targetRegion = DeviceManager.getTargetRegion(context)

            // Each region maps to a separate Firebase project; the correct projectId must be
            // stored alongside the token so cross-project messaging logic can route correctly.
            val projectId = when (targetRegion) {
                "US" -> "clipsync1-c3c3c"
                else -> "clipsyncind"
            }

            // Assemble the token document. serverTimestamp() is used for lastUpdated so the
            // value reflects Firebase server time, which is consistent across all clients.
            val tokenData = hashMapOf(
                "token" to token,
                "platform" to "android",
                "projectId" to projectId,
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "appVersion" to "1.0.0",
                "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            // Use the stable deviceId as the document key so repeated calls simply update
            // the existing record rather than creating duplicate token entries.
            val db = FirestoreManager.getDb(context)
            db.collection(COLLECTION_FCM_TOKENS)
                .document(deviceId)
                .set(tokenData, SetOptions.merge())
                .await()

            Log.d(TAG, "FCM token stored in Firestore (projectId: $projectId)")

            // Subscribing to "all_devices" enables sending a single FCM message from the
            // Firebase Console that targets every device regardless of deviceId or region.
            FirebaseMessaging.getInstance().subscribeToTopic("all_devices")
                .addOnSuccessListener { Log.d(TAG, "Subscribed to all_devices topic") }
                .addOnFailureListener { Log.e(TAG, "Failed to subscribe to topic", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store FCM token", e)
        }
    }

    /**
     * Removes this device's FCM token document from the `fcmTokens` Firestore collection.
     *
     * Should be called when the user logs out or explicitly unpairs the device so the
     * backend can no longer push notifications to it. After this call, [registerFCMToken]
     * must be invoked again (e.g. on the next sign-in) to re-enable push delivery.
     *
     * @param context Application context used to retrieve the stable device identifier.
     */
    suspend fun deleteFCMToken(context: Context) {
        try {
            val deviceId = DeviceManager.getDeviceId(context)
            val db = FirestoreManager.getDb(context)

            // Delete the entire token document; the device ID is the document path so this
            // is a targeted, single-document operation with no risk of collateral deletions.
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
