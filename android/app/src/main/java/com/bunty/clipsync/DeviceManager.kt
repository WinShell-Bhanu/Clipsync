package com.bunty.clipsync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

object DeviceManager {
    private const val PREFS_NAME = "clipsync_prefs"
    
    // --- Preference Keys ---
    private const val KEY_PAIRED = "is_paired"
    private const val KEY_PAIRED_DEVICE_ID = "paired_device_id"
    private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_ENCRYPTION_KEY = "encryption_key" 
    private const val KEY_ANDROID_DEVICE_ID = "android_device_id"
    private const val KEY_ANDROID_DEVICE_NAME = "android_device_name"
    private const val KEY_SYNC_TO_MAC = "sync_to_mac"
    private const val KEY_SYNC_FROM_MAC = "sync_from_mac"
    private const val KEY_REGION = "server_region" // "IN" or "US"

    // Track the region the app was ACTUALLY initialized with at startup
    var initializedRegion: String = "IN" 

    fun getTargetRegion(context: Context): String {
        return getPrefs(context).getString(KEY_REGION, "IN") ?: "IN"
    }

    fun isRegionSet(context: Context): Boolean {
        return getPrefs(context).contains(KEY_REGION)
    }

    fun setTargetRegion(context: Context, region: String) {
        val normalizedRegion = region.uppercase()
        // Only saving if different to avoid redundant writes, 
        // but for initial setup we might want to force save.
        if (normalizedRegion != getTargetRegion(context)) {
            // Must use commit() here because we might kill the process immediately after 
            // for a region switch restart. apply() is async and might get cut off.
            getPrefs(context).edit().putString(KEY_REGION, normalizedRegion).commit()
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDeviceId(context: Context): String {
        var deviceId = getPrefs(context).getString(KEY_ANDROID_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = "${Build.MODEL}_${UUID.randomUUID()}"
            getPrefs(context).edit().putString(KEY_ANDROID_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun isPaired(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PAIRED, false)
    }

    fun savePairing(
        context: Context,
        pairingId: String,
        macDeviceId: String,
        macDeviceName: String
    ) {
        val androidDeviceName = getAndroidDeviceName()

        getPrefs(context).edit().apply {
            putBoolean(KEY_PAIRED, true)
            putString(KEY_PAIRING_ID, pairingId)
            putString(KEY_PAIRED_DEVICE_ID, macDeviceId)
            putString(KEY_PAIRED_DEVICE_NAME, macDeviceName)
            putString(KEY_ANDROID_DEVICE_NAME, androidDeviceName)
            apply()
        }
    }

    fun getPairedMacDeviceName(context: Context): String {
        return getPrefs(context).getString(KEY_PAIRED_DEVICE_NAME, "Unknown Device") ?: "Unknown Device"
    }

    fun getAndroidDeviceName(): String {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: "Android"

        return when {
            model.contains("sdk", ignoreCase = true) -> "Android Emulator"
            manufacturer.isNotEmpty() -> {
                val capitalizedManufacturer = manufacturer.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                "$capitalizedManufacturer $model"
            }
            else -> model
        }.take(20)
    }

    fun getPairingId(context: Context): String? {
        return getPrefs(context).getString(KEY_PAIRING_ID, null)
    }

    fun clearPairing(context: Context) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_PAIRED, false)
            remove(KEY_PAIRING_ID)
            remove(KEY_PAIRED_DEVICE_ID)
            remove(KEY_PAIRED_DEVICE_NAME)
            remove(KEY_PAIRED_DEVICE_NAME)
            remove(KEY_ENCRYPTION_KEY) // Clear key on unpair
            // Keep sync preferences or reset? Let's keep them for convenience.
            apply()
        }
    }

    // Dynamic Encryption Key
    fun getEncryptionKey(context: Context): String {
        return getPrefs(context).getString(KEY_ENCRYPTION_KEY, null) 
            ?: Secrets.FALLBACK_ENCRYPTION_KEY // Legacy Fallback
    }

    fun saveEncryptionKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_ENCRYPTION_KEY, key).apply()
    }

    fun isSyncToMacEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_TO_MAC, true) // Default true
    }

    fun setSyncToMacEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_TO_MAC, enabled).apply()
    }

    fun isSyncFromMacEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_FROM_MAC, true) // Default true
    }

    fun setSyncFromMacEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_FROM_MAC, enabled).apply()
    }
}
