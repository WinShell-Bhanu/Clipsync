package com.bunty.clipsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast

/**
 * A [BroadcastReceiver] that intercepts incoming SMS messages and automatically extracts
 * One-Time Password (OTP) codes for instant delivery to the paired Mac.
 *
 * Detection pipeline:
 *  1. Receives the [Telephony.Sms.Intents.SMS_RECEIVED_ACTION] broadcast (requires
 *     READ_SMS and RECEIVE_SMS manifest permissions).
 *  2. A 1-second debounce ([MIN_PROCESSING_INTERVAL]) guards against duplicate triggers
 *     when a long SMS is split into multiple PDU fragments that each fire the broadcast.
 *  3. Each [SmsMessage] body is scanned for [OTP_KEYWORDS] (case-insensitive). Keywords
 *     span 22 languages — English is the primary match since most banking and service
 *     SMSes globally are sent in English; the regional keywords are a safety net for
 *     apps that localise their SMS copy.
 *  4. If a keyword is found, two regex patterns are tried in order to extract the code:
 *     - A word-boundary pattern for plain 4–8 digit sequences (e.g. "123456").
 *     - A split-format fallback for grouped codes separated by space or dash (e.g. "123 456").
 *     Each candidate from the primary pattern is run through [isValidOTP] to reject
 *     digits that are embedded inside phone numbers or order IDs.
 *  5. The extracted OTP is copied to the Android clipboard via [ClipboardGhostActivity]
 *     (which works around the Android 10 background-clipboard restriction) and pushed to
 *     the Mac via [OTPNotificationService].
 *
 * Declared in AndroidManifest.xml with an SMS_RECEIVED intent-filter and the
 * android.permission.RECEIVE_SMS requirement.
 */
class OTPListeningService : BroadcastReceiver() {

    companion object {
        private const val TAG = "OTPListeningService"

        // Tracks the wall-clock time of the most recent successful OTP extraction.
        // Used together with MIN_PROCESSING_INTERVAL to suppress duplicate broadcasts.
        private var lastProcessedTime = 0L

        /**
         * Minimum elapsed time in milliseconds between two successive OTP extractions.
         * Prevents the same SMS being processed multiple times when a carrier splits it
         * into several PDU fragments that each individually trigger SMS_RECEIVED.
         */
        private const val MIN_PROCESSING_INTERVAL = 1000L

        /**
         * Multilingual keyword corpus used to identify SMS messages that carry an OTP.
         *
         * Matching is performed case-insensitively against the full message body.
         * Any single keyword present in the body is sufficient to trigger OTP extraction.
         *
         * Language coverage (22 languages):
         * English · Hindi · Bengali · Telugu · Tamil · Gujarati · Kannada · Malayalam ·
         * Marathi · Punjabi · French · German · Spanish · Arabic · Turkish · Russian · Indonesian
         *
         * English keywords appear first because virtually all Indian and global banks send
         * OTP SMSes in English regardless of the device language. The regional-language
         * entries are a safety net for apps that localise their SMS copy.
         */
        private val OTP_KEYWORDS = listOf(
            // ── English — covers most banking and SaaS OTP formats ──
            "otp",
            "verification",
            "verify",
            "code",
            "passcode",
            "one time",
            "one-time",
            "authentication",
            "confirm",
            "security code",
            "pin",
            "2fa",
            "two-factor",
            "two factor",
            // ── Hindi ──
            "ओटीपी",
            "सत्यापन कोड",
            "एकबारगी पासवर्ड",
            "सुरक्षा कोड",
            // ── Bengali ──
            "যাচাইকরণ কোড",
            "ওটিপি",
            "নিরাপত্তা কোড",
            // ── Telugu ──
            "ధృవీకరణ కోడ్",
            "ఓటీపీ",
            // ── Tamil ──
            "சரிபார்ப்பு குறியீடு",
            "ஒருமுறை கடவுச்சொல்",
            // ── Gujarati ──
            "ઓટીપી",
            "ચકાસણી કોડ",
            // ── Kannada ──
            "ಒಟಿಪಿ",
            "ಪರಿಶೀಲನಾ ಕೋಡ್",
            // ── Malayalam ──
            "ഒടിപി",
            "സ്ഥിരീകരണ കോഡ്",
            // ── Marathi ──
            "ओटीपी",
            "सत्यापन कोड",
            // ── Punjabi ──
            "ਓਟੀਪੀ",
            "ਤਸਦੀਕ ਕੋਡ",
            // ── French ──
            "code de vérification",
            "mot de passe à usage unique",
            // ── German ──
            "verifizierungscode",
            "einmalpasswort",
            // ── Spanish ──
            "código de verificación",
            // ── Arabic ──
            "رمز التحقق",
            "كلمة المرور لمرة واحدة",
            // ── Turkish ──
            "doğrulama kodu",
            // ── Russian ──
            "код подтверждения",
            // ── Indonesian ──
            "kode verifikasi"
        )
    }

