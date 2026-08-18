package com.bunty.clipsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A Foreground Service responsible for keeping URI grants alive during 
 * background file transfers initiated from the Android Share Sheet.
 *
 * It starts when ShareActivity hands off a share intent, manages the 
 * ParcelFileDescriptors, passes them to LocalSyncManager, and cleanly 
 * stops itself the moment the transfer completes or fails.
 */
class ShareTransferService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var collectorJob: Job? = null
    // We want to skip the initial "Idle" state when collecting
    private var hasStartedTransfer = false

    companion object {
        private const val TAG = "ShareTransferService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "share_transfer_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Preparing transfer..."))

        if (intent == null || intent.clipData == null) {
            Log.e(TAG, "No ClipData found in intent, stopping service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val clipData = intent.clipData!!
        val uris = mutableListOf<Uri>()
        for (i in 0 until clipData.itemCount) {
            clipData.getItemAt(i).uri?.let { uris.add(it) }
        }

        if (uris.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val single = intent.getBooleanExtra("single", true)
        
        // Start processing the files
        scope.launch {
            val sharedFiles = mutableListOf<SharedFile>()

            for (uri in uris) {
                try {
                    val fileName = resolveFileName(uri) ?: "shared_file_${System.currentTimeMillis()}"
                    var fileSize = -1L
                    
                    try {
                        contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                            fileSize = it.length
                        }
                    } catch (_: Exception) {}
                    
                    if (fileSize <= 0) {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                            }
                        }
                    }

                    // Open the PFD! As long as this service is alive, the URI grant is valid
                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        if (fileSize <= 0) {
                            fileSize = pfd.statSize
                        }
                        if (fileSize <= 0) {
                            try {
                                fileSize = java.io.FileInputStream(pfd.fileDescriptor).channel.size()
                            } catch (_: Exception) {}
                        }
                        // Important: if we still can't determine the size, it's safer to fail or warn, 
                        // but usually statSize or channel size succeeds.
                        if (fileSize > 0) {
                            sharedFiles.add(SharedFile(fileName, fileSize, pfd, uri, null))
                        } else {
                            Log.e(TAG, "Could not determine file size for $uri, skipping zero-byte file")
                            pfd.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract PFD for URI $uri: ${e.message}")
                }
            }

            if (sharedFiles.isEmpty()) {
                Log.e(TAG, "No files could be processed, stopping service.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val title = if (single) "Sending file to Mac..." else "Sending ${sharedFiles.size} files to Mac..."
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(title))

            // Hand off to LocalSyncManager
            LocalSyncManager.onClipboardContentSharedFiles(applicationContext, sharedFiles)

            // Wait for completion
            collectorJob = scope.launch {
                LocalSyncManager.state.collect { state ->
                    when (state) {
                        is LocalSyncManager.SyncState.Found, 
                        is LocalSyncManager.SyncState.Connecting,
                        is LocalSyncManager.SyncState.SendingWakeup,
                        is LocalSyncManager.SyncState.Streaming -> {
                            hasStartedTransfer = true
                        }
                        is LocalSyncManager.SyncState.Success, is LocalSyncManager.SyncState.Failed -> {
                            if (hasStartedTransfer) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                        is LocalSyncManager.SyncState.Idle -> {
                            // If it's Idle but we already started transferring, it means it reset
                            if (hasStartedTransfer) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                        else -> {
                            // Other intermediate states like CheckingWifi or DiscoveringMac
                        }
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun resolveFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress of background file transfers"
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipSync Transfer")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher) 
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        collectorJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
