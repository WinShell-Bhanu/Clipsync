package com.bunty.clipsync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject

/**
 * FirestoreManager is the single entry-point for all Firestore database operations.
 *
 * It handles:
 * - Selecting the correct Firebase project (US or IN) based on the stored region.
 * - Parsing the QR code JSON emitted by the Mac app.
 * - Creating and deleting pairing documents in the `pairings` collection.
 * - Sending and listening to encrypted clipboard items in the `clipboardItems` collection.
 * - Sending encrypted OTP notifications to the `notifications` collection.
 * - AES-256-GCM encryption and decryption of all clipboard content.
 */
object FirestoreManager {

    /**
     * Returns the [FirebaseFirestore] instance for the target region stored in [DeviceManager].
     *
     * - `"US"` → uses the named secondary Firebase app `"ClipSyncUS"` (initialised in [ClipSyncApp]).
     * - `"IN"` (or any other) → uses the default Firebase app from `google-services.json`.
     *
     * Falls back to the default instance if the US app hasn't been initialised yet.
     */
    internal fun getDb(context: Context): FirebaseFirestore {
        val targetRegion = DeviceManager.getTargetRegion(context)
        return if (targetRegion == RegionConfig.REGION_US) {
            try {
                FirebaseFirestore.getInstance(com.google.firebase.FirebaseApp.getInstance("ClipSyncUS"))
            } catch (e: Exception) {
                Log.e("FirestoreManager", "US App not initialized, falling back to default", e)
                FirebaseFirestore.getInstance()
            }
        } else {
            // Default instance targets the India Firestore region
            FirebaseFirestore.getInstance()
        }
    }

    /**
     * Returns the AES-256 encryption key (hex string) for the current pairing session.
     * Delegated to [DeviceManager] which falls back to [Secrets.FALLBACK_ENCRYPTION_KEY].
     */
    private fun getSharedSecret(context: Context): String =
        DeviceManager.getEncryptionKey(context)

    /**
     * Parses the raw JSON string embedded in the Mac's QR code into a typed map.
     *
     * Expected JSON fields:
     * - `macId` / `macDeviceId` – unique identifier of the Mac.
     * - `deviceName` / `macDeviceName` – display name of the Mac.
     * - `server` / `serverRegion` – Firestore region to use (`"IN"` or `"US"`).
     * - `secret` – the 64-char hex AES-256 encryption key.
     *
     * @param qrData The raw string decoded from the QR code image.
     * @return A map of the parsed fields, or `null` if the JSON is invalid or `macId` is missing.
     */
    fun parseQRData(qrData: String): Map<String, Any>? {
        return try {
            if (qrData.trim().startsWith("{")) {
                val jsonObject = JSONObject(qrData)
                val macId = jsonObject.optString("macId")
                val deviceName = jsonObject.optString("deviceName").ifEmpty {
                    jsonObject.optString("macDeviceName", "Mac")
                }
                val secret = jsonObject.optString("secret")

                if (macId.isNotEmpty()) {
                    mapOf(
                        "macDeviceId"  to macId,
                        "macDeviceName" to deviceName,
                        "serverRegion" to jsonObject.optString("server").ifEmpty {
                            jsonObject.optString("serverRegion", "IN")
                        },
                        "secret" to secret
                    )
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypts a Base64-encoded AES-256-GCM ciphertext back to a plain-text string.
     *
     * Format of [encryptedBase64] after Base64-decoding:
     * `[12-byte IV][ciphertext + 16-byte GCM auth tag]`
     *
     * @param context       Used to retrieve the shared AES key from [DeviceManager].
     * @param encryptedBase64 Base64-encoded (NO_WRAP) encrypted payload.
     * @return The decrypted plain-text string.
     * @throws Exception if decryption fails (bad key, corrupted ciphertext, etc.).
     */
    private fun decryptData(context: Context, encryptedBase64: String): String {
        val encryptedBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        if (encryptedBytes.size < 28) return ""  // too short to be valid (12 IV + 16 tag minimum)

        val keySpec = javax.crypto.spec.SecretKeySpec(hexStringToByteArray(getSharedSecret(context)), "AES")

        // First 12 bytes are the random IV; the rest is ciphertext + GCM auth tag
        val iv         = encryptedBytes.copyOfRange(0, 12)
        val ciphertext = encryptedBytes.copyOfRange(12, encryptedBytes.size)

        val cipher  = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))

        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Encrypts a plain-text string using AES-256-GCM and returns a Base64-encoded result.
     *
     * A fresh random 12-byte IV is generated for every call, so the same plaintext
     * produces a different ciphertext each time (semantic security).
     *
     * Output format (after Base64 decoding): `[12-byte IV][ciphertext + 16-byte GCM auth tag]`
     *
     * Falls back to returning [plainText] unencrypted on error (logged as an error).
     *
     * @param context   Used to retrieve the shared AES key.
     * @param plainText The text to encrypt.
     * @return Base64-encoded (NO_WRAP) encrypted payload.
     */
    private fun encryptData(context: Context, plainText: String): String {
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(hexStringToByteArray(getSharedSecret(context)), "AES")
            val cipher  = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

            // Generate a fresh random IV for every encryption call
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))

            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // Concatenate IV + ciphertext and Base64-encode the result
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Encryption failed", e)
            plainText  // last-resort fallback: send unencrypted
        }
    }

