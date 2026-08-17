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
 * Centralises all notification creation and display logic for the ClipSync application.
 *
 * Two distinct notification channels are managed here to separate concerns by importance level:
 *
 *  - [CHANNEL_CLIPBOARD]: High-importance channel used for real-time clipboard and OTP events.
 *    Notifications on this channel appear as floating heads-up banners so the user immediately
 *    sees an incoming OTP or synced clipboard entry without pulling down the shade.
 *
 *  - [CHANNEL_SERVICE]: Low-importance channel used exclusively by the persistent foreground
 *    service. Low importance suppresses sound and vibration, keeping the always-on service
 *    notification unobtrusive while still satisfying Android's foreground-service requirement.
 *
 * Both channels are registered with the OS as soon as a [NotificationHelper] instance is
 * constructed, guaranteeing they exist before any notification is posted. Android silently
 * ignores duplicate channel registrations, so constructing multiple instances is safe.
 *
 * @param context The application context used to obtain the [NotificationManager] and build intents.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        /**
         * Unique string identifier for the clipboard / OTP notification channel.
         * This value must remain stable across app releases because the OS persists it.
         */
        const val CHANNEL_CLIPBOARD = "clipboard_channel"

        /**
         * Unique string identifier for the background-service notification channel.
         * This value must remain stable across app releases because the OS persists it.
         */
        const val CHANNEL_SERVICE   = "service_channel"

        /**
         * Fixed notification ID for clipboard and OTP alerts. Re-using the same ID causes
         * Android to update the existing notification in place rather than stacking duplicates.
         */
        const val NOTIFICATION_ID_CLIPBOARD = 1001

        /**
         * Fixed notification ID for the persistent foreground-service notification.
         * Must differ from [NOTIFICATION_ID_CLIPBOARD] so both can coexist simultaneously.
         */
        const val NOTIFICATION_ID_SERVICE   = 1002
    }

    init {
        // Register both channels immediately so they are ready before the first notification post.
        createNotificationChannels()
    }

    /**
     * Registers the clipboard and service notification channels with the Android OS.
     *
     * Channel creation is gated behind an API 26 (Oreo) version check because the
     * [NotificationChannel] class does not exist on earlier API levels; on those devices
     * notifications work without channels and this method becomes a no-op.
     *
     * The clipboard channel uses [NotificationManager.IMPORTANCE_HIGH] so incoming OTP
     * notifications surface as floating heads-up banners over the active foreground activity.
     * The service channel uses [NotificationManager.IMPORTANCE_LOW] to produce no sound or
     * vibration for the mandatory background-service notification.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // High-importance channel: floating heads-up banners ensure the user immediately
            // notices an incoming OTP or synced clipboard item without opening the shade.
            val clipboardChannel = NotificationChannel(
                CHANNEL_CLIPBOARD,
                "Clipboard Sync",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming clipboard content"
            }

            // Low-importance channel: keeps the mandatory foreground-service notification
            // silent and unobtrusive — no sound, no vibration, just a status-bar icon.
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
     * Builds and posts a notification for an incoming clipboard sync event or a detected OTP code.
     *
     * Notification content adapts based on the [isOtp] flag:
     *  - [isOtp] = `true`  → title "OTP Detected",    body shows the raw OTP string so the user
     *    can read it at a glance from the shade or lock screen.
     *  - [isOtp] = `false` → title "Clipboard Synced", body reads "New content received from Mac"
     *    to keep the actual clipboard text private on the lock screen.
     *
     * Tapping the notification launches [MainActivity] and clears the activity back stack, landing
     * the user at a clean root state rather than returning them to a previous screen.
     *
     * On Android 13 (API 33) and above the [Manifest.permission.POST_NOTIFICATIONS] runtime
     * permission must be explicitly granted by the user. If it has not been granted this method
     * exits early without posting, because calling [notify] without the permission throws a
     * [SecurityException].
     *
     * @param content The clipboard text or OTP string to display in the notification body.
     * @param isOtp   `true` when [content] is a one-time password; `false` for general clipboard
     *                content. Defaults to `false`.
     */
    fun showClipboardNotification(content: String, isOtp: Boolean = false) {
        // Android 13+ enforces a runtime notification permission. Exit early if it is absent
        // to avoid a SecurityException when notify() is called without the required permission.
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Build a PendingIntent that reopens MainActivity on tap. FLAG_ACTIVITY_CLEAR_TASK
        // resets the activity back stack so the user always lands at a clean root state.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Select the notification title and body based on whether this is an OTP or a
        // generic clipboard sync event.
        val title   = if (isOtp) "OTP Detected"    else "Clipboard Synced"
        val message = if (isOtp) content            else "New content received from Mac"

        val builder = NotificationCompat.Builder(context, CHANNEL_CLIPBOARD)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // Triggers heads-up display on pre-Oreo devices.
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)   // Dismiss the notification automatically once the user taps it.

        with(NotificationManagerCompat.from(context)) {
            notify(NOTIFICATION_ID_CLIPBOARD, builder.build())
        }
    }
}
