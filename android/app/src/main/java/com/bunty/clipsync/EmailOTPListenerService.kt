package com.bunty.clipsync

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * EmailOTPListenerService is a [NotificationListenerService] that watches notifications
 * from known email apps and automatically extracts OTP codes from them.
 *
 * **Why this exists:**
 * Many services send OTPs via email rather than SMS. By listening to email-app notifications
 * (Gmail, Outlook, etc.) ClipSync can detect and sync these OTPs to the Mac without
 * requiring the user to open the email manually.
 *
 * **How it works:**
 * 1. Receives `onNotificationPosted` callbacks for every notification posted on the device.
 * 2. Filters to the [EMAIL_APP_PACKAGES] allow-list.
 * 3. Concatenates all text extras (title, body, big text, sub-text) into a single string.
 * 4. Checks for OTP keywords (e.g. "verification code", "one time password").
 * 5. Extracts a 4–8 digit code and syncs it to the Mac via [OTPNotificationService].
 *
 * A 2-second debounce ([MIN_PROCESSING_INTERVAL]) and a deduplication check against
 * [lastProcessedOTP] prevent the same OTP being processed twice.
 *
 * Requires the **Notification Listener** permission granted in Android Settings.
 * Declared in AndroidManifest.xml with the `BIND_NOTIFICATION_LISTENER_SERVICE` permission.
 */
class EmailOTPListenerService : NotificationListenerService() {

    /** Handler used to post Toast messages back to the main thread from the service. */
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "EmailOTPListener"

        // Debounce: skip notifications that arrive within 2 seconds of each other
        private var lastProcessedTime = 0L
        private var lastProcessedOTP  = ""
        private const val MIN_PROCESSING_INTERVAL = 2000L

        /**
         * Package names of email apps whose notifications are monitored for OTP codes.
         * Add new email apps here if users report missing OTP detection.
         */
        private val EMAIL_APP_PACKAGES = setOf(
            "com.google.android.gm",               // Gmail
            "com.microsoft.office.outlook",         // Outlook
            "com.yahoo.mobile.client.android.mail", // Yahoo Mail
            "com.samsung.android.email.provider",   // Samsung Email
            "ch.protonmail.android",                // ProtonMail
            "me.bluemail.mail",                     // BlueMail
            "com.android.email",                    // AOSP Email
            "com.fsck.k9",                          // K-9 Mail
            "com.apple.android.mail"                // (future)
        )

