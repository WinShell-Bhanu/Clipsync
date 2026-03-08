package com.bunty.clipsync

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * A [NotificationListenerService] that monitors email-app notifications for OTP codes and
 * forwards any code it finds to the paired Mac, complementing the SMS-based [OTPListeningService].
 *
 * Many services now deliver OTPs exclusively by email rather than SMS. Without this service,
 * ClipSync users would need to open their email app manually and copy the code by hand.
 * This service eliminates that friction by extracting the code the moment the email
 * notification appears in the status bar.
 *
 * Detection pipeline:
 *  1. [onNotificationPosted] fires for every notification posted on the device.
 *  2. The source package name is checked against [EMAIL_APP_PACKAGES]; all others are ignored.
 *  3. A 2-second debounce ([MIN_PROCESSING_INTERVAL]) suppresses rapid repeat notifications
 *     from the same email thread.
 *  4. All text extras from the notification (title, body, big-text, sub-text) are
 *     concatenated into a single string for scanning.
 *  5. The string is checked for [OTP_KEYWORDS] (22 languages, case-insensitive).
 *  6. A 4–8 digit code is extracted and validated by [isValidOTP].
 *  7. Duplicate codes are rejected by comparing against [lastProcessedOTP].
 *  8. The unique code is written to the clipboard via [ClipboardGhostActivity] and pushed
 *     to the Mac via [OTPNotificationService]. A Toast on the main thread confirms capture.
 *
 * Requires the Notification Listener permission (Android Settings > Notifications > Notification
 * access). Declared in AndroidManifest.xml with BIND_NOTIFICATION_LISTENER_SERVICE permission.
 */
class EmailOTPListenerService : NotificationListenerService() {

    /**
     * Handler tied to the main (UI) thread. Needed because [onNotificationPosted] can be
     * called from a binder thread, but [Toast.makeText] must run on the main thread.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "EmailOTPListener"

        // Timestamp of the last successful OTP extraction, used for the debounce check.
        private var lastProcessedTime = 0L
        // The OTP string most recently acted upon; compared against new candidates to skip duplicates.
        private var lastProcessedOTP  = ""

        /**
         * Minimum gap in milliseconds between successive OTP extractions from email notifications.
         * Two seconds is generous enough to absorb repeated badge-update notifications
         * (e.g. "1 new message", "2 new messages") that arrive in quick succession for the same email.
         */
        private const val MIN_PROCESSING_INTERVAL = 2000L

