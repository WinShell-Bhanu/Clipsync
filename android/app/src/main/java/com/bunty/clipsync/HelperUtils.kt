package com.bunty.clipsync

import java.util.regex.Pattern

/**
 * HelperUtils provides stateless utility functions shared across the app.
 *
 * Currently focused on OTP detection: determining whether a short text string looks
 * like a one-time password and extracting the numeric code from it.
 *
 * The regex pattern [OTP_PATTERN] matches three common OTP formats:
 * 1. A plain 4–8 digit code (e.g. `"123456"`).
 * 2. A split 6-digit code with a separator (e.g. `"123-456"` or `"123 456"`).
 * 3. An alphanumeric code with an optional dash (e.g. `"AB-12345"`).
 */
object HelperUtils {

    /**
     * Compiled regex that matches common OTP formats:
     * - Group 1: `\b(\d{4,8})\b`             — plain 4–8 digit code
     * - Group 2: `\b(\d{3}[-\s]\d{3})\b`     — split numeric code with dash or space
     * - Group 3: `\b([A-Za-z]{1,4}-?\d{3,8})\b` — alphanumeric code (e.g. `"G-123456"`)
     */
    private val OTP_PATTERN = Pattern.compile(
        "\\b(\\d{4,8})\\b|\\b(\\d{3}[-\\s]\\d{3})\\b|\\b([A-Za-z]{1,4}-?\\d{3,8})\\b"
    )

    /**
     * Returns `true` if [text] looks like an OTP message.
     *
     * Heuristics applied:
     * - `null` or empty strings are never OTPs.
     * - Strings longer than 100 characters are rejected (OTP messages are short).
     * - The text must contain at least one match of [OTP_PATTERN].
     *
     * @param text The clipboard text or notification body to inspect.
     */
    fun isOTP(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        if (text.length > 100) return false  // OTP messages are always short
        return OTP_PATTERN.matcher(text).find()
    }

    /**
     * Extracts and returns the first OTP code found in [text], or `null` if none is found.
     *
     * Strings longer than 300 characters are rejected to avoid scanning large blobs of text.
     *
     * @param text The clipboard text or notification body to search.
     * @return The raw matched OTP string (may include dashes/letters depending on format),
     *         or `null` if no match was found.
     */
    fun extractOTP(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        if (text.length > 300) return null  // skip excessively long strings

        val matcher = OTP_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }
}
