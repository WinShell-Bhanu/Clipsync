package com.bunty.clipsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps Android subscribed to the Mac's BLE notify characteristic while the app is backgrounded.
 */
class MacPushForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMonitorIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        MacPushReceiver.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitorIfNeeded() {
        if (monitorJob?.isActive == true) return

        monitorJob = serviceScope.launch {
            while (isActive) {
                if (!shouldRun(this@MacPushForegroundService)) {
                    stopSelf()
                    return@launch
                }

                MacPushReceiver.start(applicationContext)
                delay(RECONNECT_INTERVAL_MS)
            }
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ClipSync is listening")
            .setContentText("Ready for Mac clipboard and file pushes")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Mac Push Sync",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Maintains the BLE subscription for Mac to Android sync"
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "MacPushService"
        private const val CHANNEL_ID = "clipsync_mac_push"
        private const val NOTIFICATION_ID = 7601
        private const val RECONNECT_INTERVAL_MS = 15_000L

        fun shouldRun(context: Context): Boolean {
            val hasBluetooth = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            return DeviceManager.isPaired(context) &&
                DeviceManager.isSyncFromMacEnabled(context) &&
                !DeviceManager.getMacBleAddress(context).isNullOrEmpty() &&
                hasBluetooth
        }

        fun startIfNeeded(context: Context) {
            if (!shouldRun(context)) return

            val appContext = context.applicationContext
            val intent = Intent(appContext, MacPushForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }
    }
}
