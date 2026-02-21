package com.bunty.clipsync

import android.content.Context
import android.util.Log

/**
 * OTPNotificationService handles sending detected OTP codes to the paired Mac via Firestore.
 *
 * When an OTP is detected (by [OTPListeningService] from SMS or [EmailOTPListenerService]
 * from email notifications), it is:
 * 1. Encrypted with AES-256-GCM using the pairing session key.
 * 2. Written to the `notifications` Firestore collection as a document of type `"OTP_NOTIFICATION"`.
 * 3. Read and displayed by the Mac's ClipSync app in near-real-time.
 *
 * All cryptography mirrors [FirestoreManager]'s encryption so both sides can interoperate.
 */
object OTPNotificationService {

    private const val TAG = "OTPNotificationService"

    /**
     * Encrypts [otpCode] and writes it to the `notifications` Firestore collection.
     *
     * The document schema written to Firestore:
     * ```
     * {
     *   type:             "OTP_NOTIFICATION",
     *   encryptedOTP:     "<base64-AES-GCM-ciphertext>",
     *   pairingId:        "<current-pairing-doc-id>",
     *   sourceDeviceId:   "<android-device-id>",
     *   sourceDeviceName: "<friendly-device-name>",
     *   timestamp:        <server-timestamp>
     * }
     * ```
     *
     * Does nothing if no pairing ID is currently stored.
     *
     * @param context Application context (used to retrieve pairing/device info).
     * @param otpCode The plain-text OTP code to send (e.g. `"123456"`).
     */
    fun notifyOTPDetected(context: Context, otpCode: String) {
        val appContext = context.applicationContext

        try {
            val pairingId  = DeviceManager.getPairingId(appContext)
            val deviceId   = DeviceManager.getDeviceId(appContext)
            val deviceName = DeviceManager.getAndroidDeviceName()

            if (pairingId == null) {
                Log.e(TAG, "No pairing ID found - cannot send OTP notification")
                return
            }

            // Encrypt the OTP before sending it over the wire
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
                    Log.d(TAG, "OTP notification sent successfully: ${documentReference.id}")
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to send OTP notification", exception)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending OTP notification: ${e.message}", e)
        }
    }

    /**
     * Encrypts [otpCode] using AES-256-GCM and returns a Base64-encoded ciphertext.
     *
     * A fresh random 12-byte IV is generated for every call. The output format
     * (after Base64-decoding) is: `[12-byte IV][ciphertext + 16-byte GCM auth tag]`.
     *
     * Falls back to returning [otpCode] as plain text if encryption fails (logged as an error).
     *
     * @param context Application context — used to retrieve the AES key via [DeviceManager].
     * @param otpCode The plain-text OTP to encrypt.
     * @return Base64-encoded (NO_WRAP) encrypted string.
     */
    private fun encryptOTP(context: Context, otpCode: String): String {
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(
                hexStringToByteArray(DeviceManager.getEncryptionKey(context)), "AES"
            )
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

            // Generate a unique IV for every encryption call (semantic security)
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))

            val ciphertext = cipher.doFinal(otpCode.toByteArray(Charsets.UTF_8))

            // Layout: [IV (12 bytes)] + [ciphertext + GCM tag (16 bytes)]
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv,         0, combined, 0,       iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "OTP encryption failed - sending plaintext as fallback", e)
            otpCode  // last-resort fallback
        }
    }

    /**
     * Converts a hex-encoded string to a raw [ByteArray].
     * Validates that the string length is even and that all characters are valid hex digits.
     *
     * @param s A hex string with an even number of characters (e.g. `"4A2F..."`).
     * @throws IllegalArgumentException if the string has an odd length or invalid characters.
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
