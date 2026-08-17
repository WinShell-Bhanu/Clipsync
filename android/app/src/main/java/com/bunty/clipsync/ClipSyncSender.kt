package com.bunty.clipsync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Raw TCP socket sender for the local Wi-Fi sync path.
 *
 * Wire protocol — every connection starts with a header block, followed by
 * the encrypted payload (or encrypted 64 KB chunks for large transfers):
 *
 * ```
 * ┌─────────────────────────────────────────────────────────┐
 * │ Header (24 bytes)                                       │
 * │   [4]  magic: 0x43_4C_53_59 ("CLSY")                   │
 * │   [1]  version: 0x01                                    │
 * │   [1]  type: 0x01=text 0x02=image 0x03=file            │
 * │   [2]  reserved                                         │
 * │   [8]  total payload size (Long, big-endian)            │
 * │   [8]  chunk size (Long, big-endian)                    │
 * ├─────────────────────────────────────────────────────────┤
 * │ Payload                                                 │
 * │   Each chunk: [4-byte encrypted-chunk-size][chunk data] │
 * └─────────────────────────────────────────────────────────┘
 * ```
 *
 * Both the header and each chunk are transmitted as plain bytes. Each chunk's
 * content is individually AES-256-GCM encrypted (12-byte IV prepended).
 */
object ClipSyncSender {

    private const val TAG         = "LocalSync"
    private const val MAGIC       = 0x434C5359.toInt()   // "CLSY"
    private const val VERSION     = 0x01.toByte()
    private const val TYPE_TEXT   = 0x01.toByte()
    private const val TYPE_IMAGE  = 0x02.toByte()
    private const val TYPE_FILE   = 0x03.toByte()
    private const val TYPE_FILE_FAST = 0x04.toByte()
    private const val CHUNK_SIZE  = 1_048_576                // 1 MB

    // ── Small payload (text / small images) ───────────────────────────────────

    /**
     * Encrypts [text] and streams it to [ip]:[port] over a single TCP connection.
     *
     * @param ip           Mac's LAN IP address (e.g. "192.168.1.5").
     * @param port         Mac's TCP server port ([LocalSyncManager.TCP_PORT]).
     * @param text         Plain-text clipboard content.
     * @param hexKey       64-char hex AES-256 session key.
     * @param connectMs    TCP connect timeout in milliseconds (default 3 s).
     */
    suspend fun sendText(
        ip: String,
        port:      Int,
        text:      String,
        hexKey:    String,
        connectMs: Int = 3_000
    ) = withContext(Dispatchers.IO) {
        val payload = AesGcmCipher.encrypt(text, hexKey)
        sendEncryptedPayload(ip, port, payload, TYPE_TEXT, connectMs)
    }

    /**
     * Encrypts [imageBytes] and streams it to [ip]:[port].
     */
    suspend fun sendImage(
        ip: String,
        port:      Int,
        imageBytes: ByteArray,
        hexKey:    String,
        connectMs: Int = 3_000
    ) = withContext(Dispatchers.IO) {
        val payload = AesGcmCipher.encrypt(imageBytes, hexKey)
        sendEncryptedPayload(ip, port, payload, TYPE_IMAGE, connectMs)
    }

    // ── Large file streaming ───────────────────────────────────────────────────

