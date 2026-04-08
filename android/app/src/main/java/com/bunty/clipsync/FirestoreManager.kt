package com.bunty.clipsync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject

/**
 * FirestoreManager is the single entry-point for every Firestore database operation
 * in ClipSync. All reads and writes pass through this object so that Firestore-specific
 * details (collection names, field names, encryption) are contained in one place.
 *
 * **Responsibilities:**
 * - Selecting the correct Firebase project instance (US or IN) based on the region
 *   stored in [DeviceManager].
 * - Parsing the JSON payload embedded in the Mac app's pairing QR code.
 * - Creating and deleting pairing documents in the `pairings` collection.
 * - Encrypting outgoing clipboard text and decrypting incoming clipboard text using
 *   AES-256-GCM with the session key exchanged during pairing.
 * - Writing encrypted clipboard items to the `clipboardItems` collection and listening
 *   for new items from the Mac via a real-time snapshot listener.
 * - Batch-deleting all clipboard items for a pairing (cloud clipboard clear).
 */
object FirestoreManager {

    /**
     * Returns the [FirebaseFirestore] instance for the target region stored in [DeviceManager].
     *
     * ClipSync maintains two separate Firebase projects to reduce latency for users in
     * different geographies:
     * - `"US"` → the named secondary app `"ClipSyncUS"` initialised in [ClipSyncApp].
     * - `"IN"` (or any other value) → the default app configured by `google-services.json`,
     *   which points to the India Firestore region.
     *
     * If the US app has not yet been initialised (e.g. called before [ClipSyncApp.onCreate]),
     * this function logs a warning and falls back to the default instance so that no
     * operation is silently lost.
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
            // Default Firebase app targets the India Firestore region via google-services.json.
            FirebaseFirestore.getInstance()
        }
    }

    /**
     * Retrieves the AES-256 encryption key (hex string) for the current pairing session.
     *
     * Delegates to [DeviceManager.getEncryptionKey], which returns the key stored during
     * the last successful QR scan or falls back to [Secrets.FALLBACK_ENCRYPTION_KEY] if
     * no key has been persisted yet.
     */
    private fun getSharedSecret(context: Context): String =
        DeviceManager.getEncryptionKey(context)

