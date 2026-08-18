package com.bunty.clipsync

import android.content.Context
import android.util.Log

/**
 * Singleton service responsible for forwarding detected OTP codes from the Android device
 * to the paired Mac via the shared Firestore `notifications` collection.
 *
 * The end-to-end forwarding pipeline works as follows:
 *  1. A caller — typically [OTPListeningService] for SMS-sourced OTPs, or
 *     [EmailOTPListenerService] for email-sourced OTPs — invokes [notifyOTPDetected].
 *  2. The raw OTP string is encrypted with AES-256-GCM using the session key established
 *     during the pairing handshake. A fresh 12-byte IV is generated for every message.
 *  3. The encrypted payload, alongside metadata (pairing ID, device ID, device name, and a
 *     server-generated timestamp), is written as a new document to Firestore.
 *  4. The Mac's ClipSync app, which maintains a live snapshot listener on the same collection,
 *     picks up the document within milliseconds, decrypts it with the shared key, and either
 *     copies the OTP to the Mac clipboard or surfaces it in a notification.
 *
 * The encryption scheme intentionally mirrors [FirestoreManager]'s encrypt/decrypt logic so
 * the Mac can use a single decryption path for all payloads received from Firestore.
 */
object OTPNotificationService {

    private const val TAG = "OTPNotificationService"

    /**
     * Encrypts the given [otpCode] and writes it to the Firestore `notifications` collection
     * so the paired Mac can receive it in near-real-time.
     *
     * The document written to Firestore has the following structure:
     * ```json
     * {
     *   "type":             "OTP_NOTIFICATION",
     *   "encryptedOTP":     "<Base64-AES-256-GCM ciphertext with prepended IV>",
     *   "pairingId":        "<current pairing document ID>",
     *   "sourceDeviceId":   "<stable Android device identifier>",
     *   "sourceDeviceName": "<human-readable device name>",
     *   "timestamp":        "<Firestore server timestamp>"
     * }
     * ```
     *
     * If no pairing ID is stored (i.e. the device has not yet completed pairing), the method
     * logs an error and returns immediately without writing anything to Firestore.
     *
     * @param context  Application context used to look up pairing and device metadata.
     * @param otpCode  The plain-text OTP string to encrypt and forward (e.g. `"847291"`).
     */
    fun notifyOTPDetected(context: Context, otpCode: String) {
        val appContext = context.applicationContext

        try {
            val pairingId  = DeviceManager.getPairingId(appContext)
            val deviceId   = DeviceManager.getDeviceId(appContext)
            val deviceName = DeviceManager.getAndroidDeviceName()

            // Without a valid pairing ID the Mac cannot match this document to an active session,
            // so there is no point writing to Firestore.
            if (pairingId == null) {
                Log.e(TAG, "No pairing ID found - cannot send OTP notification")
                return
            }

            // Encrypt the OTP before it leaves the device; the Mac decrypts it with the shared key.
            val encryptedOTP = encryptOTP(appContext, otpCode)

            val notificationData = hashMapOf<String, Any>(
                "type"             to "OTP_NOTIFICATION",
                "encryptedOTP"     to encryptedOTP,
                "pairingId"        to pairingId,
                "sourceDeviceId"   to deviceId,
                "sourceDeviceName" to deviceName,
                "timestamp"        to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            FirestoreManager.getDb(appContext).collection("notifications")
                .add(notificationData)
                .addOnSuccessListener { documentReference ->
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to send OTP notification", exception)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending OTP notification: ${e.message}", e)
        }
    }

    /**
     * Encrypts [otpCode] using AES-256-GCM and returns the result as a Base64-encoded string.
     *
     * A cryptographically-random 12-byte IV is generated on every call to guarantee semantic
     * security — encrypting the same OTP twice will never produce identical ciphertext.
     *
     * Binary layout of the output (before Base64 encoding):
     * ```
     * [ 12 bytes : IV ] [ N bytes : AES-GCM ciphertext ] [ 16 bytes : GCM authentication tag ]
     * ```
     * Prepending the IV to the ciphertext lets the recipient extract it at decryption time
     * without requiring a separate transmission channel.
     *
     * If encryption fails for any reason (e.g. a malformed stored key), the method logs the
     * error and returns the plain-text OTP as a last resort so the user is not silently blocked
     * from receiving their code. This fallback path should never be reached in production.
     *
     * @param context  Application context used to retrieve the AES session key via [DeviceManager].
     * @param otpCode  The plain-text OTP to encrypt.
     * @return         A Base64 (NO_WRAP) string encoding `[IV][ciphertext+GCM tag]`.
     */
    private fun encryptOTP(context: Context, otpCode: String): String {
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(
                hexStringToByteArray(DeviceManager.getEncryptionKey(context) ?: ""), "AES"
            )
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

            // A unique IV must be used for every encryption operation. Reusing an IV with the
            // same key would catastrophically break GCM's confidentiality and integrity guarantees.
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))

            val ciphertext = cipher.doFinal(otpCode.toByteArray(Charsets.UTF_8))

            // Concatenate IV and ciphertext (which already contains the 16-byte GCM auth tag)
            // into a single buffer so both travel together as one Base64 string.
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv,         0, combined, 0,       iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "OTP encryption failed - sending plaintext as fallback", e)
            otpCode  // Last-resort fallback; the Mac will still receive a usable OTP value.
        }
    }

    /**
     * Converts a hex-encoded string into its raw [ByteArray] representation.
     *
     * Every pair of hex characters maps to one byte. For example, the input `"4A2F"` produces
     * the two-byte array `[0x4A, 0x2F]`. This helper is used to decode the hex-encoded AES
     * session key returned by [DeviceManager] into the raw bytes required by [javax.crypto].
     *
     * @param s  A hex string whose length must be even and whose characters must all be valid
     *           hexadecimal digits (`0–9`, `A–F`, `a–f`).
     * @throws IllegalArgumentException if [s] has an odd number of characters or contains a
     *         character that is not a valid hexadecimal digit.
     */
    private fun hexStringToByteArray(s: String): ByteArray {
        require(s.length % 2 == 0) { "Invalid hex length" }
        val data = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val high = Character.digit(s[i],     16)
            val low  = Character.digit(s[i + 1], 16)
            require(high != -1 && low != -1) { "Invalid hex character" }
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }
}