        /**
         * Allow-list of email app package names whose notifications are inspected for OTP codes.
         *
         * Only notifications from these packages are processed; all others are ignored to keep
         * CPU and battery overhead to a minimum and to avoid false positives from non-email apps.
         * Add new packages here when new email clients gain significant adoption.
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
         * Multilingual keyword list used to identify notifications that carry an OTP.
         *
         * Applied case-insensitively against the concatenated notification text. One match
         * is sufficient to proceed to code extraction.
         *
         * Covers 22 languages across English, major Indian regional languages (Hindi, Bengali,
         * Telugu, Tamil, Gujarati, Kannada, Malayalam, Marathi, Punjabi), and global languages
         * (French, German, Spanish, Arabic, Turkish, Russian, Indonesian). English keywords
         * appear first because virtually all email OTPs from Indian and global services are
         * written in English; the other languages serve as a safety net for regional providers.
         */
        private val OTP_KEYWORDS = listOf(
            // ── English ──
            "otp",
            "verification code",
            "one time password",
            "one time verification",
            "one-time password",
            "one-time verification",
            "security code",
            "code",
            "two factor authentication",
            "2fa",
            "passcode",
            "pin",
            // ── Hindi ──
            "ओटीपी",
            "सत्यापन कोड",
            "एकबारगी पासवर्ड",
            "सुरक्षा कोड",
            // ── Bengali ──
            "যাচাইকরণ কোড",
            "ওটিপি",
            // ── Telugu ──
            "ధృవీకరణ కోడ్",
            "ఓటీపీ",
            // ── Tamil ──
            "சரிபார்ப்பு குறியீடு",
            // ── Gujarati ──
            "ઓટીપી", "ચકાસણી કોડ",
            // ── Kannada ──
            "ಒಟಿಪಿ", "ಪರಿಶೀಲನಾ ಕೋಡ್",
            // ── Malayalam ──
            "ഒടിപി", "സ്ഥിരീകരണ കോഡ്",
            // ── Marathi ──
            "सत्यापन कोड",
            // ── Punjabi ──
            "ਓਟੀਪੀ", "ਤਸਦੀਕ ਕੋਡ",
            // ── French ──
            "code de vérification",
            "mot de passe à usage unique",
            // ── German ──
            "verifizierungscode",
            // ── Spanish ──
            "código de verificación",
            // ── Arabic ──
            "رمز التحقق",
            // ── Turkish ──
            "doğrulama kodu",
            // ── Russian ──
            "код подтверждения",
            // ── Indonesian ──
            "kode verifikasi"
        )
    }

    /**
     * Invoked by the system whenever any notification is posted on the device.
     *
     * Filtering and guard conditions applied in order:
     *  1. Null check on [sbn] — the system contract allows null here.
     *  2. Package allow-list check against [EMAIL_APP_PACKAGES].
     *  3. Time-based debounce against [MIN_PROCESSING_INTERVAL].
     *  4. Full notification text assembled from all extras, checked for [OTP_KEYWORDS].
     *  5. Extracted OTP must differ from [lastProcessedOTP] to prevent re-processing.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (!EMAIL_APP_PACKAGES.contains(sbn.packageName)) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) return

        try {
            val extras = sbn.notification?.extras ?: return

            // Pull every text-bearing extra and join them so a code split across
            // the title and body can still be matched in a single pass.
            val title   = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()    ?: ""
            val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()     ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

            val fullContent = "$title $text $bigText $subText"
            if (fullContent.isBlank()) return

            if (containsOTPKeyword(fullContent)) {
                val otpCode = extractOTP(fullContent)

                // Only act if we found a new OTP that differs from the last one processed,
                // preventing the same code from being sent to the Mac multiple times if the
                // email app refreshes its notification badge for the same message.
                if (otpCode != null && otpCode != lastProcessedOTP) {
                    lastProcessedTime = currentTime
                    lastProcessedOTP  = otpCode

                    // Write to the Android clipboard so the user can paste immediately if needed.
                    ClipboardGhostActivity.copyToClipboard(this, otpCode)
                    // Push the OTP to the Mac via Firestore.
                    OTPNotificationService.notifyOTPDetected(this, otpCode)

                    // Toast.makeText must run on the main thread; post via mainHandler.
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

    /** Called when a notification is dismissed. Not used by this service. */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    /**
     * Called when the service is shut down — either because the user revoked notification-listener
     * permission or the system destroyed the service. Cancels all pending main-thread handler
     * callbacks to prevent memory leaks, and resets the deduplication state so a future
     * service instance starts cleanly.
     */
    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        lastProcessedTime = 0L
        lastProcessedOTP  = ""
    }

    /**
     * Called once the service has successfully bound to the notification system and is
     * ready to receive [onNotificationPosted] callbacks.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    /**
     * Called when the service loses its notification-listener binding — typically after the
     * user revokes the Notification Listener permission or the system performs a settings reset.
     * Pending main-thread callbacks are cancelled here to prevent handler memory leaks.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Translates an email-app package name into a human-readable display name.
     * Used in log messages and any future debug UI to identify which app produced a notification.
     *
     * @param packageName The Android package identifier of the email app.
     * @return A friendly name string, or the raw [packageName] for unrecognised packages.
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
     * Returns `true` if [content] contains at least one keyword from [OTP_KEYWORDS].
     * Lowercases [content] before comparison to ensure the check is case-insensitive.
     */
    private fun containsOTPKeyword(content: String): Boolean {
        val lower = content.lowercase()
        return OTP_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    /**
     * Extracts the first valid OTP code from [content] using two regex patterns.
     *
     * **Pattern 1** — word-boundary numeric sequence: `\b(\d{4,8})\b`
     *   Matches any standalone 4–8 digit number. Each match is passed through [isValidOTP]
     *   to reject digit sequences that form part of a longer number (phone numbers, etc.).
     *
     * **Pattern 2** — split-format: `(\d{3,4})[\s\-](\d{3,4})`
     *   Fallback for grouped codes like "123 456" or "1234-5678". The two digit groups are
     *   joined and the combined length must be 4–8 digits.
     *
     * @return The OTP string if found and valid, or `null` if nothing matched.
     */
    private fun extractOTP(content: String): String? {
        // Pattern 1: standalone 4–8 digit number.
        val pattern1 = Regex("""\b(\d{4,8})\b""")
        // Pattern 2: split-format like "123 456" or "123-456".
        val pattern2 = Regex("""(\d{3,4})[\s\-](\d{3,4})""")

        for (match in pattern1.findAll(content)) {
            val code = match.groupValues[1]
            if (isValidOTP(code, content, match.range)) return code
        }

        // Fallback: try the split-format pattern and concatenate the two groups.
        val match2 = pattern2.find(content)
        if (match2 != null) {
            val combined = match2.groupValues[1] + match2.groupValues[2]
            if (combined.length in 4..8) return combined
        }

        return null
    }

    /**
     * Determines whether a digit sequence qualifies as a genuine OTP.
     *
     * A candidate is rejected if:
     *  - Its length is outside the 4–8 digit OTP range.
     *  - The character immediately before it is a digit (it is part of a longer number).
     *  - The character immediately after it is a digit (same reason).
     *  - Either boundary character is something other than whitespace, common punctuation,
     *    or an alphabetic character (an unusual delimiter the regex may have missed).
     *
     * Boundary characters default to a space when [code] appears at the very start or end
     * of [fullContent], so edge-of-string codes are accepted correctly.
     *
     * @param code        Candidate digit string.
     * @param fullContent Complete notification text providing boundary characters.
     * @param range       Character range of [code] within [fullContent].
     * @return `true` if [code] should be treated as a valid OTP.
     */
    private fun isValidOTP(code: String, fullContent: String, range: IntRange): Boolean {
        if (code.length !in 4..8) return false

        val before = if (range.first > 0) fullContent[range.first - 1] else ' '
        val after  = if (range.last  < fullContent.length - 1) fullContent[range.last + 1] else ' '

        // Reject if the code is embedded in a longer digit sequence.
        if (before.isDigit() || after.isDigit()) return false

        val allowed = setOf(' ', '\n', '\r', '\t', '.', ':', '-', ',', '(', ')', '[', ']', '<', '>')
        if (!allowed.contains(before) && !before.isLetter()) return false
        if (!allowed.contains(after)  && !after.isLetter())  return false

        return true
    }
}
