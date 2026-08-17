package com.bunty.clipsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AndroidTcpReceiver — on-demand TCP server that accepts exactly ONE file transfer from the Mac.
 *
 * Lifecycle:
 *   [start] → opens ServerSocket → returns true once ready → waits for Mac to connect
 *   → streams bytes to disk incrementally → closes socket → shows notification
 *   Auto-closes after 60 seconds if Mac never connects.
 *
 * Wire protocol (matches ClipSyncServer.swift writeFileOverTCP):
 *   Header 24 bytes: magic(4) + version(1) + typeCode(1) + reserved(2) + totalSize(8) + chunkSize(8)
 *   Preamble: nameLen(4) + name(nameLen)
 *   Chunks: [chunkLen(4) + encrypted_chunk]*
 *   Each chunk: nonce(12) + ciphertext + GCM_tag(16)
 */
object AndroidTcpReceiver {

    private const val TAG = "AndroidTcpReceiver"
    private const val MAGIC = 0x434C5359L   // "CLSY"
    private const val TIMEOUT_MS = 60_000
    private const val NOTIF_CHANNEL = "clipsync_file_transfer"
    private const val NOTIF_ID = 7701

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    @Volatile private var isCancelled: Boolean = false

    // State exposed to UI
    private val _isReceiving = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isReceiving: kotlinx.coroutines.flow.StateFlow<Boolean> = _isReceiving

    private val _receiveProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val receiveProgress: kotlinx.coroutines.flow.StateFlow<Float> = _receiveProgress

    private val _receiveSpeedString = kotlinx.coroutines.flow.MutableStateFlow("")
    val receiveSpeedString: kotlinx.coroutines.flow.StateFlow<String> = _receiveSpeedString

