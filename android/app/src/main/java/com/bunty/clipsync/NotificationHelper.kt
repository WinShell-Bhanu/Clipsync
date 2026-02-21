package com.bunty.clipsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

/**
 * NotificationHelper centralises all notification creation and display logic for ClipSync.
 *
 * It manages two distinct notification channels:
 * - **Clipboard channel** (`CHANNEL_CLIPBOARD`): High-importance channel for incoming OTP /
 *   clipboard content notifications. These appear as heads-up banners.
 * - **Service channel** (`CHANNEL_SERVICE`): Low-importance channel used by persistent
 *   foreground-service notifications so the system doesn't kill the sync service.
 *
 * Both channels are created in [init] so they are always ready before any notification is posted.
 *
 * @param context Application context used to access the [NotificationManager].
 */
class NotificationHelper(private val context: Context) {

    companion object {
        // Channel IDs — must match what's declared in the AndroidManifest if referenced there
        const val CHANNEL_CLIPBOARD = "clipboard_channel"
        const val CHANNEL_SERVICE   = "service_channel"

        // Stable notification IDs — reusing the same ID updates an existing notification
        const val NOTIFICATION_ID_CLIPBOARD = 1001
        const val NOTIFICATION_ID_SERVICE   = 1002
    }

    init {
        // Create both channels as soon as an instance is constructed
        createNotificationChannels()
    }

    /**
     * Registers the clipboard and service notification channels with the OS.
     *
     * Channels are only created on API 26+ (Oreo); on older versions this is a no-op.
     * Calling this repeatedly is safe — Android ignores duplicate channel registrations.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // High-importance channel: shows heads-up banners for clipboard/OTP events
            val clipboardChannel = NotificationChannel(
                CHANNEL_CLIPBOARD,
                "Clipboard Sync",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming clipboard content"
            }

            // Low-importance channel: silent persistent notification for the background service
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for background service"
            }

            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannels(listOf(clipboardChannel, serviceChannel))
        }
    }

    /**
     * Posts a notification for incoming clipboard content or an OTP code.
     *
     * The notification title and body adapt based on [isOtp]:
     * - OTP:       Title = "OTP Detected",    body = the raw OTP code.
     * - Clipboard: Title = "Clipboard Synced", body = "New content received from Mac".
     *
     * Tapping the notification opens [MainActivity].
     *
     * Does nothing if the POST_NOTIFICATIONS permission has not been granted (required on API 33+).
     *
     * @param content The clipboard text or OTP code to display.
     * @param isOtp   `true` if [content] is an OTP code; `false` for general clipboard content.
     */
    fun showClipboardNotification(content: String, isOtp: Boolean = false) {
        // Guard: POST_NOTIFICATIONS permission must be granted on Android 13+
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Tapping the notification brings the user back into the app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Adapt title/body based on whether this is an OTP or a regular clipboard sync
        val title   = if (isOtp) "OTP Detected"    else "Clipboard Synced"
        val message = if (isOtp) content            else "New content received from Mac"

        val builder = NotificationCompat.Builder(context, CHANNEL_CLIPBOARD)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // heads-up on older APIs
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)   // dismiss the notification when the user taps it

        with(NotificationManagerCompat.from(context)) {
            notify(NOTIFICATION_ID_CLIPBOARD, builder.build())
        }
    }
}
