package com.bunty.clipsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object PermissionHelper {
    
    fun isAccessibilityEnabled(context: Context): Boolean {
        var accessibilityEnabled = 0
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) { }
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                return settingValue.contains(context.packageName)
            }
        }
        return false
    }

    fun isNotificationsEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isOverlayEnabled(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isNotificationAccessEnabled(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }


    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestAccessibility(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    fun requestOverlay(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun requestNotificationAccess(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    fun requestBatteryOptimization(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun isSmsPermissionEnabled(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
}
