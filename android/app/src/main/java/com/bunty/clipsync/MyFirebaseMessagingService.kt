package com.bunty.clipsync

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * FCM message receiver that processes all push notifications delivered to the ClipSync Android app.
 *
 * ClipSync sends FCM *data* messages — rather than notification messages — so that every payload
 * is guaranteed to arrive at this service regardless of whether the app is foregrounded,
 * backgrounded, or completely killed by the OS. Three message types are dispatched based on the
 * `type` key present in each message's data map:
 *
 * ┌──────────────────┬────────────────────────────────────────────────────────────────────────┐
 * │ type             │ Expected data-map keys                                                 │
 * ├──────────────────┼────────────────────────────────────────────────────────────────────────┤
 * │ "update"         │ version, downloadUrl, releaseNotes                                     │
 * │ "announcement"   │ title (required), body (required), actionLabel, actionUrl              │
 * │ <anything else>  │ content  (legacy clipboard-sync wake-up path)                          │
 * └──────────────────┴────────────────────────────────────────────────────────────────────────┘
 *
 * [serviceScope] is a coroutine scope backed by [SupervisorJob] and [Dispatchers.IO]. Using
 * [SupervisorJob] ensures that a failure in one child coroutine does not cascade and cancel
 * unrelated work. The scope is cancelled in [onDestroy] to prevent coroutine leaks when the
 * OS tears down the service between message deliveries.
 *
 * This class must be declared in AndroidManifest.xml with an intent-filter for the action
 * `com.google.firebase.MESSAGING_EVENT`.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FCMService"

    /**
     * IO-dispatched coroutine scope used for asynchronous work such as persisting the FCM token
     * to Firestore. [SupervisorJob] ensures a child failure does not cancel sibling coroutines.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called by the FCM SDK whenever the registration token for this app installation is
     * created or rotated.
     *
     * Token rotation occurs when the user restores a backup onto a new device, reinstalls the
     * app, or when the FCM infrastructure invalidates older tokens. The refreshed token is
     * persisted to Firestore alongside device metadata so the server can continue addressing
     * push messages to this specific installation. The write is performed on [serviceScope]
     * to keep the main thread unblocked.
     *
     * @param token The new FCM registration token assigned to this app installation.
     */
    override fun onNewToken(token: String) {
        serviceScope.launch {
            FCMTokenManager.storeFCMToken(applicationContext, token)
        }
    }

    /**
     * Called by the FCM SDK when a data message is received by this device.
     *
     * Messages with an empty data payload are ignored. For non-empty payloads the `type` key
     * acts as a discriminator and routes execution to one of three handling branches:
     *
     *  - **"update"** — A new version of the app is available. Update metadata (version string,
     *    GitHub download URL, release notes) is persisted via [UpdateNotificationManager.savePendingUpdate]
     *    so [MainActivity] can show a download dialog on next launch, and an immediate status-bar
     *    notification with a "View on GitHub" action is posted via
     *    [UpdateNotificationManager.showUpdateNotification].
     *
     *  - **"announcement"** — A general broadcast (e.g. a feedback survey, a blog post, or a
     *    product update). Both `title` and `body` are required; messages without a body are
     *    silently dropped to avoid blank notifications. An optional `actionLabel`/`actionUrl`
     *    pair drives a tappable action button in the posted notification.
     *
     *  - **anything else** — Treated as the legacy clipboard-sync wake-up path. If the `content`
     *    key is non-empty, [HelperUtils.isOTP] determines whether to surface it as an OTP alert
     *    or a generic clipboard notification via [NotificationHelper].
     *
     * @param remoteMessage The incoming FCM message containing the data map and any attached
     *                      notification metadata from the sender.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data.isNotEmpty()) {
            val messageType = remoteMessage.data["type"]

            when (messageType) {
                "update" -> {
                    // Extract update metadata with safe fallbacks for absent keys.
                    val version = remoteMessage.data["version"] ?: "Unknown"
                    val downloadUrl = remoteMessage.data["downloadUrl"] ?: ""
                    val releaseNotes = remoteMessage.data["releaseNotes"] ?: "New update available!"

                    // Persist update details so MainActivity can present a download dialog even
                    // if the user dismissed or never saw the notification.
                    UpdateNotificationManager.savePendingUpdate(
                        applicationContext,
                        version,
                        downloadUrl,
                        releaseNotes
                    )

                    // Post an immediate status-bar notification so the user is informed right away.
                    UpdateNotificationManager.showUpdateNotification(
                        applicationContext,
                        version,
                        releaseNotes,
                        downloadUrl
                    )
                }

                "announcement" -> {
                    // Read announcement fields; title and body are mandatory, action fields are optional.
                    val title       = remoteMessage.data["title"]       ?: "ClipSync"
                    val body        = remoteMessage.data["body"]        ?: ""
                    val actionLabel = remoteMessage.data["actionLabel"] ?: "Open"
                    val actionUrl   = remoteMessage.data["actionUrl"]   ?: ""

                    // Guard against an empty body to prevent a content-free notification appearing
                    // in the shade, which would confuse or annoy the user.
                    if (body.isNotEmpty()) {
                        UpdateNotificationManager.showAnnouncementNotification(
                            applicationContext,
                            title,
                            body,
                            actionLabel,
                            actionUrl
                        )
                    }
                }

                "wake_up" -> {
                    if (!DeviceManager.isSyncFromMacEnabled(applicationContext)) {
                        return
                    }
                    // Fetch latest from Firestore and copy it via ClipboardGhostActivity
                    FirestoreManager.fetchLatestClipboard(applicationContext) { decryptedContent ->
                        // Dispatch to GhostActivity to copy to local clipboard
                        val intent = Intent(applicationContext, ClipboardGhostActivity::class.java).apply {
                            action = ClipboardGhostActivity.ACTION_WRITE
                            putExtra(ClipboardGhostActivity.EXTRA_CLIP_TEXT, decryptedContent)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        applicationContext.startActivity(intent)
                        
                        // Show notification if it's OTP
                        val isOtp = HelperUtils.isOTP(decryptedContent)
                        if (isOtp) {
                            NotificationHelper(applicationContext).showClipboardNotification(decryptedContent, isOtp)
                        }

                        // Try to re-establish BLE connection since we just got a wake up
                        LocalSyncManager.startDiscovery(applicationContext)
                    }
                }

                else -> {
                    // Legacy clipboard-sync path: the payload carries clipboard text in "content".
                    // OTP detection determines the appropriate notification title and presentation.
                    val clipboardContent = remoteMessage.data["content"]
                    if (!clipboardContent.isNullOrEmpty()) {
                        val isOtp = HelperUtils.isOTP(clipboardContent)
                        NotificationHelper(applicationContext).showClipboardNotification(clipboardContent, isOtp)
                    }
                }
            }
        }
    }

    /**
     * Called when the OS is about to destroy this service.
     * Cancels [serviceScope] to release all in-flight coroutines and prevent memory leaks.
     */
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