    /**
     * Converts a hex string (e.g. `"4A2F..."`) to a raw [ByteArray].
     * Each pair of hex characters maps to one byte.
     */
    private fun hexStringToByteArray(s: String): ByteArray {
        val data = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Creates a new pairing document in the Firestore `pairings` collection.
     *
     * If a previous pairing document exists (stored in [DeviceManager.getPairingId]),
     * it is deleted first to avoid orphaned records in the cloud.
     *
     * On success, [DeviceManager.savePairing] is called to persist the pairing locally,
     * and [onSuccess] is invoked with the new pairing document ID.
     *
     * @param context   Application context.
     * @param qrData    The parsed QR data map from [parseQRData].
     * @param onSuccess Called with the new Firestore document ID on success.
     * @param onFailure Called with the exception on failure.
     */
    fun createPairing(
        context: Context,
        qrData: Map<String, Any>,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val androidDeviceId   = DeviceManager.getDeviceId(context)
        val androidDeviceName = DeviceManager.getAndroidDeviceName()
        val macDeviceId       = qrData["macDeviceId"] as? String ?: ""
        val macDeviceName     = qrData["macDeviceName"] as? String ?: "Mac"
        val secret            = qrData["secret"] as? String

        // Persist the encryption key received from the QR code before writing to Firestore
        if (!secret.isNullOrEmpty()) {
            DeviceManager.saveEncryptionKey(context, secret)
        }

        val pairingData = hashMapOf<String, Any>(
            "androidDeviceId"   to androidDeviceId,
            "androidDeviceName" to androidDeviceName,
            "macDeviceId"       to macDeviceId,
            "macId"             to macDeviceId,  // legacy field kept for Mac-side compatibility
            "macDeviceName"     to macDeviceName,
            "createdAt"         to System.currentTimeMillis(),
            "timestamp"         to com.google.firebase.Timestamp.now(),
            "status"            to "active"
        )

        /** Writes [pairingData] to Firestore and handles the result callbacks. */
        fun createNewPairing() {
            getDb(context).collection("pairings")
                .add(pairingData)
                .addOnSuccessListener { documentReference ->
                    val pairingId = documentReference.id
                    // Write the auto-generated document ID back into the document itself
                    documentReference.update("pairingId", pairingId)

                    DeviceManager.savePairing(
                        context       = context,
                        pairingId     = pairingId,
                        macDeviceId   = macDeviceId,
                        macDeviceName = macDeviceName
                    )

                    onSuccess(pairingId)
                }
                .addOnFailureListener { exception ->
                    Log.e("FirestoreManager", "Failed to create pairing", exception)
                    onFailure(exception)
                }
        }

        // Delete the old pairing document before creating a new one
        val oldPairingId = DeviceManager.getPairingId(context)
        if (oldPairingId != null) {
            getDb(context).collection("pairings").document(oldPairingId).delete()
                .addOnSuccessListener  { createNewPairing() }
                .addOnFailureListener  { createNewPairing() }  // proceed even if delete fails
        } else {
            createNewPairing()
        }
    }

    /**
     * Attaches a real-time Firestore listener to the `clipboardItems` collection,
     * filtered to the current pairing and ordered newest-first.
     *
     * When a new item arrives from the Mac:
     * - Items sent by this Android device are ignored (source device ID check).
     * - The content is decrypted with [decryptData] before being passed to [onClipboardUpdate].
     *
     * @param context           Application context.
     * @param onClipboardUpdate Called on the main thread with the decrypted clipboard text.
     * @return A [ListenerRegistration] that must be removed when the observer is destroyed,
     *         or `null` if no pairing ID is available.
     */
    fun listenToClipboard(
        context: Context,
        onClipboardUpdate: (String) -> Unit
    ): ListenerRegistration? {
        val pairingId       = DeviceManager.getPairingId(context) ?: return null
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
                    val sourceDeviceId   = document.getString("sourceDeviceId")

                    // Only process items that came from the Mac, not from this device
                    if (encryptedContent != null && sourceDeviceId != currentDeviceId) {
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

    /**
     * Encrypts [text] and writes it to the `clipboardItems` Firestore collection.
     *
     * The document includes the pairing ID, source device ID, and a server timestamp
     * so the Mac can query only items newer than its last-seen timestamp.
     *
     * @param context   Application context.
     * @param text      The plain-text clipboard content to send.
     * @param onSuccess Called when the document is written successfully.
     * @param onFailure Called with the exception if the write fails.
     */
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

        val encryptedContent = encryptData(context, text)

        val clipboardData = hashMapOf<String, Any>(
            "content"        to encryptedContent,
            "pairingId"      to pairingId,
            "sourceDeviceId" to DeviceManager.getDeviceId(context),
            "timestamp"      to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "type"           to "text"
        )

        getDb(context).collection("clipboardItems")
            .add(clipboardData)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { exception ->
                Log.e("FirestoreManager", "Failed to send clipboard", exception)
                onFailure(exception)
            }
    }

    /**
     * Deletes the pairing document from Firestore, then clears the local pairing state
     * via [DeviceManager.clearPairing].
     *
     * Called when the user taps "Reset Pairing" in [Homescreen].
     *
     * @param onSuccess Called after the Firestore document is deleted and local state is cleared.
     * @param onFailure Called with the exception if the Firestore delete fails.
     */
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
                DeviceManager.clearPairing(context)  // also clear local SharedPreferences
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Log.e("FirestoreManager", "Failed to clear pairing", exception)
                onFailure(exception)
            }
    }

    /**
     * Batch-deletes all `clipboardItems` documents for the current pairing.
     *
     * Used by the "Clear Cloud Clipboard" action button in [Homescreen].
     *
     * @param onSuccess Called after all documents are deleted.
     * @param onFailure Called if the query or batch commit fails.
     */
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

        // Fetch all clipboard items for this pairing, then delete them in a single batch
        getDb(context).collection("clipboardItems")
            .whereEqualTo("pairingId", pairingId)
            .get()
            .addOnSuccessListener { snapshot ->
                val batch = getDb(context).batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit()
                    .addOnSuccessListener { onSuccess() }
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
