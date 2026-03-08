package com.bunty.clipsync

import java.util.regex.Pattern

/**
 * HelperUtils is a stateless singleton that provides shared utility functions used across
 * multiple layers of the ClipSync application.
 *
 * Its primary responsibility is **OTP detection**: given an arbitrary string — clipboard
 * content, a notification body, or any short text — it can determine whether that string
 * looks like a one-time password message and, if so, extract the raw code from it.
 *
 * Detection is intentionally conservative. The logic is tuned to produce very few false
 * positives on ordinary prose while reliably catching the three OTP formats most commonly
 * seen in bank SMS messages, app verification flows, and authentication emails:
 *
 *   1. A plain sequence of 4–8 digits            — e.g. `"983201"` or `"1234"`
 *   2. A six-digit code split by a dash or space  — e.g. `"123-456"` or `"123 456"`
 *   3. An alphanumeric code with an optional dash — e.g. `"AB-12345"` or `"G123456"`
 */
object HelperUtils {

    /**
     * Pre-compiled regular expression used to locate OTP codes within arbitrary text.
     *
     * The pattern is anchored with `\b` (word boundary) on both sides of each alternative
     * to prevent partial matches inside longer numbers such as phone numbers or timestamps.
     * It consists of three alternating groups:
     *
     *   - **Group 1** `\b(\d{4,8})\b`
     *     A standalone run of 4 to 8 digits. Covers the vast majority of numeric OTPs
     *     used by banking apps, two-factor auth services, and delivery notifications.
     *
     *   - **Group 2** `\b(\d{3}[-\s]\d{3})\b`
     *     Two 3-digit groups separated by a single hyphen or whitespace character.
     *     This matches the split format used by Google, Apple, and many banking apps
     *     (e.g. `"123-456"` or `"123 456"`).
     *
     *   - **Group 3** `\b([A-Za-z]{1,4}-?\d{3,8})\b`
     *     An alphanumeric code starting with 1–4 letters, an optional hyphen, then 3–8
     *     digits (e.g. Google's `"G-XXXXXX"` style or carrier codes like `"AA123456"`).
     *
     * Compiling once as a static field avoids the overhead of regex compilation on every
     * clipboard-change event, which can fire at high frequency.
     */
    private val OTP_PATTERN = Pattern.compile(
        "\\b(\\d{4,8})\\b|\\b(\\d{3}[-\\s]\\d{3})\\b|\\b([A-Za-z]{1,4}-?\\d{3,8})\\b"
    )

    /**
     * Returns `true` if [text] is likely to be an OTP (one-time password) message.
     *
     * Three lightweight rejection checks are evaluated before the regex engine is invoked:
     *  - A `null` or blank input is never an OTP.
     *  - Strings longer than 100 characters are rejected. Real OTP delivery messages are
     *    concise; anything longer almost certainly represents a copied article or URL.
     *  - The compiled [OTP_PATTERN] must find at least one match within the string.
     *
     * @param text The clipboard snippet or notification body to evaluate.
     * @return `true` if the text passes all heuristics and contains an OTP-like token,
     *         `false` otherwise.
     */
    fun isOTP(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        if (text.length > 100) return false  // OTP messages are always short
        return OTP_PATTERN.matcher(text).find()
    }

    /**
     * Scans [text] for the first OTP-like code and returns it as a raw string.
     *
     * Unlike [isOTP], this function accepts strings up to 300 characters so that slightly
     * wordier notification bodies (e.g. "Your verification code is 123456. Do not share.")
     * can still yield their code. Strings beyond 300 characters are rejected to avoid
     * unnecessary regex work on large clipboard pastes such as copied web pages.
     *
     * The returned value is the matched substring exactly as it appears in the source —
     * it may contain letters, hyphens, or spaces depending on which format was matched.
     * Callers that need only digits should post-process the result themselves.
     *
     * @param text The clipboard snippet or notification body to search.
     * @return The first matched OTP token, or `null` if no match was found or the input
     *         exceeded the 300-character length limit.
     */
    fun extractOTP(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        if (text.length > 300) return null  // skip excessively long strings

        val matcher = OTP_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }
}