    /**
     * Streams data from an InputStream directly to [ip]:[port] in [CHUNK_SIZE]-byte chunks.
     */
    suspend fun sendFileStream(
        ip: String,
        port:       Int,
        fileName:   String,
        fileSize:   Long,
        inputStreamProvider: () -> java.io.InputStream,
        hexKey:     String,
        connectMs:  Int = 3_000,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.tcpNoDelay = true // Disable Nagle's algorithm
            socket.sendBufferSize = 2 * 1024 * 1024
            socket.receiveBufferSize = 2 * 1024 * 1024
            socket.connect(InetSocketAddress(ip, port), connectMs)
            socket.soTimeout = 30_000  // 30 s read/write timeout

            val out = java.io.BufferedOutputStream(socket.getOutputStream(), 1024 * 1024)

            // ── Header ────────────────────────────────────────────────────────
            writeHeader(out, TYPE_FILE, fileSize, CHUNK_SIZE.toLong(), fileName)

            // ── Chunked streaming ─────────────────────────────────────────────
            val buffer = ByteArray(CHUNK_SIZE)
            try {
                inputStreamProvider().use { fis ->
                    var totalSent  = 0L
                    var bytesRead: Int = 0
                    var lastUpdate = System.currentTimeMillis()
                    var bytesSinceUpdate = 0L

                    while (isActive && fis.read(buffer).also { bytesRead = it } != -1) {
                        val plain          = if (bytesRead == CHUNK_SIZE) buffer else buffer.copyOf(bytesRead)
                        val encryptedChunk = AesGcmCipher.encrypt(plain, hexKey)

                        // 4-byte chunk length prefix so Mac knows how many bytes to read
                        val chunkLen = ByteBuffer.allocate(4).putInt(encryptedChunk.size).array()
                        out.write(chunkLen)
                        out.write(encryptedChunk)

                        totalSent += bytesRead
                        bytesSinceUpdate += bytesRead
                        
                        val now = System.currentTimeMillis()
                        val diff = now - lastUpdate
                        if (diff > 500) {
                            val speedMbps = (bytesSinceUpdate * 1000L / diff) / (1024.0 * 1024.0)
                            val speedStr = String.format("%.1f MB/s", speedMbps)
                            onProgress(totalSent.toFloat() / fileSize.toFloat(), speedStr)
                            lastUpdate = now
                            bytesSinceUpdate = 0
                        }
                    }
                    onProgress(1f, "0 MB/s")

                    out.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming file: ${e.message}")
                throw e
            }
        }

    }

    /**
     * Streams [file] directly from disk to [ip]:[port] using kernel zero-copy (FileChannel.transferTo).
     * Bypasses userspace memory and AES encryption completely for ultra-fast transfer speeds.
     */
    suspend fun sendZeroCopyFileStream(
        ip: String,
        port:       Int,
        fileName:   String,
        fileSize:   Long,
        fd:         java.io.FileDescriptor,
        connectMs:  Int = 3_000,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        
        SocketChannel.open().use { socketChannel ->
            socketChannel.socket().apply {
                tcpNoDelay = true
                sendBufferSize = 4 * 1024 * 1024 // 4MB send buffer
                receiveBufferSize = 4 * 1024 * 1024
                soTimeout = 30_000
            }
            socketChannel.connect(InetSocketAddress(ip, port))
            
            val out = java.io.BufferedOutputStream(socketChannel.socket().getOutputStream(), 8192)
            // Header with TYPE_FILE_FAST (0x04).
            // We use CHUNK_SIZE = fileSize since it's a single raw stream.
            writeHeader(out, TYPE_FILE_FAST, fileSize, fileSize, fileName)
            out.flush() // flush header before transferTo takes over the raw socket

            FileInputStream(fd).channel.use { fileChannel ->
                var position = 0L
                var lastUpdate = System.currentTimeMillis()
                var bytesSinceUpdate = 0L

                while (position < fileSize && isActive) {
                    val count = Math.min(4L * 1024 * 1024, fileSize - position) // Transfer in 4MB chunks
                    val transferred = fileChannel.transferTo(position, count, socketChannel)
                    if (transferred <= 0) {
                        // socket closed or error
                        break
                    }
                    position += transferred
                    bytesSinceUpdate += transferred

                    val now = System.currentTimeMillis()
                    val diff = now - lastUpdate
                    if (diff > 500) {
                        val speedMbps = (bytesSinceUpdate * 1000L / diff) / (1024.0 * 1024.0)
                        val speedStr = String.format("%.1f MB/s", speedMbps)
                        onProgress(position.toFloat() / fileSize.toFloat(), speedStr)
                        lastUpdate = now
                        bytesSinceUpdate = 0
                    }
                }
                onProgress(1f, "0 MB/s")
            }
        }
    }

    /**
     * Streams [file] to [ip]:[port] in [CHUNK_SIZE]-byte chunks.
     *
     * Each chunk is individually encrypted so the Mac can begin decrypting before
     * all bytes arrive (streaming decryption). Progress is reported via [onProgress]
     * as a value in [0.0, 1.0].
     *
     * @param file       The file to transfer (any MIME type: PDF, video, zip, …).
     * @param hexKey     64-char hex AES-256 session key.
     * @param onProgress Called on the IO thread with fraction [0.0, 1.0] and speed as bytes are sent.
     */
    suspend fun sendFile(
        ip: String,
        port:       Int,
        file:       File,
        hexKey:     String,
        connectMs:  Int = 3_000,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) {
        sendFileStream(
            ip = ip,
            port = port,
            fileName = file.name,
            fileSize = file.length(),
            inputStreamProvider = { FileInputStream(file) },
            hexKey = hexKey,
            connectMs = connectMs,
            onProgress = onProgress
        )
    }

