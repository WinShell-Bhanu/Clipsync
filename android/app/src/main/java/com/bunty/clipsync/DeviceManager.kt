package com.bunty.clipsync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * DeviceManager is the single source of truth for all device-level and pairing-level
 * persistent state in ClipSync.
 *
 * All data is stored in a private [SharedPreferences] file named `clipsync_prefs` so
 * that the rest of the codebase never accesses [SharedPreferences] directly. Typed
 * getters and setters are exposed for every logical domain.
 *
 * **Domains managed by this object:**
 * - **Device identity** — A stable UUID-based device ID and a human-readable device name,
 *   both generated once on first launch and persisted forever.
 * - **Pairing state** — Whether the device is paired, the paired Mac's ID and display name,
 *   and the Firestore document ID of the active pairing record.
 * - **Sync direction toggles** — Independent boolean flags controlling whether clipboard
 *   content flows from Android → Mac and/or Mac → Android.
 * - **Firestore region** — The target Firebase project region (`"IN"` or `"US"`) selected
 *   based on the user's physical location or the Mac's QR code.
 * - **AES encryption key** — The 64-character hex session key exchanged during pairing,
 *   used by [FirestoreManager] to encrypt and decrypt all clipboard content.
 */
object DeviceManager {

    private const val PREFS_NAME = "clipsync_prefs"
    private const val ENCRYPTED_PREFS_NAME = "clipsync_prefs_secure"

    // Keys used to read and write individual values inside the clipsync_prefs file.
    // Each key is a stable string constant — changing any key is a breaking change that
    // would cause existing installs to lose their persisted data.
    private const val KEY_PAIRED             = "is_paired"
    private const val KEY_PAIRED_DEVICE_ID   = "paired_device_id"
    private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
    private const val KEY_PAIRING_ID         = "pairing_id"
    private const val KEY_ENCRYPTION_KEY     = "encryption_key"
    private const val KEY_ANDROID_DEVICE_ID  = "android_device_id"
    private const val KEY_ANDROID_DEVICE_NAME = "android_device_name"
    private const val KEY_SYNC_TO_MAC        = "sync_to_mac"
    private const val KEY_SYNC_FROM_MAC      = "sync_from_mac"
    private const val KEY_REGION             = "server_region"
    private const val KEY_AUTO_SYNC_OTPS     = "auto_sync_otps"
    private const val KEY_ENCRYPTION_FAILURE_POLICY = "encryption_failure_policy"

    /**
     * Policy values for [getEncryptionFailurePolicy]:
     * - [ENCRYPTION_POLICY_NEVER_ALLOW]: Never send unencrypted data (default — fail safe).
     * - [ENCRYPTION_POLICY_ALWAYS_ALLOW]: Always fall back to plaintext on encryption failure.
     */
    const val ENCRYPTION_POLICY_NEVER_ALLOW  = "never_allow"
    const val ENCRYPTION_POLICY_ALWAYS_ALLOW = "always_allow"

    /**
     * Stores the Firestore region that was active when Firebase was initialised in
     * [ClipSyncApp]. [FirestoreManager] reads this value to detect whether a freshly
     * scanned QR code requests a different region, which would require an app restart
     * to switch Firebase instances. Defaults to `"IN"` (India).
     */
    var initializedRegion: String = "IN"

    // ── Region helpers ────────────────────────────────────────────────────────

    /**
     * Returns the persisted Firestore target region code (`"IN"` or `"US"`).
     * Defaults to `"IN"` if no region has been saved yet (e.g. on first launch before
     * [LocationHelper] has determined the user's location).
     */
    fun getTargetRegion(context: Context): String =
        getPrefs(context).getString(KEY_REGION, "IN") ?: "IN"

    /**
     * Returns `true` if a target region has already been saved to [SharedPreferences].
     *
     * Used by [ClipSyncApp] and [LocationHelper] to determine whether the region-selection
     * step should be skipped on subsequent launches.
     */
    fun isRegionSet(context: Context): Boolean =
        getPrefs(context).contains(KEY_REGION)

