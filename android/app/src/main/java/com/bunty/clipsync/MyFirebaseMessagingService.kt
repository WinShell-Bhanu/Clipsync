package com.bunty.clipsync

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MyFirebaseMessagingService handles incoming Firebase Cloud Messaging (FCM) push notifications.
 *
 * ClipSync uses FCM for:
 * - Clipboard sync wake-up notifications
 * - Update notifications (new app version available)
 *
 * Registered in AndroidManifest.xml with the `com.google.firebase.MESSAGING_EVENT` intent-filter.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FCMService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when the FCM registration token is refreshed.
     * Stores the token in Firestore with device metadata.
     *
     * @param token The new FCM registration token for this device.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token received")
        serviceScope.launch {
            FCMTokenManager.storeFCMToken(applicationContext, token)
        }
    }

    /**
     * Called when an FCM message is received.
     * Handles both clipboard sync and update notifications.
     *
     * @param remoteMessage The incoming FCM message including its data payload.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data.isNotEmpty()) {
            val messageType = remoteMessage.data["type"]

            when (messageType) {
                "update" -> {
                    // Update notification - show notification and store pending update info
                    val version = remoteMessage.data["version"] ?: "Unknown"
                    val downloadUrl = remoteMessage.data["downloadUrl"] ?: ""
                    val releaseNotes = remoteMessage.data["releaseNotes"] ?: "New update available!"

                    // Save pending update info for dialog
                    UpdateNotificationManager.savePendingUpdate(
                        applicationContext,
                        version,
                        downloadUrl,
                        releaseNotes
                    )

                    // Show notification
                    UpdateNotificationManager.showUpdateNotification(
                        applicationContext,
                        version,
                        releaseNotes
                    )
                }

                else -> {
                    // Legacy clipboard sync notification
                    val clipboardContent = remoteMessage.data["content"]
                    if (!clipboardContent.isNullOrEmpty()) {
                        val isOtp = HelperUtils.isOTP(clipboardContent)
                        NotificationHelper(applicationContext).showClipboardNotification(clipboardContent, isOtp)
                    }
                }
            }
        }
    }
}
