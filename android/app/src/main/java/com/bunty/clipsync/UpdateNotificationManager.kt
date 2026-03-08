package com.bunty.clipsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Singleton that manages two categories of user-facing system notifications for ClipSync:
 *
 * 1. **Update notifications** — shown when an FCM data message with `type = "update"` is
 *    received. Informs the user that a new version is available and provides a direct link
 *    to the GitHub Releases page. The update details are also persisted to SharedPreferences
 *    so the app can surface an in-app dialog the next time it is foregrounded.
 *
 * 2. **Announcement notifications** — shown for any general-purpose push that does not
 *    constitute a version update (e.g. surveys, blog posts, changelogs). The notification
 *    body and action button label/URL are fully driven by the incoming FCM payload, making
 *    this a flexible template for any future broadcast message type.
 *
 * All notifications are posted to the shared `clipsync_updates` channel, which is created
 * with [NotificationManager.IMPORTANCE_HIGH] so it appears as a heads-up notification on
 * Android 8.0+ devices. The channel creation call is idempotent — Android no-ops it if the
 * channel already exists.
 *
 * Expected FCM data payload for an update notification:
 *   type         = "update"
 *   version      = "1.2.0"
 *   downloadUrl  = "https://github.com/WinShell-Bhanu/Clipsync/releases/latest"
 *   releaseNotes = "Bug fixes and performance improvements"
 *
 * Expected FCM data payload for an announcement notification:
 *   type        = "announcement"
 *   title       = "We'd love your feedback! 💬"
 *   body        = "Help us improve ClipSync. Takes only 2 minutes."
 *   actionLabel = "Give Feedback"
 *   actionUrl   = "https://forms.gle/xyz"
 */
object UpdateNotificationManager {

    // ── Notification channel constants ─────────────────────────────────────────
    private const val CHANNEL_ID   = "clipsync_updates"
    private const val CHANNEL_NAME = "App Updates"

    // Update notifications use ID 1001; announcement notifications use 1002 so they can
    // coexist in the notification drawer without one replacing the other.
    private const val NOTIFICATION_ID = 1001

    // ── SharedPreferences keys for pending-update persistence ──────────────────
    private const val PREFS_NAME          = "update_prefs"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_PENDING_URL     = "pending_url"
    private const val KEY_PENDING_NOTES   = "pending_notes"
    private const val KEY_HAS_PENDING     = "has_pending_update"