    /**
     * Streams an image [file] from disk to [ip]:[port] using the TYPE_IMAGE header byte (0x02).
     *
     * Unlike [sendFile] which uses TYPE_FILE (0x03) and causes the Mac to save the
     * file to Downloads, this tells the Mac to write the data directly to the
     * clipboard as an NSImage — so CMD+V pastes the actual image.
     */
    suspend fun sendImageFile(
        ip: String,
        port:       Int,
        file:       File,
        hexKey:     String,
        connectMs:  Int = 3_000,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.sendBufferSize = 2 * 1024 * 1024
            socket.receiveBufferSize = 2 * 1024 * 1024
            socket.connect(InetSocketAddress(ip, port), connectMs)
            socket.soTimeout = 30_000

            val out = java.io.BufferedOutputStream(socket.getOutputStream(), 1024 * 1024)
            // Use TYPE_IMAGE so the Mac puts it on the clipboard, not Downloads
            writeHeader(out, TYPE_IMAGE, file.length(), CHUNK_SIZE.toLong(), file.name)

            val buffer = ByteArray(CHUNK_SIZE)
            FileInputStream(file).use { fis ->
                var totalSent = 0L
                var bytesRead: Int = 0
                var lastUpdate = System.currentTimeMillis()
                var bytesSinceUpdate = 0L
                while (isActive && fis.read(buffer).also { bytesRead = it } != -1) {
                    val plain = if (bytesRead == CHUNK_SIZE) buffer else buffer.copyOf(bytesRead)
                    val encryptedChunk = AesGcmCipher.encrypt(plain, hexKey)
                    val chunkLen = ByteBuffer.allocate(4).putInt(encryptedChunk.size).array()
                    out.write(chunkLen)
                    out.write(encryptedChunk)
                    totalSent += bytesRead
                    bytesSinceUpdate += bytesRead
                    val now = System.currentTimeMillis()
                    val diff = now - lastUpdate
                    if (diff > 500) {
                        val speedMbps = (bytesSinceUpdate * 1000L / diff) / (1024.0 * 1024.0)
                        onProgress(totalSent.toFloat() / file.length().toFloat(), "%.1f MB/s".format(speedMbps))
                        lastUpdate = now
                        bytesSinceUpdate = 0
                    }
                }
                onProgress(1f, "0 MB/s")
                out.flush()
            }
        }
    }

    // ── Probe ─────────────────────────────────────────────────────────────────

    /**
     * Tries to open a TCP connection to [ip]:[port] within [timeoutMs].
     * Returns `true` if the Mac's server is reachable (same LAN / hotspot).
     */
    fun probeTcp(ip: String, port: Int, timeoutMs: Int = 500): Boolean {
        return try {
            Socket().use { s ->
                s.sendBufferSize = 8 * 1024 * 1024
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sends a single pre-encrypted [payload] blob: header + one chunk.
     * Used for small payloads where the entire content fits in memory.
     */
    private suspend fun sendEncryptedPayload(
        ip:   String,
        port:       Int,
        payload:    ByteArray,
        type:       Byte,
        connectMs:  Int
    ) = withContext(Dispatchers.IO) {

        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.sendBufferSize = 8 * 1024 * 1024
            socket.receiveBufferSize = 8 * 1024 * 1024
            socket.connect(InetSocketAddress(ip, port), connectMs)
            socket.soTimeout = 30_000

            val out = socket.getOutputStream()

            // Header
            writeHeader(out, type, payload.size.toLong(), payload.size.toLong(), null)

            // Single chunk: 4-byte length + data
            val chunkLen = ByteBuffer.allocate(4).putInt(payload.size).array()
            out.write(chunkLen)
            out.write(payload)
            out.flush()
        }
    }

    private fun writeHeader(
        out:       java.io.OutputStream,
        type:      Byte,
        totalSize: Long,
        chunkSize: Long,
        fileName:  String?
    ) {
        val fileNameBytes = fileName?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        
        val header = ByteBuffer.allocate(24 + 4 + fileNameBytes.size).apply {
            putInt(MAGIC)                   // 4 bytes
            put(VERSION)                    // 1 byte
            put(type)                       // 1 byte
            putShort(0)                     // 2 bytes reserved
            putLong(totalSize)              // 8 bytes
            putLong(chunkSize)              // 8 bytes
            putInt(fileNameBytes.size)      // 4 bytes
            put(fileNameBytes)              // N bytes
        }.array()
        out.write(header)
    }
}
