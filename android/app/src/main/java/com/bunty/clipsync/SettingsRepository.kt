package com.bunty.clipsync

import android.content.Context
import com.bunty.clipsync.DeviceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(private val context: Context) {

    private val _syncToMac = MutableStateFlow(DeviceManager.isSyncToMacEnabled(context))
    val syncToMac: StateFlow<Boolean> = _syncToMac.asStateFlow()

    private val _syncFromMac = MutableStateFlow(DeviceManager.isSyncFromMacEnabled(context))
    val syncFromMac: StateFlow<Boolean> = _syncFromMac.asStateFlow()

    private val _autoSyncOTPs = MutableStateFlow(DeviceManager.isAutoSyncOTPsEnabled(context))
    val autoSyncOTPs: StateFlow<Boolean> = _autoSyncOTPs.asStateFlow()

    fun getPairedMacDeviceName(): String {
        return DeviceManager.getPairedMacDeviceName(context)
    }

    fun setSyncToMac(enabled: Boolean) {
        DeviceManager.setSyncToMacEnabled(context, enabled)
        _syncToMac.value = enabled
    }

    fun setSyncFromMac(enabled: Boolean) {
        DeviceManager.setSyncFromMacEnabled(context, enabled)
        _syncFromMac.value = enabled
    }

    fun setAutoSyncOTPs(enabled: Boolean) {
        DeviceManager.setAutoSyncOTPsEnabled(context, enabled)
        _autoSyncOTPs.value = enabled
    }
}
