package com.bunty.clipsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast

/**
 * OTPListeningService is a [BroadcastReceiver] that listens for incoming SMS messages
 * and automatically extracts OTP codes from them.
 *
 * **How it works:**
 * 1. Receives the `SMS_RECEIVED` broadcast (requires READ_SMS + RECEIVE_SMS permissions).
 * 2. Checks each message body for OTP-related keywords (e.g. "otp", "verification", "code").
 * 3. If a keyword match is found, tries to extract a 4–8 digit numeric code using two regex patterns.
 * 4. Copies the extracted OTP to the clipboard via [ClipboardGhostActivity] and
 *    syncs it to the Mac via [OTPNotificationService].
 *
 * A 1-second debounce ([MIN_PROCESSING_INTERVAL]) prevents duplicate processing when
 * a single SMS arrives as multiple PDU fragments.
 *
 * Registered in AndroidManifest.xml with `<receiver>` and the `SMS_RECEIVED` intent-filter.
 */
class OTPListeningService : BroadcastReceiver() {

    companion object {
        private const val TAG = "OTPListeningService"

        // Debounce: ignore a second SMS that arrives within 1 second of the first
        private var lastProcessedTime = 0L
        private const val MIN_PROCESSING_INTERVAL = 1000L

        /**
         * Keywords that indicate an SMS likely contains an OTP.
         * Checked case-insensitively against the full message body.
         */
        private val OTP_KEYWORDS = listOf(
            "otp",
            "verification",
            "verify",
            "code",
            "passcode",
            "one time",
            "authentication",
            "confirm",
            "security code",
            "pin"
        )
    }

    /**
     * Entry point for incoming SMS broadcasts.
     *
     * Guards:
     * - Ignores non-SMS_RECEIVED intents.
     * - Applies a 1-second debounce to avoid duplicate processing.
     * - Processes all PDU messages, stops at the first successfully extracted OTP.
     */
    override fun onReceive(context: Context, intent: Intent) {
        // Only process SMS_RECEIVED broadcasts
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        val currentTime = System.currentTimeMillis()

        // Debounce: skip if we just processed an SMS within the last second
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) return

        try {
            val messages = extractSmsMessages(intent)

            for (message in messages) {
                val messageBody = message.messageBody ?: continue

                if (containsOTPKeyword(messageBody)) {
                    val otpCode = extractOTP(messageBody)

                    if (otpCode != null) {
                        lastProcessedTime = currentTime

                        // Copy OTP to Android clipboard so the user can paste it
                        ClipboardGhostActivity.copyToClipboard(appContext, otpCode)
                        // Push OTP notification to the paired Mac via Firestore
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
     * Extracts the list of [SmsMessage] objects from a `SMS_RECEIVED` broadcast intent.
     *
     * Uses [Telephony.Sms.Intents.getMessagesFromIntent] which handles multi-part messages
     * (PDU reassembly) automatically. Returns an empty list on any error.
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
     * Returns `true` if [message] contains at least one word from [OTP_KEYWORDS].
     * Comparison is case-insensitive.
     */
    private fun containsOTPKeyword(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return OTP_KEYWORDS.any { keyword -> lowerMessage.contains(keyword) }
    }

    /**
     * Attempts to extract a numeric OTP code from [message] using two regex patterns:
     *
     * - **Pattern 1**: A standalone 4–8 digit sequence (`\b(\d{4,8})\b`).
     * - **Pattern 2**: Two groups of 3–4 digits separated by a space or dash (e.g. "123 456").
     *
     * Each candidate from Pattern 1 is validated by [isValidOTP] before being returned.
     * Pattern 2 is used as a fallback.
     *
     * @return The extracted OTP string, or `null` if none was found.
     */
    private fun extractOTP(message: String): String? {
        // Pattern 1: plain 4–8 digit number bounded by word boundaries
        val pattern1 = Regex("""\b(\d{4,8})\b""")

        // Pattern 2: split-format OTP like "123 456" or "123-456"
        val pattern2 = Regex("""(\d{3,4})[\s\-](\d{3,4})""")

        // Try all Pattern 1 matches and return the first one that passes validation
        for (match in pattern1.findAll(message)) {
            val code = match.groupValues[1]
            if (isValidOTP(code, message, match.range)) {
                return code
            }
        }

        // Fallback: try Pattern 2 and combine the two groups
        val match2 = pattern2.find(message)
        if (match2 != null) {
            val combined = match2.groupValues[1] + match2.groupValues[2]
            if (combined.length in 4..8) return combined
        }

        return null
    }

    /**
     * Validates that a numeric [code] candidate is a genuine OTP and not part of a longer
     * number (e.g. a phone number or order ID).
     *
     * Rules:
     * - Length must be 4–8 digits.
     * - The characters immediately before and after the code must not be digits.
     * - The surrounding characters must be whitespace, punctuation, or letters.
     *
     * @param code        The digit string to validate.
     * @param fullMessage The original message text (used for boundary checks).
     * @param range       The character range of [code] within [fullMessage].
     */
    private fun isValidOTP(code: String, fullMessage: String, range: IntRange): Boolean {
        if (code.length !in 4..8) return false

        // Check the character immediately before and after the digit sequence
        val before = if (range.first > 0) fullMessage[range.first - 1] else ' '
        val after  = if (range.last  < fullMessage.length - 1) fullMessage[range.last + 1] else ' '

        // Reject if it is part of a longer number
        if (before.isDigit() || after.isDigit()) return false

        val allowedChars = setOf(' ', '\n', '\r', '\t', '.', ':', '-', ',', '(', ')', '[', ']')
        if (!allowedChars.contains(before) && !before.isLetter() && before != ' ') return false
        if (!allowedChars.contains(after)  && !after.isLetter()  && after  != ' ') return false

        return true
    }
}