    /**
     * Opens a ServerSocket on [port] and starts accepting in the background.
     * Returns true immediately once the port is open (so caller can send the ACK).
     * Returns false if the port could not be bound.
     */
    suspend fun start(
        context: Context,
        port: Int,
        expectedSize: Long,
        expectedFilename: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Close any lingering previous socket
            serverSocket?.close()

            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            server.soTimeout = TIMEOUT_MS
            serverSocket = server

            // Accept loop in background
            scope.launch {
                try {
                    val client: Socket = server.accept()
                    server.close()
                    serverSocket = null
                    receiveFile(context, client, expectedFilename)
                } catch (e: Exception) {
                    Log.w(TAG, "TCP server accept error (may be timeout): ${e.message}")
                    serverSocket = null
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open TCP server on port $port", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    /**
     * Like [start], but receives the stream into memory and delivers the decoded UTF-8 text
     * via [onReceived] instead of writing to Downloads.
     * Used for large clipboard text transfers (Mac `text_incoming` signal).
     */
    suspend fun startForText(
        context: Context,
        port: Int,
        expectedSize: Long,
        onReceived: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            serverSocket?.close()
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            server.soTimeout = TIMEOUT_MS
            serverSocket = server

            scope.launch {
                try {
                    val client: Socket = server.accept()
                    server.close()
                    serverSocket = null
                    receiveTextStream(context, client, expectedSize, onReceived)
                } catch (e: Exception) {
                    Log.w(TAG, "TCP text-server accept error: ${e.message}")
                    serverSocket = null
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open TCP text-server on port $port", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    /**
     * Receives an encrypted-chunk TCP stream (same wire protocol as file transfers) into a
     * ByteArrayOutputStream, then decodes and delivers the result as a UTF-8 String.
     */
    private suspend fun receiveTextStream(
        context: Context,
        client: Socket,
        expectedSize: Long,
        onReceived: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val inp = client.getInputStream()

            // 1. Read 24-byte header (same format as file transfers)
            val header = inp.readFully(24) ?: run {
                Log.e(TAG, "receiveTextStream — failed to read header"); return@withContext
            }
            val magic = header.readUInt32BE(0)
            if (magic != MAGIC) { Log.e(TAG, "receiveTextStream — bad magic: $magic"); return@withContext }
            val totalSize = header.readInt64BE(8)

            // 2. Read filename preamble (sent but ignored for text streams)
            val nameLenBytes = inp.readFully(4) ?: return@withContext
            val nameLen = nameLenBytes.readUInt32BE(0).toInt()
            if (nameLen in 1..2048) inp.readFully(nameLen) // discard sentinel filename

            // 3. Open decryption key
            val hexKey = DeviceManager.getEncryptionKey(context)
            val keyBytes = hexKey?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            if (keyBytes == null) {
                Log.e(TAG, "receiveTextStream — no encryption key"); return@withContext
            }
            val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

            var received = 0L
            val buffer = java.io.ByteArrayOutputStream(totalSize.coerceAtMost(10 * 1024 * 1024).toInt())

            while (received < totalSize) {
                val chunkLenBytes = inp.readFully(4) ?: break
                val chunkLen = chunkLenBytes.readUInt32BE(0).toInt()
                if (chunkLen <= 0 || chunkLen > 5_000_000) {
                    Log.e(TAG, "receiveTextStream — invalid chunk length: $chunkLen"); break
                }
                val encrypted = inp.readFully(chunkLen) ?: break
                val decrypted = decryptChunk(encrypted, cipher, secretKey) ?: break
                buffer.write(decrypted)
                received += decrypted.size
            }
            client.runCatching { close() }

            if (received < totalSize) {
                Log.w(TAG, "receiveTextStream — incomplete: $received / $totalSize bytes")
                return@withContext
            }

            val text = buffer.toString(Charsets.UTF_8.name())
            onReceived(text)

        } catch (e: Exception) {
            Log.e(TAG, "receiveTextStream error", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        } finally {
            client.runCatching { close() }
        }
    }


    /**
     * Forcefully cancels the active file receive transfer by closing the sockets.
     */
    fun cancel() {
        isCancelled = true
        serverSocket?.runCatching { close() }
        serverSocket = null
        activeClient?.runCatching { close() }
        activeClient = null
    }

    // ── File receive ─────────────────────────────────────────────────────────

    private suspend fun receiveFile(context: Context, client: Socket, expectedFilename: String) =
        withContext(Dispatchers.IO) {
            activeClient = client
            isCancelled = false
            _isReceiving.value = true
            _receiveProgress.value = 0f
            var fileName = expectedFilename

            // Track the MediaStore URI so we can delete the partial entry on failure
            var pendingUri: android.net.Uri? = null

            try {
                val inp = client.getInputStream()

                // ── 1. Read 24-byte header ────────────────────────────────────
                val header = inp.readFully(24) ?: run {
                    Log.e(TAG, "Failed to read header"); return@withContext
                }
                val magic = header.readUInt32BE(0)
                if (magic != MAGIC) { Log.e(TAG, "Bad magic: $magic"); return@withContext }
                val typeCode = header[5]
                val isUltraFast = typeCode == 0x04.toByte()
                val totalSize = header.readInt64BE(8)

                // ── 2. Read filename preamble ─────────────────────────────────
                val nameLenBytes = inp.readFully(4) ?: return@withContext
                val nameLen = nameLenBytes.readUInt32BE(0).toInt()
                fileName = if (nameLen in 1..2048) {
                    inp.readFully(nameLen)?.toString(Charsets.UTF_8) ?: expectedFilename
                } else {
                    expectedFilename
                }

                // ── 3. Open decryption key ────────────────────────────────────
                val hexKey = DeviceManager.getEncryptionKey(context)
                val keyBytes = hexKey?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                if (keyBytes == null) {
                    Log.e(TAG, "No encryption key — cannot decrypt file"); return@withContext
                }
                val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

                // ── 4. Choose destination based on payload type ───────────────────
                val isImagePayload = typeCode == 0x02.toByte()

                val outputStream: java.io.OutputStream
                val resolver = context.contentResolver
                var stagedImageFile: File? = null

                if (isImagePayload) {
                    // Images go to a private cache file — NOT MediaStore/Downloads.
                    // ClipboardGhostActivity will read this file and place it directly
                    // on the clipboard once the transfer completes.
                    val stageDir = File(context.cacheDir, "clipboard_images").apply { mkdirs() }
                    stagedImageFile = File(stageDir, "received_image_${System.currentTimeMillis()}.jpg")
                    outputStream = FileOutputStream(stagedImageFile)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ — MediaStore with IS_PENDING
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: run {
                            Log.e(TAG, "MediaStore insert failed"); return@withContext
                        }
                    pendingUri = uri
                    outputStream = resolver.openOutputStream(uri)
                        ?: run {
                            Log.e(TAG, "Could not open MediaStore OutputStream"); return@withContext
                        }
                } else {
                    // Android 9 and below — write directly to public Downloads dir
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    outputStream = FileOutputStream(File(dir, fileName))
                }

                showProgressNotification(context, fileName, 0, totalSize)

                // ── 5. Stream chunks directly to the destination ──────────────
                var received = 0L
                var lastUpdate = System.currentTimeMillis()
                var lastNotifTime = 0L
                var lastBytes = 0L
                
                outputStream.use { out ->
                    if (isUltraFast) {
                        // UltraFast mode: Mac sends raw bytes with no chunk-length prefix
                        // and no AES-GCM encryption envelope — read directly into file.
                        val buf = ByteArray(1024 * 1024)
                        while (received < totalSize && !isCancelled) {
                            val toRead = minOf(buf.size.toLong(), totalSize - received).toInt()
                            val n = inp.read(buf, 0, toRead)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            received += n

                            val now = System.currentTimeMillis()
                            val dt = (now - lastUpdate) / 1000.0
                            if (dt >= 0.2) {
                                val progress = if (totalSize > 0) received.toDouble() / totalSize else 1.0
                                _receiveProgress.value = progress.toFloat()
                                val db = received - lastBytes
                                val speedMBs = (db / (1024.0 * 1024.0)) / dt
                                _receiveSpeedString.value = String.format(java.util.Locale.US, "%.1f MB/s", speedMBs)
                                if (now - lastNotifTime >= 1000) {
                                    showProgressNotification(context, fileName, (progress * 100).toInt(), totalSize)
                                    lastNotifTime = now
                                }
                                lastUpdate = now
                                lastBytes = received
                            }
                        }
                    } else {
                        // Standard encrypted mode: [chunkLen(4) + nonce(12) + ciphertext + GCM_tag(16)]
                        while (received < totalSize && !isCancelled) {
                            val chunkLenBytes = inp.readFully(4) ?: break
                            val chunkLen = chunkLenBytes.readUInt32BE(0).toInt()
                            if (chunkLen <= 0 || chunkLen > 5_000_000) {
                                Log.e(TAG, "Invalid chunk length: $chunkLen"); break
                            }

                            val encrypted = inp.readFully(chunkLen) ?: break
                            val decrypted = decryptChunk(encrypted, cipher, secretKey) ?: break
                            out.write(decrypted)
                            received += decrypted.size

                            val now = System.currentTimeMillis()
                            val dt = (now - lastUpdate) / 1000.0
                            if (dt >= 0.2) {
                                val progress = if (totalSize > 0) received.toDouble() / totalSize else 1.0
                                _receiveProgress.value = progress.toFloat()
                                val db = received - lastBytes
                                val speedMBs = (db / (1024.0 * 1024.0)) / dt
                                _receiveSpeedString.value = String.format(java.util.Locale.US, "%.1f MB/s", speedMBs)
                                if (now - lastNotifTime >= 1000) {
                                    showProgressNotification(context, fileName, (progress * 100).toInt(), totalSize)
                                    lastNotifTime = now
                                }
                                lastUpdate = now
                                lastBytes = received
                            }
                        }
                    }
                }
                
                // Final guaranteed progress update
                _receiveProgress.value = 1f
                _receiveSpeedString.value = ""
                showProgressNotification(context, fileName, 100, totalSize)

                client.close()
                activeClient = null
                _isReceiving.value = false

                // ── 6. Handle cancellation / incomplete transfer ──────────────
                if (isCancelled) {
                    Log.w(TAG, "Transfer was cancelled midway")
                    showFailedNotification(context, fileName, "Cancelled by user")
                    deletePendingEntry(context, pendingUri)
                    return@withContext
                }

                if (received < totalSize) {
                    Log.w(TAG, "Transfer incomplete: $received / $totalSize bytes")
                    showFailedNotification(context, fileName, "Connection dropped")
                    deletePendingEntry(context, pendingUri)
                    return@withContext
                }

                // ── 7. Finalize ─────────────────────────────────────────────────
                if (isImagePayload) {
                    stagedImageFile?.let { file ->
                        ClipboardGhostActivity.copyImageFileToClipboard(context, file.absolutePath)
                    }
                    // No Downloads notification for images — they're not going to Downloads at all now
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingUri != null) {
                        val doneValues = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        resolver.update(pendingUri!!, doneValues, null, null)
                    }
                    var finalUri = pendingUri
                    if (finalUri == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        finalUri = Uri.fromFile(File(dir, fileName))
                    }
                    showCompleteNotification(context, fileName, "Downloads", finalUri)
                }

            } catch (e: Exception) {
                Log.e(TAG, "receiveFile error", e)
                FirebaseCrashlytics.getInstance().recordException(e)
                client.runCatching { close() }
                activeClient = null
                _isReceiving.value = false
                deletePendingEntry(context, pendingUri)
                val reason = if (isCancelled) "Cancelled" else "Connection error"
                showFailedNotification(context, fileName, reason)
            } finally {
                DeviceManager.setUltraFastModeEnabled(context, false)
            }
        }

    /** Deletes a partial MediaStore entry if something went wrong mid-transfer. */
    private fun deletePendingEntry(context: Context, uri: android.net.Uri?) {
        if (uri == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete pending entry: ${e.message}")
        }
    }


    // ── AES-GCM decryption ────────────────────────────────────────────────────

    private fun decryptChunk(data: ByteArray, cipher: Cipher, key: SecretKeySpec): ByteArray? {
        return try {
            if (data.size <= 12) return null
            val spec = javax.crypto.spec.GCMParameterSpec(128, data, 0, 12)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            cipher.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) {
            Log.e(TAG, "Chunk decryption failed", e)
            null
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(NOTIF_CHANNEL, "File Transfers", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "ClipSync file transfer progress" }
                )
            }
        }
    }

    private fun showProgressNotification(context: Context, filename: String, percent: Int, totalBytes: Long) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sizeMb = String.format("%.1f MB", totalBytes / 1_048_576.0)
        
        val cancelIntent = android.content.Intent(context, CancelTransferReceiver::class.java).apply {
            action = CancelTransferReceiver.ACTION_CANCEL_RECEIVE
        }
        val cancelPendingIntent = android.app.PendingIntent.getBroadcast(
            context, 0, cancelIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Receiving from Mac")
            .setContentText("$filename — $percent% of $sizeMb")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun showCompleteNotification(context: Context, filename: String, destLabel: String, uri: Uri?) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("File received")
            .setContentText("$filename saved to $destLabel")
            .setAutoCancel(true)

        if (uri != null) {
            val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, viewIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
        }

        val notif = builder.build()
        nm.cancel(NOTIF_ID)
        nm.notify(NOTIF_ID + 1, notif)
    }

    private fun showFailedNotification(context: Context, filename: String, reason: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Transfer Failed")
            .setContentText("$filename: $reason")
            .setAutoCancel(true)
            .build()
        nm.cancel(NOTIF_ID)
        nm.notify(NOTIF_ID + 1, notif)
    }

    /**
     * Copies [srcFile] into the system public Downloads folder.
     * Kept for legacy/pre-Q fallback paths only.
     */
    private fun saveToPublicDownloads(context: Context, srcFile: File, fileName: String): Uri? {
        return try {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val destFile = File(downloadsDir, fileName)
            srcFile.copyTo(destFile, overwrite = true)
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "saveToPublicDownloads failed", e)
            null
        }
    }
}

// ── Stream / Data helpers ─────────────────────────────────────────────────────

private fun java.io.InputStream.readFully(length: Int): ByteArray? {
    val buf = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(buf, offset, length - offset)
        if (read < 0) return null
        offset += read
    }
    return buf
}

private fun ByteArray.readUInt32BE(offset: Int): Long {
    return ((this[offset].toLong() and 0xFF) shl 24) or
           ((this[offset + 1].toLong() and 0xFF) shl 16) or
           ((this[offset + 2].toLong() and 0xFF) shl 8) or
           (this[offset + 3].toLong() and 0xFF)
}

private fun ByteArray.readInt64BE(offset: Int): Long {
    var result = 0L
    for (i in 0..7) result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    return result
}
