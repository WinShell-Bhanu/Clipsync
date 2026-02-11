package com.bunty.clipsync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject

object FirestoreManager {
    // Dynamic DB Access based on selected region
    internal fun getDb(context: Context): FirebaseFirestore {
        val targetRegion = DeviceManager.getTargetRegion(context)
        return if (targetRegion == RegionConfig.REGION_US) {
            // Use the Named App for US
            try {
                FirebaseFirestore.getInstance(com.google.firebase.FirebaseApp.getInstance("ClipSyncUS"))
            } catch (e: Exception) {
                Log.e("FirestoreManager", "US App not initialized, falling back to default", e)
                FirebaseFirestore.getInstance()
            }
        } else {
            // Use Default App for India
            FirebaseFirestore.getInstance()
        }
    }

    // Dynamic Secret Retrieval
    private fun getSharedSecret(context: Context): String {
        return DeviceManager.getEncryptionKey(context)
    }

    // --- QR Parsing Strategy ---
    // Supports:
    // 1. JSON (New V1 Format) - Contains {server, secret, macId}
    // 2. Legacy - Fallback for older versions
    fun parseQRData(qrData: String): Map<String, Any>? {
        try {
            if (qrData.trim().startsWith("{")) {
                val jsonObject = JSONObject(qrData)
                val macId = jsonObject.optString("macId")
                val deviceName = jsonObject.optString("deviceName").ifEmpty {
                    jsonObject.optString("macDeviceName", "Mac")
                }
                val secret = jsonObject.optString("secret") // Extract new key

                if (macId.isNotEmpty()) {
                    return mapOf(
                        "macDeviceId" to macId,
                        "macDeviceName" to deviceName,
                        "serverRegion" to jsonObject.optString("server").ifEmpty {
                            jsonObject.optString("serverRegion", "IN")
                        },
                        "secret" to secret
                    )
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    // --- Encryption Helpers (AES-GCM) ---
    // Uses the Dynamic Key (swapped via QR) to secure clipboard data.
    private fun decryptData(context: Context, encryptedBase64: String): String {
        try {
            val encryptedBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
            if (encryptedBytes.size < 28) return ""

            val keyBytes = hexStringToByteArray(getSharedSecret(context)) // Use Dynamic Key
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")

            val iv = encryptedBytes.copyOfRange(0, 12)
            val ciphertext = encryptedBytes.copyOfRange(12, encryptedBytes.size)

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)

            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val plaintextBytes = cipher.doFinal(ciphertext)

            return String(plaintextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw e
        }
    }

    // Encrypt AES-GCM -> Base64 String
    private fun encryptData(context: Context, plainText: String): String {
        try {
            val keyBytes = hexStringToByteArray(getSharedSecret(context)) // Use Dynamic Key
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12)
            java.security.SecureRandom().nextBytes(iv)
            
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Encryption failed", e)
            return plainText
        }
    }

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

    // --- Firestore Operations ---

    // Create a new pairing document
    fun createPairing(
        context: Context,
        qrData: Map<String, Any>,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val androidDeviceId = DeviceManager.getDeviceId(context)
        val androidDeviceName = DeviceManager.getAndroidDeviceName()
        val macDeviceId = qrData["macDeviceId"] as? String ?: ""
        val macDeviceName = qrData["macDeviceName"] as? String ?: "Mac"
        val secret = qrData["secret"] as? String
        
        if (!secret.isNullOrEmpty()) {
            DeviceManager.saveEncryptionKey(context, secret)
        }

        val pairingData = hashMapOf<String, Any>(
            "androidDeviceId" to androidDeviceId,
            "androidDeviceName" to androidDeviceName,
            "macDeviceId" to macDeviceId,
            "macId" to macDeviceId,
            "macDeviceName" to macDeviceName,
            "createdAt" to System.currentTimeMillis(),
            "timestamp" to com.google.firebase.Timestamp.now(),
            "status" to "active"
        )


        // Helper to actually create the NEW pairing
        fun createNewPairing() {
            getDb(context).collection("pairings")
                .add(pairingData)
                .addOnSuccessListener { documentReference ->
                    val pairingId = documentReference.id
                    documentReference.update("pairingId", pairingId)

                    DeviceManager.savePairing(
                        context = context,
                        pairingId = pairingId,
                        macDeviceId = macDeviceId,
                        macDeviceName = macDeviceName
                    )

                    onSuccess(pairingId)
                }
                .addOnFailureListener { exception ->
                    Log.e("FirestoreManager", "Failed to create pairing", exception)
                    onFailure(exception)
                }
        }

        val oldPairingId = DeviceManager.getPairingId(context)
        if (oldPairingId != null) {
            getDb(context).collection("pairings").document(oldPairingId).delete()
                .addOnSuccessListener {
                    createNewPairing()
                }
                .addOnFailureListener { e ->
                    createNewPairing()
                }
        } else {
            // No existing pairing, just create new one
            createNewPairing()
        }
    }

    // --- Clipboard Syncing ---

    // Listen for incoming clipboard changes (Decryption happens here)
    fun listenToClipboard(
        context: Context,
        onClipboardUpdate: (String) -> Unit
    ): ListenerRegistration? {
        val pairingId = DeviceManager.getPairingId(context) ?: return null
        val currentDeviceId = DeviceManager.getDeviceId(context)

        return getDb(context).collection("clipboardItems")
            .whereEqualTo("pairingId", pairingId)
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e("FirestoreManager", "Listen failed", error)
                    return@addSnapshotListener
                }

                snapshots?.documents?.firstOrNull()?.let { document ->
                    val encryptedContent = document.getString("content")
                    val sourceDeviceId = document.getString("sourceDeviceId")

                    // Only update if it came from a different device (Mac)
                    if (encryptedContent != null && sourceDeviceId != currentDeviceId) {
                         // DECRYPT HERE
                         try {
                             val decryptedContent = decryptData(context, encryptedContent)
                             if (decryptedContent.isNotEmpty()) {
                                 onClipboardUpdate(decryptedContent)
                             }
                         } catch (e: Exception) {
                             Log.e("FirestoreManager", "Failed to decrypt incoming clipboard", e)
                         }
                    }
                }
            }
    }

    // Send clipboard to Firestore (ENCRYPTING)
    fun sendClipboard(
        context: Context,
        text: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val pairingId = DeviceManager.getPairingId(context)
        if (pairingId == null) {
            onFailure(Exception("No pairing ID found"))
            return
        }
        
        val deviceId = DeviceManager.getDeviceId(context)

        // ENCRYPT HERE
        val encryptedContent = encryptData(context, text)

        val clipboardData = hashMapOf<String, Any>(
            "content" to encryptedContent,
            "pairingId" to pairingId,
            "sourceDeviceId" to deviceId,
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "type" to "text"
        )

        getDb(context).collection("clipboardItems")
            .add(clipboardData)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Log.e("FirestoreManager", "Failed to send clipboard", exception)
                onFailure(exception)
            }
    }

    fun clearPairing(
        context: Context,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val pairingId = DeviceManager.getPairingId(context) ?: return

        getDb(context).collection("pairings")
            .document(pairingId)
            .delete()
            .addOnSuccessListener {
                DeviceManager.clearPairing(context)
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Log.e("FirestoreManager", "Failed to clear pairing", exception)
                onFailure(exception)
            }
    }

    // Clear CLIPBOARD history for this pairing
    fun clearClipboard(
        context: Context,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val pairingId = DeviceManager.getPairingId(context)
        if (pairingId == null) {
            onFailure(Exception("No pairing ID found"))
            return
        }

        getDb(context).collection("clipboardItems")
            .whereEqualTo("pairingId", pairingId)
            .get()
            .addOnSuccessListener { snapshot ->
                val batch = getDb(context).batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit()
                    .addOnSuccessListener {
                        onSuccess()
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirestoreManager", "Failed to commit batch delete", e)
                        onFailure(e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreManager", "Failed to fetch clipboard items for deletion", e)
                onFailure(e)
            }
    }
}