    // Fallback URL used when the FCM payload omits `downloadUrl` entirely.
    private const val GITHUB_RELEASES_URL =
        "https://github.com/WinShell-Bhanu/Clipsync/releases/latest"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Builds and posts a high-priority update-available notification.
     *
     * The notification presents three interaction points:
     * - **Tapping the notification body** — opens [downloadUrl] (or [GITHUB_RELEASES_URL]
     *   as a fallback) directly in the device's default browser.
     * - **"View on GitHub" action button** — opens the same resolved URL; uses a distinct
     *   PendingIntent request code (1) so Android treats it as a separate intent.
     * - **"Later" action button** — fires an empty broadcast that produces no side-effect;
     *   the notification is auto-cancelled when the user taps any part of it.
     *
     * The expanded (BigText) style shows up to 150 characters of [releaseNotes] so the
     * user can quickly decide whether to update without leaving the notification shade.
     *
     * @param context      Application context required to build intents and post the notification.
     * @param version      Human-readable new version string, e.g. `"1.2.0"`.
     * @param releaseNotes Short summary of what changed; truncated to 150 chars in the UI.
     * @param downloadUrl  Direct URL to open on tap; falls back to [GITHUB_RELEASES_URL] if blank.
     */
    fun showUpdateNotification(
        context: Context,
        version: String,
        releaseNotes: String,
        downloadUrl: String = GITHUB_RELEASES_URL
    ) {
        createNotificationChannel(context)

        // Guard against an empty downloadUrl sent from the FCM console by substituting
        // the known GitHub releases page as a safe, always-valid fallback.
        val resolvedUrl = downloadUrl.ifBlank { GITHUB_RELEASES_URL }

        // Tapping the notification body launches the browser at the resolved URL.
        // FLAG_ACTIVITY_NEW_TASK is required when starting an Activity from a non-Activity context.
        val openUrlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 0, openUrlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The "View on GitHub" action button reuses the same browser intent but must use a
        // different request code (1) so the PendingIntent is not collapsed with the body tap.
        val githubActionPendingIntent = PendingIntent.getActivity(
            context, 1, openUrlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The "Later" button fires a no-op broadcast. Because setAutoCancel(true) is set on
        // the notification, tapping any part of it (including this button) dismisses it.
        val laterPendingIntent = PendingIntent.getBroadcast(
            context, 2, Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ClipSync Update Available 🚀")
            .setContentText("Version $version is ready to download!")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Version $version is ready!\n\n${releaseNotes.take(150)}")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "View on GitHub",
                githubActionPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Later",
                laterPendingIntent
            )
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Builds and posts a general-purpose announcement notification with a fully customisable
     * title, body, and action button.
     *
     * Use this for any message that isn't a version update — user surveys, blog posts,
     * feature announcements, promotional content, etc. The content is entirely driven by
     * the incoming FCM data payload so no code change is needed to alter the wording.
     *
     * Interaction model:
     * - **Tapping the notification body** — opens [actionUrl] in the browser if it is
     *   non-empty; otherwise launches [MainActivity] so the user lands inside the app.
     * - **[actionLabel] action button** — opens the same destination as the body tap.
     * - **"Dismiss" action button** — fires a no-op broadcast that closes the notification.
     *
     * This notification is posted with ID [NOTIFICATION_ID] + 1 (1002) so it does not
     * overwrite a simultaneously visible update notification (ID 1001).
     *
     * @param context     Application context required to build intents and post the notification.
     * @param title       Bold heading text displayed at the top of the notification.
     * @param body        Main body text; also shown in BigText expanded style for longer messages.
     * @param actionLabel Text label for the primary action button, e.g. `"Give Feedback"`.
     * @param actionUrl   URL to open when the body or action button is tapped. Pass an empty
     *                    string to open [MainActivity] instead of a browser URL.
     */
    fun showAnnouncementNotification(
        context: Context,
        title: String,
        body: String,
        actionLabel: String = "Open",
        actionUrl: String = ""
    ) {
        createNotificationChannel(context)

        // Decide what to open on tap: an external URL when one is provided, or the app
        // itself when the payload did not include a URL (e.g. a plain informational push).
        val openIntent = if (actionUrl.isNotBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            // No URL in the payload — bring the user into the app instead.
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        // Request code 10 is reserved for the announcement body-tap intent, separate from
        // the update notification's codes (0–2) to prevent accidental PendingIntent reuse.
        val contentPendingIntent = PendingIntent.getActivity(
            context, 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The custom action button (e.g. "Give Feedback") shares the same destination intent
        // but must use a distinct request code (11) to remain a separate PendingIntent.
        val actionPendingIntent = PendingIntent.getActivity(
            context, 11, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Dismiss" fires an empty broadcast with no side-effects; setAutoCancel ensures
        // the notification is removed from the drawer as soon as the user taps it.
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, 12, Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                actionLabel,
                actionPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                dismissPendingIntent
            )
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Offset the notification ID by 1 so this coexists with any active update notification.
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    /**
     * Ensures the `clipsync_updates` notification channel exists on Android 8.0 (API 26) and
     * above. On older API levels the call is a no-op because notification channels do not exist.
     *
     * The channel is created with [NotificationManager.IMPORTANCE_HIGH], which produces
     * heads-up (peek) notifications and plays a sound — appropriate for update and announcement
     * alerts that the user should notice even when using the device actively.
     *
     * This method is safe to call multiple times; the system ignores the request if a channel
     * with the same [CHANNEL_ID] has already been registered.
     *
     * @param context Application context used to obtain the [NotificationManager] system service.
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
     * Persists an incoming update's metadata to SharedPreferences so the app can surface
     * an in-app update dialog the next time [MainActivity] is resumed, even if the user
     * dismissed or never tapped the system notification.
     *
     * The presence of a pending update is signalled by [KEY_HAS_PENDING] = `true`. Call
     * [getPendingUpdate] to read it back and [clearPendingUpdate] once the user has acted.
     *
     * @param context      Application context for SharedPreferences access.
     * @param version      Version string of the available update, e.g. `"1.2.0"`.
     * @param downloadUrl  URL pointing to the release asset or GitHub release page.
     * @param releaseNotes Human-readable summary of changes included in the update.
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
     * Reads and returns any pending update information previously saved by [savePendingUpdate].
     *
     * Returns `null` in two situations:
     * - [KEY_HAS_PENDING] is `false`, meaning no update has been stored since the last clear.
     * - Either the version or URL string is missing/null in SharedPreferences (corrupted state).
     *
     * The release notes field falls back to a generic `"New update available!"` string if the
     * stored value is absent, ensuring callers always receive a displayable notes string.
     *
     * @param context Application context for SharedPreferences access.
     * @return A populated [UpdateInfo] instance, or `null` if no pending update is stored.
     */
    fun getPendingUpdate(context: Context): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPending = prefs.getBoolean(KEY_HAS_PENDING, false)

        // Exit early if no update was saved — avoids unnecessary string lookups.
        if (!hasPending) return null

        val version = prefs.getString(KEY_PENDING_VERSION, null) ?: return null
        val url = prefs.getString(KEY_PENDING_URL, null) ?: return null
        val notes = prefs.getString(KEY_PENDING_NOTES, "New update available!") ?: "New update available!"

        return UpdateInfo(version, url, notes)
    }

    /**
     * Removes all pending-update fields from SharedPreferences and resets [KEY_HAS_PENDING]
     * to `false`, effectively marking the update as handled.
     *
     * Should be called after the user has either initiated the download or explicitly
     * dismissed the in-app update dialog to prevent the dialog from re-appearing on
     * subsequent app launches.
     *
     * @param context Application context for SharedPreferences access.
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
     * Immutable value object that bundles the three pieces of information describing an
     * available app update. Returned by [getPendingUpdate] and passed to UI components
     * that need to render an update dialog or notification.
     *
     * @property version      Semantic version string of the new release, e.g. `"1.2.0"`.
     * @property downloadUrl  URL the user should be directed to in order to obtain the update.
     * @property releaseNotes Human-readable description of what is new or fixed in this release.
     */
    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )
}
