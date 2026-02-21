package com.bunty.clipsync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

/**
 * DeviceManager is the single source of truth for all device-level state in ClipSync.
 *
 * It persists data in a private [SharedPreferences] file (`clipsync_prefs`) and exposes
 * typed getters/setters so the rest of the codebase never accesses SharedPreferences directly.
 *
 * **Responsibilities:**
 * - Generating and persisting a stable device ID (UUID + model name).
 * - Storing and reading the pairing state (paired/unpaired, paired Mac details).
 * - Persisting sync direction toggles (to Mac / from Mac).
 * - Managing the target Firestore region (IN or US).
 * - Storing the per-session AES encryption key exchanged during pairing.
 */
object DeviceManager {

    private const val PREFS_NAME = "clipsync_prefs"

    // ── SharedPreferences keys ────────────────────────────────────────────────
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

    /**
     * The Firestore region that was active when Firebase was initialised in [ClipSyncApp].
     * Used by [FirestoreManager] to detect region mismatches after a QR scan.
     * Defaults to `"IN"` (India).
     */
    var initializedRegion: String = "IN"

    // ── Region helpers ────────────────────────────────────────────────────────

    /**
     * Returns the persisted Firestore target region (`"IN"` or `"US"`).
     * Defaults to `"IN"` if not yet set.
     */
    fun getTargetRegion(context: Context): String =
        getPrefs(context).getString(KEY_REGION, "IN") ?: "IN"

    /**
     * Returns `true` if a target region has already been stored (set on first launch
     * via [LocationHelper]).
     */
    fun isRegionSet(context: Context): Boolean =
        getPrefs(context).contains(KEY_REGION)

    /**
     * Persists the Firestore target region.
     *
     * Uses `commit()` (synchronous write) instead of `apply()` because the region value
     * must be readable by [FirestoreManager] immediately on the next line.
     *
     * @param region `"IN"` or `"US"` (case-insensitive; stored as uppercase).
     */
    fun setTargetRegion(context: Context, region: String) {
        val normalizedRegion = region.uppercase()
        // Only write if the value has actually changed to avoid unnecessary disk writes
        if (normalizedRegion != getTargetRegion(context)) {
            getPrefs(context).edit().putString(KEY_REGION, normalizedRegion).commit()
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Returns the [SharedPreferences] instance used by all DeviceManager operations. */
    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Device ID ─────────────────────────────────────────────────────────────

    /**
     * Returns the stable device ID for this Android device.
     *
     * The ID is generated once on first call (`"<Build.MODEL>_<UUID>"`) and then persisted
     * so the same value is returned on every subsequent call.
     *
     * @return A non-empty string that uniquely identifies this device installation.
     */
    fun getDeviceId(context: Context): String {
        var deviceId = getPrefs(context).getString(KEY_ANDROID_DEVICE_ID, null)
        if (deviceId == null) {
            // First launch: generate and persist a stable ID
            deviceId = "${Build.MODEL}_${UUID.randomUUID()}"
            getPrefs(context).edit().putString(KEY_ANDROID_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    // ── Pairing state ─────────────────────────────────────────────────────────

    /**
     * Returns `true` if this device has been successfully paired with a Mac.
     * Used by [MainActivity] to decide the initial navigation destination.
     */
    fun isPaired(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PAIRED, false)

    /**
     * Persists all pairing data after a successful QR scan + Firestore pairing.
     *
     * @param pairingId      The Firestore document ID of the pairing record.
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
     * Returns the display name of the currently paired Mac, or `"Unknown Device"` if
     * no pairing has been saved yet.
     */
    fun getPairedMacDeviceName(context: Context): String =
        getPrefs(context).getString(KEY_PAIRED_DEVICE_NAME, "Unknown Device") ?: "Unknown Device"

    /**
     * Builds a human-readable name for this Android device from [Build.MANUFACTURER]
     * and [Build.MODEL]. Truncated to 20 characters to keep it UI-friendly.
     *
     * Examples: `"Samsung Galaxy S23"`, `"Google Pixel 8"`, `"Android Emulator"`.
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
        }.take(20)  // cap at 20 chars for display purposes
    }

    /**
     * Returns the Firestore document ID of the active pairing, or `null` if unpaired.
     * Used by [FirestoreManager] to scope all Firestore queries to this pairing.
     */
    fun getPairingId(context: Context): String? =
        getPrefs(context).getString(KEY_PAIRING_ID, null)

    /**
     * Clears all pairing data from SharedPreferences.
     * Called when the user taps "Re-pair" or "Reset Pairing".
     * Does NOT delete the Firestore document — use [FirestoreManager.clearPairing] for that.
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
     * Returns the AES-256 encryption key (hex-encoded) agreed during pairing.
     * Falls back to [Secrets.FALLBACK_ENCRYPTION_KEY] if no key has been stored yet.
     */
    fun getEncryptionKey(context: Context): String =
        getPrefs(context).getString(KEY_ENCRYPTION_KEY, null)
            ?: Secrets.FALLBACK_ENCRYPTION_KEY

    /**
     * Persists the AES encryption key received from the Mac's QR code during pairing.
     *
     * @param key A 64-character hex string representing a 32-byte AES-256 key.
     */
    fun saveEncryptionKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_ENCRYPTION_KEY, key).apply()
    }

    // ── Sync direction toggles ────────────────────────────────────────────────

    /** Returns `true` if the "Sync to Mac" toggle is enabled (default: `true`). */
    fun isSyncToMacEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SYNC_TO_MAC, true)

    /** Persists the "Sync to Mac" toggle state. */
    fun setSyncToMacEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_TO_MAC, enabled).apply()
    }

    /** Returns `true` if the "Sync from Mac" toggle is enabled (default: `true`). */
    fun isSyncFromMacEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SYNC_FROM_MAC, true)

    /** Persists the "Sync from Mac" toggle state. */
    fun setSyncFromMacEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_FROM_MAC, enabled).apply()
    }
}
