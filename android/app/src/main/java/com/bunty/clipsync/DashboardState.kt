package com.bunty.clipsync

data class DashboardState(
    val macDeviceName: String = "Unknown Device",
    val isPaired: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isBatteryUnrestricted: Boolean = false,
    val isNotificationListenerEnabled: Boolean = false,
    val syncToMac: Boolean = true,
    val syncFromMac: Boolean = true,
    val autoSyncOTPs: Boolean = true,
    val activeRouteName: String = "Disconnected",
    val activeRouteDescription: String = "Not connected to any device.",
    val showUpdateDialog: Boolean = false
)
