package com.bunty.clipsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * UpdateNotificationManager handles update notifications and pending update storage.
 */
object UpdateNotificationManager {

    private const val CHANNEL_ID = "clipsync_updates"
    private const val CHANNEL_NAME = "App Updates"
    private const val NOTIFICATION_ID = 1001

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_PENDING_URL = "pending_url"
    private const val KEY_PENDING_NOTES = "pending_notes"
    private const val KEY_HAS_PENDING = "has_pending_update"

    /**
     * Shows an update notification when a new version is available.
     */
    fun showUpdateNotification(context: Context, version: String, releaseNotes: String) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_update_dialog", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ClipSync Update Available 🚀")
            .setContentText("Version $version is now available!")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Version $version is now available!\n\n${releaseNotes.take(100)}")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Creates notification channel for update notifications (Android 8.0+).
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for app updates"
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Saves pending update information for later display.
     */
    fun savePendingUpdate(context: Context, version: String, downloadUrl: String, releaseNotes: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_PENDING_VERSION, version)
            putString(KEY_PENDING_URL, downloadUrl)
            putString(KEY_PENDING_NOTES, releaseNotes)
            putBoolean(KEY_HAS_PENDING, true)
            apply()
        }
    }

    /**
     * Returns pending update info if available.
     */
    fun getPendingUpdate(context: Context): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPending = prefs.getBoolean(KEY_HAS_PENDING, false)

        if (!hasPending) return null

        val version = prefs.getString(KEY_PENDING_VERSION, null) ?: return null
        val url = prefs.getString(KEY_PENDING_URL, null) ?: return null
        val notes = prefs.getString(KEY_PENDING_NOTES, "New update available!") ?: "New update available!"

        return UpdateInfo(version, url, notes)
    }

    /**
     * Clears pending update information.
     */
    fun clearPendingUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            remove(KEY_PENDING_VERSION)
            remove(KEY_PENDING_URL)
            remove(KEY_PENDING_NOTES)
            putBoolean(KEY_HAS_PENDING, false)
            apply()
        }
    }

    /**
     * Data class for update information.
     */
    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )
}
