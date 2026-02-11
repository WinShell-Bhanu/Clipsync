package com.bunty.clipsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast

/**
 * OTP Listening Service - Automatically detects OTP codes from incoming SMS messages
 *
 * Detection Logic:
 * 1. Listens for incoming SMS messages
 * 2. Checks for trigger keywords: "OTP", "verification", "code", etc.
 * 3. Extracts 4-8 digit numeric patterns
 * 4. Validates patterns (no special chars, no letters between digits)
 * 5. Auto-copies detected OTP to clipboard using ClipboardGhostActivity
 *
 * Note: For email OTP detection, see EmailOTPListenerService
 */
class OTPListeningService : BroadcastReceiver() {

    companion object {
        private const val TAG = "OTPListeningService"

        // Prevent duplicate processing within short time window
        private var lastProcessedTime = 0L
        private const val MIN_PROCESSING_INTERVAL = 1000L // 1 second

        // Handler for UI operations (shared across all broadcasts)
        private val mainHandler = Handler(Looper.getMainLooper())

        // Trigger keywords to identify OTP messages
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL) {
            return
        }

        try {
            val messages = extractSmsMessages(intent)

            for (message in messages) {
                val messageBody = message.messageBody ?: continue

                if (containsOTPKeyword(messageBody)) {
                    val otpCode = extractOTP(messageBody)

                    if (otpCode != null) {
                        lastProcessedTime = currentTime

                        ClipboardGhostActivity.copyToClipboard(context, otpCode)
                        OTPNotificationService.notifyOTPDetected(context, otpCode)

                        mainHandler.post {
                            Toast.makeText(
                                context,
                                "OTP Copied: $otpCode",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        break
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS", e)
        }
    }

    /**
     * Extract SMS messages from intent (supports multiple formats)
     */
    private fun extractSmsMessages(intent: Intent): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // Modern Android - use Telephony API
                val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                messages.addAll(smsMessages)
            } else {
                // Legacy Android
                val pdus = intent.extras?.get("pdus") as? Array<*>
                pdus?.forEach { pdu ->
                    val pduBytes = pdu as? ByteArray ?: return@forEach
                    val message = SmsMessage.createFromPdu(pduBytes)
                    messages.add(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting SMS messages", e)
        }

        return messages
    }

    /**
     * Check if message contains OTP-related keywords
     */
    private fun containsOTPKeyword(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return OTP_KEYWORDS.any { keyword ->
            lowerMessage.contains(keyword)
        }
    }

    /**
     * Extract OTP code from message using smart regex patterns
     *
     * Rules:
     * - Find 4-8 digit sequences
     * - No special characters before/after
     * - No letters between digits
     * - Clean digit-only sequences
     */
    private fun extractOTP(message: String): String? {
        // Pattern 1: Find standalone digit sequences (4-8 digits)
        // \b ensures word boundary (no special chars or letters adjacent)
        val pattern1 = Regex("""\b(\d{4,8})\b""")

        // Pattern 2: Digits separated by space/dash (e.g., "1234 5678" or "123-456")
        // We'll extract and combine them if they form valid OTP length
        val pattern2 = Regex("""(\d{3,4})[\s\-](\d{3,4})""")

        // Try Pattern 1 first (standalone digits)
        val match1 = pattern1.findAll(message)
        for (match in match1) {
            val code = match.groupValues[1]

            // Validate: Must be clean digits, no adjacent special chars
            if (isValidOTP(code, message, match.range)) {
                return code
            }
        }

        // Try Pattern 2 (space/dash separated)
        val match2 = pattern2.find(message)
        if (match2 != null) {
            val combined = match2.groupValues[1] + match2.groupValues[2]
            if (combined.length in 4..8) {
                return combined
            }
        }

        return null
    }

    /**
     * Validate extracted OTP code
     * - Check surroundings for special characters
     * - Ensure it's not part of a larger number (like phone number)
     */
    private fun isValidOTP(code: String, fullMessage: String, range: IntRange): Boolean {
        if (code.length !in 4..8) {
            return false
        }

        val before = if (range.first > 0) fullMessage[range.first - 1] else ' '
        val after = if (range.last < fullMessage.length - 1) fullMessage[range.last + 1] else ' '

        if (before.isDigit() || after.isDigit()) {
            return false
        }

        val allowedChars = setOf(' ', '\n', '\r', '\t', '.', ':', '-', ',', '(', ')', '[', ']')
        if (!allowedChars.contains(before) && !before.isLetter() && before != ' ') {
            return false
        }
        if (!allowedChars.contains(after) && !after.isLetter() && after != ' ') {
            return false
        }

        return true
    }
}
