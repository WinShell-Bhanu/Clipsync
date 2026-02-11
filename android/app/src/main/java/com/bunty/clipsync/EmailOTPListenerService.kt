package com.bunty.clipsync

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Email OTP Listener Service - Monitors email app notifications for OTP codes
 *
 * BATTERY OPTIMIZATION:
 * - Uses WHITELIST approach (only checks known email apps)
 * - Package name checked FIRST before any content access
 * - All non-email notifications ignored immediately (no processing)
 * - Prevents battery drain from checking every system notification
 *
 * Detection Logic (TWO-STEP VERIFICATION):
 * 1. STEP 1: Verify notification is from whitelisted email app (Gmail, Outlook, etc.)
 *    → If NOT email app → IMMEDIATE RETURN (no further processing)
 * 2. STEP 2: Search for OTP keywords in notification text
 *    → Only executed if Step 1 passed
 *    → Searches for: "OTP", "one time verification", "verification code", etc.
 * 3. Extract 4-8 digit numeric patterns
 * 4. Validate and auto-copy detected OTP to clipboard
 *
 * Privacy Features:
 * - ONLY processes notifications from email apps
 * - Does NOT access banking, messaging, or social apps
 * - Does NOT read email content beyond notification text
 * - Transparent whitelist of supported apps
 *
 * Supported Email Apps:
 * - Gmail
 * - Outlook
 * - Yahoo Mail
 * - Samsung Email
 * - ProtonMail
 * - BlueMail
 * - K-9 Mail
 * - Generic email apps
 */
class EmailOTPListenerService : NotificationListenerService() {

    // Handler for UI operations - needs cleanup in onDestroy
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "EmailOTPListener"

        // Prevent duplicate processing
        private var lastProcessedTime = 0L
        private var lastProcessedOTP = ""
        private const val MIN_PROCESSING_INTERVAL = 2000L // 2 seconds

        // Popular email app package names
        private val EMAIL_APP_PACKAGES = setOf(
            "com.google.android.gm",              // Gmail
            "com.microsoft.office.outlook",       // Outlook
            "com.yahoo.mobile.client.android.mail", // Yahoo Mail
            "com.samsung.android.email.provider", // Samsung Email
            "ch.protonmail.android",              // ProtonMail
            "me.bluemail.mail",                   // BlueMail
            "com.android.email",                  // Generic Email
            "com.fsck.k9",                        // K-9 Mail
            "com.apple.android.mail"              // Apple Mail (if available)
        )

        // OTP Keywords (checked AFTER email app verification)
        // Only searches for these keywords in notifications from email apps
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null) return

        val packageName = sbn.packageName

        if (!EMAIL_APP_PACKAGES.contains(packageName)) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) {
            return
        }

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

            val fullContent = "$title $text $bigText $subText"

            if (fullContent.isBlank()) {
                return
            }

            if (containsOTPKeyword(fullContent)) {
                val otpCode = extractOTP(fullContent)

                if (otpCode != null && otpCode != lastProcessedOTP) {
                    lastProcessedTime = currentTime
                    lastProcessedOTP = otpCode

                    ClipboardGhostActivity.copyToClipboard(this, otpCode)
                    OTPNotificationService.notifyOTPDetected(this, otpCode)

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

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Do nothing - we only care about posted notifications
    }

    override fun onDestroy() {
        super.onDestroy()

        mainHandler.removeCallbacksAndMessages(null)
        lastProcessedTime = 0L
        lastProcessedOTP = ""
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Get friendly app name from package name
     */
    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.google.android.gm" -> "Gmail"
            "com.microsoft.office.outlook" -> "Outlook"
            "com.yahoo.mobile.client.android.mail" -> "Yahoo Mail"
            "com.samsung.android.email.provider" -> "Samsung Email"
            "ch.protonmail.android" -> "ProtonMail"
            "me.bluemail.mail" -> "BlueMail"
            "com.android.email" -> "Email"
            "com.fsck.k9" -> "K-9 Mail"
            else -> packageName
        }
    }

    /**
     * Check if content contains OTP keywords
     */
    private fun containsOTPKeyword(content: String): Boolean {
        val lowerContent = content.lowercase()
        return OTP_KEYWORDS.any { keyword ->
            lowerContent.contains(keyword)
        }
    }

    /**
     * Extract OTP code from content using regex patterns
     */
    private fun extractOTP(content: String): String? {
        // Pattern 1: Standalone 4-8 digits with word boundaries
        val pattern1 = Regex("""\b(\d{4,8})\b""")

        // Pattern 2: Digits with spaces/dashes
        val pattern2 = Regex("""(\d{3,4})[\s\-](\d{3,4})""")

        // Try Pattern 1
        val match1 = pattern1.findAll(content)
        for (match in match1) {
            val code = match.groupValues[1]
            if (isValidOTP(code, content, match.range)) {
                return code
            }
        }

        // Try Pattern 2
        val match2 = pattern2.find(content)
        if (match2 != null) {
            val combined = match2.groupValues[1] + match2.groupValues[2]
            if (combined.length in 4..8) {
                return combined
            }
        }

        return null
    }

    /**
     * Validate extracted OTP
     */
    private fun isValidOTP(code: String, fullContent: String, range: IntRange): Boolean {
        if (code.length !in 4..8) {
            return false
        }

        // Get surrounding characters
        val before = if (range.first > 0) fullContent[range.first - 1] else ' '
        val after = if (range.last < fullContent.length - 1) fullContent[range.last + 1] else ' '

        // Reject if surrounded by digits
        if (before.isDigit() || after.isDigit()) {
            return false
        }

        // Reject special characters adjacent
        val allowedChars = setOf(' ', '\n', '\r', '\t', '.', ':', '-', ',', '(', ')', '[', ']', '<', '>')
        if (!allowedChars.contains(before) && !before.isLetter()) {
            return false
        }
        if (!allowedChars.contains(after) && !after.isLetter()) {
            return false
        }

        return true
    }
}
