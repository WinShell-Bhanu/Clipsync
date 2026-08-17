package com.bunty.clipsync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bunty.clipsync.DeviceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(DashboardState())
    val uiState: StateFlow<DashboardState> = _uiState.asStateFlow()

    init {
        // Initialize state
        _uiState.update {
            it.copy(
                macDeviceName = repository.getPairedMacDeviceName(),
                isPaired = DeviceManager.isPaired(application),
                syncToMac = repository.syncToMac.value,
                syncFromMac = repository.syncFromMac.value,
                autoSyncOTPs = repository.autoSyncOTPs.value,
                activeRouteName = "Direct: Same Wi-Fi", // Mocked for now based on new plan
                activeRouteDescription = "Data flows directly between devices over the local network. No cloud required."
            )
        }

        // Collect repository flows to update UI state when preferences change
        viewModelScope.launch {
            repository.syncToMac.collect { value ->
                _uiState.update { it.copy(syncToMac = value) }
            }
        }

        viewModelScope.launch {
            repository.syncFromMac.collect { value ->
                _uiState.update { it.copy(syncFromMac = value) }
            }
        }

        viewModelScope.launch {
            repository.autoSyncOTPs.collect { value ->
                _uiState.update { it.copy(autoSyncOTPs = value) }
            }
        }
    }

    fun toggleSyncToMac(enabled: Boolean) {
        repository.setSyncToMac(enabled)
    }

    fun toggleSyncFromMac(enabled: Boolean) {
        repository.setSyncFromMac(enabled)
    }

    fun toggleAutoSyncOTPs(enabled: Boolean) {
        repository.setAutoSyncOTPs(enabled)
    }

    fun updatePermissionsState(
        isAccessibilityEnabled: Boolean,
        isBatteryUnrestricted: Boolean,

        isNotificationListenerEnabled: Boolean
    ) {
        _uiState.update {
            it.copy(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isBatteryUnrestricted = isBatteryUnrestricted,

                isNotificationListenerEnabled = isNotificationListenerEnabled
            )
        }
    }

    fun clearPairing() {
        DeviceManager.clearPairing(getApplication())
        _uiState.update { it.copy(isPaired = false, macDeviceName = "Unknown Device") }
    }
}