    /**
     * Parses the raw JSON string embedded in the Mac app's pairing QR code into a typed map.
     *
     * The Mac encodes pairing metadata as a JSON object. This function extracts the fields
     * that the Android app needs to create a pairing document and establish encryption.
     *
     * **Expected JSON fields:**
     * | Field | Aliases | Description |
     * |---|---|---|
     * | `macId` | — | Unique stable identifier of the Mac. |
     * | `deviceName` | `macDeviceName` | Human-readable Mac display name. |
     * | `server` | `serverRegion` | Firestore region (`"IN"` or `"US"`). |
     * | `secret` | — | 64-char hex AES-256 session key. |
     *
     * @param qrData The raw string decoded from the QR code image.
     * @return A map containing `macDeviceId`, `macDeviceName`, `serverRegion`, and `secret`,
     *         or `null` if [qrData] is not valid JSON or `macId` is missing.
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
     * Decrypts a Base64-encoded AES-256-GCM ciphertext back to its original plain-text string.
     *
     * The expected binary layout of the decoded bytes is:
     * ```
     * [ 12-byte random IV ][ ciphertext ][ 16-byte GCM authentication tag ]
     * ```
     * The GCM tag is appended to the ciphertext by the JCE provider automatically and is
     * verified during decryption — any tampering with the payload will throw an exception.
     *
     * @param context         Used to retrieve the shared AES key from [DeviceManager].
     * @param encryptedBase64 Base64-encoded (NO_WRAP) encrypted payload produced by the Mac app.
     * @return The decrypted plain-text string, or an empty string if the payload is too short.
     * @throws Exception If decryption fails due to an incorrect key, corrupted data, or a
     *                   failed GCM tag authentication.
     */
    private fun decryptData(context: Context, encryptedBase64: String): String {
        val encryptedBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        if (encryptedBytes.size < 28) return "" // minimum valid size is 12 (IV) + 16 (GCM tag) bytes

        val keySpec = javax.crypto.spec.SecretKeySpec(hexStringToByteArray(getSharedSecret(context)), "AES")

        // Split the decoded bytes: the first 12 bytes are the IV, the remainder is ciphertext + GCM tag.
        val iv         = encryptedBytes.copyOfRange(0, 12)
        val ciphertext = encryptedBytes.copyOfRange(12, encryptedBytes.size)

        val cipher  = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))

        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Encrypts a plain-text string using AES-256-GCM and returns a Base64-encoded result.
     *
     * A fresh cryptographically random 12-byte IV is generated for every invocation using
     * [java.security.SecureRandom], ensuring that encrypting the same plaintext twice
     * produces different ciphertexts (semantic security / IND-CPA).
     *
     * **Output binary layout** (after Base64 decoding):
     * ```
     * [ 12-byte random IV ][ ciphertext ][ 16-byte GCM authentication tag ]
     * ```
     *
     * If encryption fails for any reason, the function logs an error and returns `null`
     * so the caller can decide how to handle the failure based on the user's
     * [DeviceManager.getEncryptionFailurePolicy] setting. This prevents sensitive data
     * from being silently sent as plaintext to Firestore.
     *
     * @param context   Used to retrieve the shared AES key from [DeviceManager].
     * @param plainText The clipboard text to encrypt.
     * @return Base64-encoded (NO_WRAP) encrypted payload ready to be stored in Firestore,
     *         or `null` if encryption failed.
     */
    private fun encryptData(context: Context, plainText: String): String? {
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(hexStringToByteArray(getSharedSecret(context)), "AES")
            val cipher  = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

            // A fresh random IV is generated for every call, ensuring ciphertext uniqueness.
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))

            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // Concatenate IV + ciphertext into a single byte array, then Base64-encode it.
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Encryption failed", e)
            null
        }
    }

    /**
     * Converts a lowercase or uppercase hex string (e.g. `"4a2f3c..."`) to a raw [ByteArray].
     *
     * Each consecutive pair of hex characters is converted to one byte. The input length
     * must be even; passing an odd-length string will produce a truncated result.
     *
     * @param s A hex-encoded string (case-insensitive).
     * @return A [ByteArray] whose length is `s.length / 2`.
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
     * Creates a new pairing document in the Firestore `pairings` collection, establishing
     * a link between this Android device and the scanned Mac.
     *
     * **Pre-step:** If [DeviceManager.getPairingId] returns an existing document ID, that
     * document is deleted first to prevent orphaned pairing records accumulating in the cloud.
     * The new document is created whether or not the deletion succeeds.
     *
     * **Post-step:** On a successful write, [DeviceManager.savePairing] is called to persist
     * the new pairing locally, and the auto-generated Firestore document ID is written back
     * into the document itself under the `pairingId` field for easy querying from the Mac.
     *
     * @param context   Application context.
     * @param qrData    The parsed QR data map returned by [parseQRData].
     * @param onSuccess Called with the new Firestore document ID once the pairing is saved.
     * @param onFailure Called with the exception if the Firestore write fails.
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

        // Persist the session encryption key before writing to Firestore so that the
        // first outgoing clipboard item is encrypted with the correct key.
        if (!secret.isNullOrEmpty()) {
            DeviceManager.saveEncryptionKey(context, secret)
        }

        val pairingData = hashMapOf<String, Any>(
            "androidDeviceId"   to androidDeviceId,
            "androidDeviceName" to androidDeviceName,
            "macDeviceId"       to macDeviceId,
            "macId"             to macDeviceId,  // legacy alias kept for Mac-side compatibility
            "macDeviceName"     to macDeviceName,
            "createdAt"         to System.currentTimeMillis(),
            "timestamp"         to com.google.firebase.Timestamp.now(),
            "status"            to "active"
        )

        /** Writes [pairingData] to the `pairings` collection and invokes the result callbacks. */
        fun createNewPairing() {
            getDb(context).collection("pairings")
                .add(pairingData)
                .addOnSuccessListener { documentReference ->
                    val pairingId = documentReference.id
                    // Write the auto-generated document ID back into the document itself so
                    // the Mac can retrieve the pairing ID without knowing it in advance.
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

        // Delete the stale pairing document first; new document creation proceeds
        // regardless of whether the deletion succeeds, to avoid blocking the user.
        val oldPairingId = DeviceManager.getPairingId(context)
        if (oldPairingId != null) {
            getDb(context).collection("pairings").document(oldPairingId).delete()
                .addOnSuccessListener  { createNewPairing() }
                .addOnFailureListener  { createNewPairing() } // always attempt creation
        } else {
            createNewPairing()
        }
    }

    /**
     * Attaches a Firestore real-time snapshot listener to the `clipboardItems` collection,
     * filtered to the current pairing and sorted newest-first with a limit of 1.
     *
     * Each time a new document lands in the collection the listener fires. Items whose
     * `sourceDeviceId` matches this Android device are silently ignored to prevent the
     * device from processing its own uploads. All other items are decrypted via [decryptData]
     * before being forwarded to [onClipboardUpdate].
     *
     * The returned [ListenerRegistration] **must** be removed (via `remove()`) when the
     * owning component is destroyed to stop background Firestore reads and avoid memory leaks.
     *
     * @param context           Application context.
     * @param onClipboardUpdate Callback invoked on the main thread with the decrypted text.
     * @return A [ListenerRegistration] for lifecycle management, or `null` if no pairing ID
     *         is available (device is not paired).
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

                    // Ignore items that this Android device uploaded — only handle Mac-originated content.
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
     * Encrypts [text] with [encryptData] and writes it to the `clipboardItems` Firestore
     * collection as a new document.
     *
     * If encryption fails, the behaviour depends on the user's encryption failure policy
     * (see [DeviceManager.getEncryptionFailurePolicy]):
     * - **never_allow** (default): the sync is skipped and [onFailure] is called. This
     *   prevents sensitive clipboard data from being stored as plaintext in Firestore.
     * - **always_allow**: the plaintext is sent as a last resort (the user has explicitly
     *   opted in to this risk).
     *
     * @param context   Application context.
     * @param text      The plain-text clipboard content to encrypt and send.
     * @param onSuccess Called when the document has been successfully written to Firestore.
     * @param onFailure Called with the exception if the Firestore write fails.
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

        // Encryption failed — decide what to do based on user policy.
        if (encryptedContent == null) {
            val policy = DeviceManager.getEncryptionFailurePolicy(context)
            if (policy == DeviceManager.ENCRYPTION_POLICY_ALWAYS_ALLOW) {
                Log.w("FirestoreManager", "Encryption failed — sending plaintext (user policy: always_allow)")
            } else {
                Log.e("FirestoreManager", "Encryption failed — sync skipped (user policy: $policy)")
                onFailure(Exception("Encryption failed and policy does not allow plaintext fallback"))
                return
            }
        }

        val contentToSend = encryptedContent ?: text

        val clipboardData = hashMapOf<String, Any>(
            "content"        to contentToSend,
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
     * Deletes the pairing document from Firestore and then clears the locally persisted
     * pairing state via [DeviceManager.clearPairing].
     *
     * This is the counterpart to [createPairing] and is called when the user initiates
     * a "Reset Pairing" action in [Homescreen]. After this call completes successfully the
     * device is in an unpaired state and must scan a new QR code to re-pair.
     *
     * @param context   Application context.
     * @param onSuccess Called after the Firestore document is deleted and local state is cleared.
     * @param onFailure Called with the exception if the Firestore delete operation fails.
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
                DeviceManager.clearPairing(context) // clear local SharedPreferences after cloud deletion
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Log.e("FirestoreManager", "Failed to clear pairing", exception)
                onFailure(exception)
            }
    }

    /**
     * Batch-deletes every document in the `clipboardItems` collection that belongs to the
     * current pairing.
     *
     * The operation runs in two steps: first a query fetches all matching documents,
     * then a single [com.google.firebase.firestore.WriteBatch] deletes them atomically.
     * Using a batch minimises the number of Firestore round trips and ensures all items
     * are deleted together.
     *
     * This is invoked by the "Clear Cloud Clipboard" action in [Homescreen].
     *
     * @param context   Application context.
     * @param onSuccess Called after all clipboard documents have been deleted.
     * @param onFailure Called if the query or the batch commit fails.
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

        // Fetch all clipboard items for this pairing, then delete them in a single batch write.
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
