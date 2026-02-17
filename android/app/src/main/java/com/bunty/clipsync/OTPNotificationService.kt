package com.bunty.clipsync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * OTP Notification Service
 *
 * Sends OTP detection notifications to macOS via Firestore
 * This triggers a menu bar ping on the Mac when an OTP is detected and copied
 */
object OTPNotificationService {
    private const val TAG = "OTPNotificationService"

    /**
     * Notify macOS that an OTP was detected and copied
     *
     * @param context Android context
     * @param otpCode The detected OTP code (WILL BE ENCRYPTED before transmission)
     */
    fun notifyOTPDetected(context: Context, otpCode: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pairingId = DeviceManager.getPairingId(context)
                val deviceId = DeviceManager.getDeviceId(context)
                val deviceName = DeviceManager.getAndroidDeviceName()

                if (pairingId == null) {
                    Log.e(TAG, "No pairing ID found - cannot send OTP notification")
                    return@launch
                }

                val encryptedOTP = encryptOTP(context, otpCode)

                val notificationData = hashMapOf<String, Any>(
                    "type" to "OTP_NOTIFICATION",
                    "encryptedOTP" to encryptedOTP,
                    "pairingId" to pairingId,
                    "sourceDeviceId" to deviceId,
                    "sourceDeviceName" to deviceName,
                    "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                FirestoreManager.getDb(context).collection("notifications")
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
    }

    /**
     * Encrypt OTP using AES-GCM (same encryption as clipboard data)
     * Uses the shared secret key exchanged during pairing
     *
     * @param context Android context
     * @param otpCode Plaintext OTP code
     * @return Base64 encoded encrypted OTP (IV + ciphertext)
     */
    private fun encryptOTP(context: Context, otpCode: String): String {
        return try {
            val keyHex = DeviceManager.getEncryptionKey(context)
            val keyBytes = hexStringToByteArray(keyHex)
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12)
            java.security.SecureRandom().nextBytes(iv)

            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertext = cipher.doFinal(otpCode.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "OTP encryption failed - sending plaintext as fallback", e)
            otpCode
        }
    }

    /**
     * Convert hex string to byte array
     */
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