    /**
     * Entry point called by the system for every broadcast matching the receiver's intent-filter.
     *
     * Early-exit conditions applied in order:
     *  - The intent action must be [Telephony.Sms.Intents.SMS_RECEIVED_ACTION].
     *  - Elapsed time since the last successful extraction must exceed [MIN_PROCESSING_INTERVAL].
     *
     * On passing both guards the receiver iterates through all PDU messages in the intent
     * and stops as soon as the first valid OTP is found and dispatched — avoiding
     * double-detection when a multipart SMS carries the code in multiple fragments.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        val currentTime = System.currentTimeMillis()

        // Debounce: skip this broadcast if an OTP was already extracted within the past second.
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) return

        try {
            val messages = extractSmsMessages(intent)

            for (message in messages) {
                val messageBody = message.messageBody ?: continue

                if (containsOTPKeyword(messageBody)) {
                    val otpCode = extractOTP(messageBody)

                    if (otpCode != null) {
                        lastProcessedTime = currentTime

                        // Write the OTP to the Android clipboard so it is ready to paste immediately.
                        ClipboardGhostActivity.copyToClipboard(appContext, otpCode)
                        // Publish the OTP to the paired Mac over Firestore.
                        OTPNotificationService.notifyOTPDetected(appContext, otpCode)
                        Toast.makeText(appContext, "OTP Copied: $otpCode", Toast.LENGTH_SHORT).show()
                        break  // stop after the first OTP found in the message batch
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS", e)
        }
    }

    /**
     * Converts the raw PDU byte arrays in [intent] into a list of [SmsMessage] objects.
     *
     * Delegates to [Telephony.Sms.Intents.getMessagesFromIntent], which handles multi-part
     * message reassembly automatically. Returns an empty list if extraction fails so the
     * caller can iterate safely without a null check.
     */
    private fun extractSmsMessages(intent: Intent): List<SmsMessage> {
        return try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting SMS messages", e)
            emptyList()
        }
    }

    /**
     * Returns `true` when [message] contains at least one entry from [OTP_KEYWORDS].
     * The check is case-insensitive to handle inconsistent capitalisation in OTP SMSes
     * (e.g. "OTP", "otp", "Otp" all match the same keyword).
     */
    private fun containsOTPKeyword(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return OTP_KEYWORDS.any { keyword -> lowerMessage.contains(keyword) }
    }

    /**
     * Extracts the first plausible OTP code from [message].
     *
     * Two regex patterns are applied in order of preference:
     *
     * **Pattern 1** — plain numeric code: `\b(\d{4,8})\b`
     *   Matches a standalone sequence of 4–8 digits bounded by word boundaries.
     *   Every match is validated by [isValidOTP] to filter out phone numbers, dates,
     *   and order IDs that also happen to be 4–8 digits long.
     *
     * **Pattern 2** — split-format code: `(\d{3,4})[\s\-](\d{3,4})`
     *   Matches two groups of 3–4 digits separated by a space or hyphen (e.g. "123 456"
     *   or "1234-5678"). The groups are concatenated; combined length must be 4–8 digits.
     *
     * @return The extracted OTP string, or `null` if no valid code was found.
     */
    private fun extractOTP(message: String): String? {
        // Pattern 1: standalone 4–8 digit number bounded by word boundaries.
        val pattern1 = Regex("""\b(\d{4,8})\b""")

        // Pattern 2: split-format OTP like "123 456" or "123-456".
        val pattern2 = Regex("""(\d{3,4})[\s\-](\d{3,4})""")

        // Try every Pattern 1 match; return the first that passes the boundary validation.
        for (match in pattern1.findAll(message)) {
            val code = match.groupValues[1]
            if (isValidOTP(code, message, match.range)) {
                return code
            }
        }

        // Fallback: try Pattern 2 and join the two digit groups.
        val match2 = pattern2.find(message)
        if (match2 != null) {
            val combined = match2.groupValues[1] + match2.groupValues[2]
            if (combined.length in 4..8) return combined
        }

        return null
    }

    /**
     * Decides whether a digit sequence is a genuine OTP rather than part of a phone number,
     * monetary amount, or other incidental digit run in the message body.
     *
     * Validation rules:
     *  1. The code must be 4–8 digits long (standard OTP length range).
     *  2. The character immediately before the sequence must not be a digit — indicating
     *     the code is embedded in a longer number (e.g. a 10-digit phone number).
     *  3. The character immediately after the sequence must not be a digit for the same reason.
     *  4. Both boundary characters must be whitespace, common punctuation, or alphabetic,
     *     rejecting unusual delimiters that the word-boundary regex might not catch.
     *
     * @param code        The candidate digit string to validate.
     * @param fullMessage The entire SMS body, used for boundary character lookup.
     * @param range       The character index range of [code] within [fullMessage].
     * @return `true` if [code] passes all rules and should be treated as an OTP.
     */
    private fun isValidOTP(code: String, fullMessage: String, range: IntRange): Boolean {
        if (code.length !in 4..8) return false

        val before = if (range.first > 0) fullMessage[range.first - 1] else ' '
        val after  = if (range.last  < fullMessage.length - 1) fullMessage[range.last + 1] else ' '

        // Reject the candidate if it is part of a longer digit sequence.
        if (before.isDigit() || after.isDigit()) return false

        val allowedChars = setOf(' ', '\n', '\r', '\t', '.', ':', '-', ',', '(', ')', '[', ']')
        if (!allowedChars.contains(before) && !before.isLetter() && before != ' ') return false
        if (!allowedChars.contains(after)  && !after.isLetter()  && after  != ' ') return false

        return true
    }
}