        /**
         * Keywords that indicate a notification likely contains an OTP.
         * Checked case-insensitively against the concatenated notification text.
         */
        private val OTP_KEYWORDS = listOf(
            "otp",
            "verification code",
            "one time password",
            "one time verification",
            "one-time password",
            "one-time verification",
            "security code",
            "code",
            "two factor authentication"
        )
    }

    /**
     * Called by the system each time a notification is posted anywhere on the device.
     *
     * Guards:
     * - Ignores `null` notifications.
     * - Only processes notifications from [EMAIL_APP_PACKAGES].
     * - Applies a 2-second debounce.
     * - Skips OTPs that match the last processed code (deduplication).
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Only process known email app packages
        if (!EMAIL_APP_PACKAGES.contains(sbn.packageName)) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) return

        try {
            val extras = sbn.notification?.extras ?: return

            // Combine all text fields into one string for keyword + OTP scanning
            val title   = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()    ?: ""
            val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()     ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

            val fullContent = "$title $text $bigText $subText"
            if (fullContent.isBlank()) return

            if (containsOTPKeyword(fullContent)) {
                val otpCode = extractOTP(fullContent)

                // Only act if we found a new OTP that differs from the last one we processed
                if (otpCode != null && otpCode != lastProcessedOTP) {
                    lastProcessedTime = currentTime
                    lastProcessedOTP  = otpCode

                    // Copy to clipboard via the ghost activity so it's immediately pasteable
                    ClipboardGhostActivity.copyToClipboard(this, otpCode)
                    // Push the OTP to the Mac via Firestore
                    OTPNotificationService.notifyOTPDetected(this, otpCode)

                    // Toast must run on the main thread
                    mainHandler.post {
                        Toast.makeText(
                            this@EmailOTPListenerService,
                            "Email OTP Copied: $otpCode",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing email notification", e)
        }
    }

    /** Called when a notification is removed. Not currently used. */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    /**
     * Cleans up the handler and resets deduplication state when the service is destroyed
     * (e.g. when the user revokes Notification Listener permission).
     */
    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        lastProcessedTime = 0L
        lastProcessedOTP  = ""
    }

    /** Called when the service successfully binds to the notification system. */
    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    /**
     * Called when the service is unbound (e.g. permission revoked or device rebooted).
     * Clears any pending handler callbacks to avoid memory leaks.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Maps a package name to a human-readable email app name for use in log messages.
     *
     * @param packageName The package name of the email app.
     * @return A friendly display name, or the raw package name as a fallback.
     */
    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.google.android.gm"               -> "Gmail"
            "com.microsoft.office.outlook"         -> "Outlook"
            "com.yahoo.mobile.client.android.mail" -> "Yahoo Mail"
            "com.samsung.android.email.provider"   -> "Samsung Email"
            "ch.protonmail.android"                -> "ProtonMail"
            "me.bluemail.mail"                     -> "BlueMail"
            "com.android.email"                    -> "Email"
            "com.fsck.k9"                          -> "K-9 Mail"
            else                                   -> packageName
        }
    }

    /**
     * Returns `true` if [content] contains at least one [OTP_KEYWORDS] entry.
     * Comparison is case-insensitive.
     */
    private fun containsOTPKeyword(content: String): Boolean {
        val lower = content.lowercase()
        return OTP_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    /**
     * Attempts to extract a numeric OTP code from [content] using two regex patterns:
     *
     * - **Pattern 1**: Standalone 4–8 digit sequence (`\b(\d{4,8})\b`).
     * - **Pattern 2**: Split-format 3–4 digit groups separated by space or dash.
     *
     * Each Pattern 1 candidate is validated by [isValidOTP] before being returned.
     *
     * @return The first valid OTP string found, or `null`.
     */
    private fun extractOTP(content: String): String? {
        // Pattern 1: plain 4–8 digit number
        val pattern1 = Regex("""\b(\d{4,8})\b""")
        // Pattern 2: split-format like "123 456" or "123-456"
        val pattern2 = Regex("""(\d{3,4})[\s\-](\d{3,4})""")

        for (match in pattern1.findAll(content)) {
            val code = match.groupValues[1]
            if (isValidOTP(code, content, match.range)) return code
        }

        // Fallback: try the split-format pattern
        val match2 = pattern2.find(content)
        if (match2 != null) {
            val combined = match2.groupValues[1] + match2.groupValues[2]
            if (combined.length in 4..8) return combined
        }

        return null
    }

    /**
     * Validates that [code] is a genuine OTP and not part of a longer number.
     *
     * Checks:
     * - Length is 4–8 digits.
     * - Characters immediately surrounding the code are not digits (no longer number).
     * - Surrounding characters are whitespace, punctuation, or letters.
     *
     * @param code        Digit string to validate.
     * @param fullContent Original notification text (used for boundary checks).
     * @param range       Index range of [code] within [fullContent].
     */
    private fun isValidOTP(code: String, fullContent: String, range: IntRange): Boolean {
        if (code.length !in 4..8) return false

        val before = if (range.first > 0) fullContent[range.first - 1] else ' '
        val after  = if (range.last  < fullContent.length - 1) fullContent[range.last + 1] else ' '

        // Reject if the code is part of a longer digit sequence
        if (before.isDigit() || after.isDigit()) return false

        val allowed = setOf(' ', '\n', '\r', '\t', '.', ':', '-', ',', '(', ')', '[', ']', '<', '>')
        if (!allowed.contains(before) && !before.isLetter()) return false
        if (!allowed.contains(after)  && !after.isLetter())  return false

        return true
    }
}
