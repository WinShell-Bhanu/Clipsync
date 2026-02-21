package com.bunty.clipsync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * MyFirebaseMessagingService handles incoming Firebase Cloud Messaging (FCM) push notifications.
 *
 * ClipSync uses FCM as a fallback/wake-up mechanism — the actual clipboard data is always
 * stored and retrieved from Firestore, but FCM can be used to wake the app or deliver
 * a notification payload directly.
 *
 * Registered in AndroidManifest.xml with the `com.google.firebase.MESSAGING_EVENT` intent-filter.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    /**
     * Called when the FCM registration token is refreshed.
     *
     * The token is not currently stored anywhere because ClipSync identifies devices by
     * their Firestore pairing document ID rather than by FCM token.
     * If push-initiated sync is added in the future, store this token in Firestore here.
     *
     * @param token The new FCM registration token for this device.
     */
    override fun onNewToken(token: String) {
        // TODO: persist token to Firestore if FCM-triggered sync is implemented
    }

    /**
     * Called when a data-only FCM message is received while the app is in the foreground
     * or in the background (data messages are always delivered).
     *
     * If the message payload contains a `"content"` key the text is treated as clipboard
     * data: it is classified as an OTP or regular clipboard sync, and a notification is shown.
     *
     * @param remoteMessage The incoming FCM message including its data payload.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data.isNotEmpty()) {
            val clipboardContent = remoteMessage.data["content"]
            if (!clipboardContent.isNullOrEmpty()) {
                // Determine whether this content looks like an OTP code
                val isOtp = HelperUtils.isOTP(clipboardContent)
                // Show a notification using the appropriate title/style
                NotificationHelper(applicationContext).showClipboardNotification(clipboardContent, isOtp)
            }
        }
    }
}
