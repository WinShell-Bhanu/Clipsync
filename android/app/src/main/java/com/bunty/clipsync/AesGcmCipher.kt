package com.bunty.clipsync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM symmetric encryption helper for the local TCP sync path.
 *
 * Wire format (encrypted blob):
 *   [ 12-byte random IV ][ ciphertext + 16-byte GCM auth tag ]
 *
 * This format is intentionally identical to [FirestoreManager]'s scheme so that
 * the same hex key can be used for both cloud and local-sync payloads.
 */
object AesGcmCipher {

    private const val IV_SIZE_BYTES  = 12
    private const val TAG_SIZE_BITS  = 128
    private const val ALGORITHM      = "AES/GCM/NoPadding"

    // ── Encrypt ───────────────────────────────────────────────────────────────

    /**
     * Encrypts [plaintext] using AES-256-GCM.
     *
     * A fresh 12-byte IV is generated via [SecureRandom] for every call, so
     * encrypting identical plaintexts twice produces different ciphertexts.
     *
     * @param plaintext Raw bytes to encrypt.
     * @param hexKey    64-character lower/upper-case hex string (256-bit key).
     * @return IV-prefixed ciphertext ready to be streamed over TCP.
     * @throws Exception If the key is invalid or encryption fails.
     */
    fun encrypt(plaintext: ByteArray, hexKey: String): ByteArray {
        val keySpec = SecretKeySpec(hexKey.hexToBytes(), "AES")
        val iv      = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, iv))

        val ciphertext = cipher.doFinal(plaintext)
        // Prepend IV so the receiver can split it out without side-channel
        return iv + ciphertext
    }

    /**
     * Encrypts [plaintext] string (UTF-8) and returns IV-prefixed ciphertext.
     */
    fun encrypt(plaintext: String, hexKey: String): ByteArray =
        encrypt(plaintext.toByteArray(Charsets.UTF_8), hexKey)

    // ── Decrypt ───────────────────────────────────────────────────────────────

    /**
     * Decrypts [data] produced by [encrypt].
     *
     * The first 12 bytes are extracted as the IV; the remainder is decrypted
     * and the 16-byte GCM authentication tag is verified automatically. Any
     * tampering will throw [javax.crypto.AEADBadTagException].
     *
     * @param data   IV-prefixed ciphertext returned by [encrypt].
     * @param hexKey 64-character hex AES-256 key.
     * @return Decrypted plaintext bytes.
     */
    fun decrypt(data: ByteArray, hexKey: String): ByteArray {
        require(data.size > IV_SIZE_BYTES) { "Ciphertext too short (${data.size} bytes)" }

        val keySpec    = SecretKeySpec(hexKey.hexToBytes(), "AES")
        val iv         = data.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = data.copyOfRange(IV_SIZE_BYTES, data.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Decrypts [data] and returns the result as a UTF-8 string.
     */
    fun decryptToString(data: ByteArray, hexKey: String): String =
        decrypt(data, hexKey).toString(Charsets.UTF_8)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.hexToBytes(): ByteArray {
        val s = this.lowercase()
        require(s.length % 2 == 0) { "Hex string length must be even (was ${s.length})" }
        return ByteArray(s.length / 2) { i ->
            val hi = s[i * 2].digitToInt(16)
            val lo = s[i * 2 + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