    /**
     * Persists the Firestore target region, normalising the value to uppercase.
     *
     * Uses [SharedPreferences.Editor.commit] (synchronous) rather than `apply` because
     * [FirestoreManager.getDb] must be able to read the updated region value on the very
     * next call without waiting for an asynchronous disk write to complete.
     *
     * The write is skipped entirely if the new value equals the already-stored one to
     * avoid unnecessary disk I/O.
     *
     * @param region The region code to persist — typically `"IN"` or `"US"`.
     */
    fun setTargetRegion(context: Context, region: String) {
        val normalizedRegion = region.uppercase()
        // Skip the write entirely when the value hasn't changed to avoid unnecessary disk I/O.
        if (normalizedRegion != getTargetRegion(context)) {
            getPrefs(context).edit().putString(KEY_REGION, normalizedRegion).commit()
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Cached [SharedPreferences] singleton — M3 fix: avoid re-creating EncryptedSharedPreferences
     *  on every call, which is extremely expensive and causes ANRs in the AccessibilityService. */
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    private val prefsLock = Any()

    /**
     * Returns the [SharedPreferences] instance backed by the `clipsync_prefs` file.
     *
     * Uses [EncryptedSharedPreferences] backed by the Android Keystore so that both
     * the keys and values at rest are protected by a hardware-bound AES-256-GCM key.
     *
     * C2 fix: does NOT fall back to plain SharedPreferences. If the Keystore is
     * unavailable, the operation must fail — not silently downgrade to cleartext.
     *
     * C1 fix: on first creation, migrates data from the legacy plain-text prefs file
     * so existing users never lose their pairingId or encryption key during an update.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(prefsLock) {
            cachedPrefs?.let { return it }
            val encPrefs = try {
                createEncryptedPrefs(context)
            } catch (e: Exception) {
                // The encrypted prefs file is corrupted (e.g. restored from backup
                // without the Keystore key, or stale after reinstall). Delete the
                // broken file and create a fresh one so the app doesn't crash.
                Log.w("DeviceManager", "EncryptedSharedPreferences corrupted — resetting", e)
                context.getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().commit()
                val file = java.io.File(context.applicationInfo.dataDir,
                    "shared_prefs/$ENCRYPTED_PREFS_NAME.xml")
                file.delete()
                createEncryptedPrefs(context)
            }
            // C1: transparent migration from legacy plain-text SharedPreferences
            migrateFromPlainPrefs(context, encPrefs)
            cachedPrefs = encPrefs
            return encPrefs
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * C1: One-shot migration. Copies every known key from the old cleartext
     * `clipsync_prefs` (MODE_PRIVATE) into [encPrefs], then wipes the old file.
     * Idempotent: if the old file has no data, this is a noop.
     */
    private fun migrateFromPlainPrefs(context: Context, encPrefs: SharedPreferences) {
        val oldPrefs = context.getSharedPreferences(PREFS_NAME + "_legacy_check", Context.MODE_PRIVATE)
        // Check for the actual plain-text file on disk
        val plainFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
        // The encrypted file has a different on-disk name; if the plain file exists AND
        // has our known keys, it's the legacy store that needs migrating.
        val legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Detect legacy data: if the plain file contains our keys before encryption was added
        val legacyPairingId = legacyPrefs.getString(KEY_PAIRING_ID, null)
        val legacyKey = legacyPrefs.getString(KEY_ENCRYPTION_KEY, null)
        if (legacyPairingId == null && legacyKey == null) return // nothing to migrate

        Log.i("DeviceManager", "Migrating legacy plaintext prefs to EncryptedSharedPreferences")
        val editor = encPrefs.edit()
        // Migrate all known keys
        legacyPrefs.getString(KEY_ENCRYPTION_KEY, null)?.let { editor.putString(KEY_ENCRYPTION_KEY, it) }
        legacyPrefs.getString(KEY_PAIRING_ID, null)?.let { editor.putString(KEY_PAIRING_ID, it) }
        legacyPrefs.getString(KEY_PAIRED_DEVICE_ID, null)?.let { editor.putString(KEY_PAIRED_DEVICE_ID, it) }
        legacyPrefs.getString(KEY_PAIRED_DEVICE_NAME, null)?.let { editor.putString(KEY_PAIRED_DEVICE_NAME, it) }
        legacyPrefs.getString(KEY_ANDROID_DEVICE_ID, null)?.let { editor.putString(KEY_ANDROID_DEVICE_ID, it) }
        legacyPrefs.getString(KEY_ANDROID_DEVICE_NAME, null)?.let { editor.putString(KEY_ANDROID_DEVICE_NAME, it) }
        legacyPrefs.getString(KEY_REGION, null)?.let { editor.putString(KEY_REGION, it) }
        if (legacyPrefs.contains(KEY_PAIRED)) editor.putBoolean(KEY_PAIRED, legacyPrefs.getBoolean(KEY_PAIRED, false))
        if (legacyPrefs.contains(KEY_SYNC_TO_MAC)) editor.putBoolean(KEY_SYNC_TO_MAC, legacyPrefs.getBoolean(KEY_SYNC_TO_MAC, true))
        if (legacyPrefs.contains(KEY_SYNC_FROM_MAC)) editor.putBoolean(KEY_SYNC_FROM_MAC, legacyPrefs.getBoolean(KEY_SYNC_FROM_MAC, true))
        editor.commit() // synchronous to guarantee data is written before we wipe

        // Wipe the old plaintext file
        legacyPrefs.edit().clear().commit()
        Log.i("DeviceManager", "Legacy prefs migration complete — old data wiped")
    }

    /**
     * Surfaces a security error to the user via a Toast.
     * Called when a Keystore / encryption operation fails.
     */
    internal fun notifySecurityError(context: Context, message: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "⚠️ ClipSync: $message", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) { /* best-effort UI notification */ }
    }

    // ── Device ID ─────────────────────────────────────────────────────────────

    /**
     * Returns the stable, persistent identifier for this Android device installation.
     *
     * The ID is composed as `"<Build.MODEL>_<UUID>"` and generated exactly once on the
     * first call. All subsequent calls return the persisted value, making this ID stable
     * across app restarts (but not across reinstalls or data clears, by design).
     *
     * This ID is written as `sourceDeviceId` in every Firestore clipboard document so
     * that each device can distinguish its own uploads from those of the paired Mac.
     *
     * @return A non-empty string that uniquely identifies this device installation.
     */
    fun getDeviceId(context: Context): String {
        var deviceId = getPrefs(context).getString(KEY_ANDROID_DEVICE_ID, null)
        if (deviceId == null) {
            // First call: generate a stable ID from the device model and a random UUID, then persist it.
            deviceId = "${Build.MODEL}_${UUID.randomUUID()}"
            getPrefs(context).edit().putString(KEY_ANDROID_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    // ── Pairing state ─────────────────────────────────────────────────────────

    /**
     * Returns `true` if this device has been successfully paired with a Mac.
     *
     * Used by [MainActivity] to decide whether to navigate directly to the home screen
     * or to the QR scanner pairing screen on app launch.
     */
    fun isPaired(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PAIRED, false)

    /**
     * Atomically persists all pairing data to [SharedPreferences] after a successful
     * QR scan and Firestore document creation.
     *
     * Writes the following values in a single editor transaction:
     * - `is_paired` flag set to `true`.
     * - Firestore pairing document ID.
     * - Paired Mac device ID and display name.
     * - This Android device's display name (captured at pairing time).
     *
     * @param pairingId      The Firestore document ID of the newly created pairing record.
     * @param macDeviceId    The unique identifier of the paired Mac.
     * @param macDeviceName  The human-readable display name of the Mac.
     */
    fun savePairing(
        context: Context,
        pairingId: String,
        macDeviceId: String,
        macDeviceName: String
    ) {
        val androidDeviceName = getAndroidDeviceName()

        getPrefs(context).edit().apply {
            putBoolean(KEY_PAIRED,              true)
            putString(KEY_PAIRING_ID,           pairingId)
            putString(KEY_PAIRED_DEVICE_ID,     macDeviceId)
            putString(KEY_PAIRED_DEVICE_NAME,   macDeviceName)
            putString(KEY_ANDROID_DEVICE_NAME,  androidDeviceName)
            apply()
        }
    }

    /**
     * Returns the display name of the currently paired Mac as stored during the last
     * successful [savePairing] call. Returns `"Unknown Device"` if no pairing exists yet.
     */
    fun getPairedMacDeviceName(context: Context): String =
        getPrefs(context).getString(KEY_PAIRED_DEVICE_NAME, "Unknown Device") ?: "Unknown Device"

    /**
     * Builds a human-readable display name for this Android device by combining
     * [Build.MANUFACTURER] and [Build.MODEL].
     *
     * Special cases:
     * - If the model string contains `"sdk"` (emulator detection), returns `"Android Emulator"`.
     * - The manufacturer name is title-cased for consistency (e.g. `"samsung"` → `"Samsung"`).
     * - The result is truncated to 20 characters to keep it suitable for compact UI display.
     *
     * Examples of returned values: `"Samsung Galaxy S23"`, `"Google Pixel 8"`,
     * `"Xiaomi Redmi Note"`, `"Android Emulator"`.
     */
    fun getAndroidDeviceName(): String {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model        = Build.MODEL        ?: "Android"

        return when {
            model.contains("sdk", ignoreCase = true) -> "Android Emulator"
            manufacturer.isNotEmpty() -> {
                val capitalizedManufacturer = manufacturer.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                "$capitalizedManufacturer $model"
            }
            else -> model
        }.take(20) // Truncate to 20 chars for UI display compatibility.
    }

    /**
     * Returns the Firestore document ID of the active pairing record, or `null` if this
     * device is not currently paired.
     *
     * [FirestoreManager] uses this value to scope every Firestore query to the correct
     * pairing, ensuring that clipboard items belonging to other users are never visible.
     */
    fun getPairingId(context: Context): String? =
        getPrefs(context).getString(KEY_PAIRING_ID, null)

    /**
     * Removes all pairing-related keys from [SharedPreferences], returning the device
     * to an unpaired state.
     *
     * Note: this method only clears local storage. It does **not** delete the corresponding
     * Firestore document. Call [FirestoreManager.clearPairing] first if the cloud record
     * should also be removed.
     */
    fun clearPairing(context: Context) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_PAIRED,           false)
            remove(KEY_PAIRING_ID)
            remove(KEY_PAIRED_DEVICE_ID)
            remove(KEY_PAIRED_DEVICE_NAME)
            remove(KEY_ENCRYPTION_KEY)
            apply()
        }
    }

    // ── Encryption key ────────────────────────────────────────────────────────

    /**
     * Returns the AES-256 encryption key (64-character hex string) for the current
     * pairing session, or `null` if no key has been stored yet.
     *
     * M2 fix: no longer falls back to FALLBACK_ENCRYPTION_KEY. Callers must guard
     * with `if (!isPaired()) return` before using this value.
     */
    fun getEncryptionKey(context: Context): String? =
        getPrefs(context).getString(KEY_ENCRYPTION_KEY, null)

    /**
     * Persists the AES-256 session key received from the Mac's QR code payload.
     *
     * This key must be saved before [FirestoreManager.createPairing] writes to Firestore
     * so that the very first outgoing clipboard upload is encrypted with the correct key.
     *
     * @param key A 64-character hex string representing a 32-byte AES-256 key.
     */
    fun saveEncryptionKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_ENCRYPTION_KEY, key).apply()
    }

    // ── Encryption failure policy ──────────────────────────────────────────────

    /**
     * Returns the user's chosen policy for what to do when AES encryption fails.
     *
     * Defaults to [ENCRYPTION_POLICY_NEVER_ALLOW] (skip sync) so that sensitive data
     * is never unknowingly sent as plaintext to Firestore.
     */
    fun getEncryptionFailurePolicy(context: Context): String =
        getPrefs(context).getString(KEY_ENCRYPTION_FAILURE_POLICY, ENCRYPTION_POLICY_NEVER_ALLOW)
            ?: ENCRYPTION_POLICY_NEVER_ALLOW

    /**
     * Persists the user's chosen encryption failure policy.
     *
     * @param policy One of [ENCRYPTION_POLICY_NEVER_ALLOW] or [ENCRYPTION_POLICY_ALWAYS_ALLOW].
     */
    fun setEncryptionFailurePolicy(context: Context, policy: String) {
        getPrefs(context).edit().putString(KEY_ENCRYPTION_FAILURE_POLICY, policy).apply()
    }

    // ── Sync direction toggles ────────────────────────────────────────────────

    /**
     * Returns `true` if the user has enabled clipboard sync from this Android device to
     * the paired Mac (default: `true`).
     *
     * When `false`, [ClipboardAccessibilityService] still detects copy events but
     * [FirestoreManager.sendClipboard] is not invoked.
     */
    fun isSyncToMacEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SYNC_TO_MAC, true)

    /** Persists the "Sync to Mac" toggle state.
     *
     * @param enabled `true` to allow Android → Mac clipboard sync; `false` to disable it.
     */
    fun setSyncToMacEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_TO_MAC, enabled).apply()
    }

    /**
     * Returns `true` if the user has enabled clipboard sync from the paired Mac to this
     * Android device (default: `true`).
     *
     * When `false`, incoming Firestore clipboard items are ignored by the snapshot
     * listener started in [ClipboardAccessibilityService].
     */
    fun isSyncFromMacEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SYNC_FROM_MAC, true)

    /** Persists the "Sync from Mac" toggle state.
     *
     * @param enabled `true` to allow Mac → Android clipboard sync; `false` to disable it.
     */
    fun setSyncFromMacEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_FROM_MAC, enabled).apply()
    }

    /**
     * Returns `true` if the user has enabled auto-sync for OTPs (default: `true`).
     */
    fun isAutoSyncOTPsEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_AUTO_SYNC_OTPS, true)

    /** Persists the "Auto-Sync OTPs" toggle state. */
    fun setAutoSyncOTPsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_SYNC_OTPS, enabled).apply()
    }
}
