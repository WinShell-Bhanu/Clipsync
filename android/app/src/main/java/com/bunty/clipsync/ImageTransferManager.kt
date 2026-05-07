package com.bunty.clipsync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*

/**
 * Tier 1 image transfer orchestrator for Android.
 *
 * Handles both directions:
 *   - **Sending** (Android → Mac): NSD discovery of `_clipsync._tcp` → HTTP POST /receive-image
 *   - **Receiving** (Mac → Android): Embedded HTTP server on port 58486 advertising `_clipsync._tcp`
 *
 * All image payloads are encrypted with AES-256-GCM using the session key from [DeviceManager]
 * and authenticated with HMAC-SHA256 before transmission.
 *
 * The state machine enforces aggressive timeouts (2s discovery, 5s connect, 30s upload)
 * and surfaces every failure as a Toast — zero silent failures.
 */
object ImageTransferManager {

    private const val TAG = "ImageTransfer"
    private const val SERVICE_TYPE = "_clipsync._tcp."
    private const val MAC_SERVICE_TYPE = "_clipsyncmac._tcp."
    private const val SERVICE_NAME = "ClipSync"
    const val LOCAL_SERVER_PORT = 58486
    private const val BUFFER_SIZE = 8192

    // Timeouts
    private const val DISCOVERY_TIMEOUT_MS = 8000L  // NsdManager can take 4-8s on a real LAN
    private const val RESOLVE_TIMEOUT_MS   = 10000L // Safety net if resolveService() hangs
    private const val CONNECTION_TIMEOUT_MS = 5000
    private const val UPLOAD_TIMEOUT_MS = 30000

    // ── State Machine ─────────────────────────────────────────────────────────

    enum class TransferState {
        IDLE, DISCOVERING, CONNECTING, COMPLETED, FAILED
    }

    @Volatile
    var currentState: TransferState = TransferState.IDLE
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── NSD Discovery ─────────────────────────────────────────────────────────

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private var isDiscovering = false

    // ── Local Server (receives images from Mac) ───────────────────────────────

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var isServerRunning = false

    /**
     * Set by the local server after writing an image to the clipboard.
     * [ClipboardAccessibilityService] should check this to suppress echo.
     */
    @Volatile
    var ignoreNextClipboardChange = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the local image reception server and registers via mDNS.
     * Safe to call multiple times.
     */
    fun startServer(context: Context) {
        if (isServerRunning) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                startLocalServer(appContext)
                registerNsdService(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start image server", e)
            }
        }
    }

    /**
     * Stops the local server and unregisters the mDNS service.
     */
    fun stopServer(context: Context) {
        val appContext = context.applicationContext
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        isServerRunning = false
        unregisterNsdService(appContext)
    }

    // ── Sending: Android → Mac ────────────────────────────────────────────────

    /**
     * Sends an image from the Android clipboard to the paired Mac.
     *
     * @param context     Application context.
     * @param imageBytes  Raw image bytes (PNG/JPEG) read from the clipboard URI.
     */
    fun sendImageToMac(context: Context, imageBytes: ByteArray) {
        val appContext = context.applicationContext

        Log.d(TAG, "sendImageToMac called — size=${imageBytes.size} bytes, state=$currentState")

        if (currentState == TransferState.DISCOVERING || currentState == TransferState.CONNECTING) {
            Log.w(TAG, "Transfer already in progress (state=$currentState) — skipping")
            return
        }

        // Pre-flight checks
        if (!DeviceManager.isPaired(appContext)) {
            Log.e(TAG, "❌ Not paired — aborting send")
            reportError(appContext, "Pair with a Mac first")
            return
        }
        val encryptionKey = DeviceManager.getEncryptionKey(appContext)
        if (encryptionKey == null) {
            Log.e(TAG, "❌ No encryption key — re-pair required")
            reportError(appContext, "Security error — re-pair required")
            return
        }

        Log.d(TAG, "Pre-flight OK — starting NSD discovery for $MAC_SERVICE_TYPE (timeout=${DISCOVERY_TIMEOUT_MS}ms)")
        transitionTo(TransferState.DISCOVERING)
        discoverMacAndSend(appContext, imageBytes, encryptionKey)
    }

    /**
     * Uses NsdManager to find the Mac's `_clipsync._tcp` service, then sends the image.
     */
    private fun discoverMacAndSend(context: Context, imageBytes: ByteArray, encryptionKey: String) {
        val nsm = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsm

        // Timeout handler: abort if the Mac is not found within DISCOVERY_TIMEOUT_MS.
        val timeoutRunnable = Runnable {
            if (currentState == TransferState.DISCOVERING) {
                Log.e(TAG, "NSD discovery timeout after ${DISCOVERY_TIMEOUT_MS}ms — no $MAC_SERVICE_TYPE service found")
                stopDiscovery()
                transitionTo(TransferState.FAILED)
                reportError(context, "Mac not found on this network")
            }
        }
        mainHandler.postDelayed(timeoutRunnable, DISCOVERY_TIMEOUT_MS)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started — type=$serviceType, waiting up to ${DISCOVERY_TIMEOUT_MS}ms")
                isDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: name='${service.serviceName}' type='${service.serviceType}'")
                // Only resolve services that look like our Mac app.
                if (!service.serviceName.contains("ClipSync", ignoreCase = true)) {
                    Log.d(TAG, "NSD: ignoring unrelated service '${service.serviceName}'")
                    return
                }
                Log.d(TAG, "NSD: matched ClipSync service '${service.serviceName}' — cancelling timeout and resolving")

                mainHandler.removeCallbacks(timeoutRunnable)
                stopDiscovery()

                resolveService(nsm, context, service, imageBytes, encryptionKey)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "NSD: service lost '${service.serviceName}'")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD: discovery stopped for $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD: discovery start FAILED for $serviceType — errorCode=$errorCode")
                mainHandler.removeCallbacks(timeoutRunnable)
                isDiscovering = false
                transitionTo(TransferState.FAILED)
                reportError(context, "Network discovery failed (code $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD: stop discovery failed — errorCode=$errorCode")
                isDiscovering = false
            }
        }

        this.discoveryListener = listener

        try {
            nsm.discoverServices(MAC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            mainHandler.removeCallbacks(timeoutRunnable)
            transitionTo(TransferState.FAILED)
            reportError(context, "Network discovery failed")
        }
    }

    private fun resolveService(
        nsm: NsdManager,
        context: Context,
        service: NsdServiceInfo,
        imageBytes: ByteArray,
        encryptionKey: String
    ) {
        // Safety-net timeout: if neither resolve callback fires, unblock the state machine.
        val resolveTimeout = Runnable {
            if (currentState == TransferState.DISCOVERING) {
                Log.e(TAG, "NSD: resolve timeout after ${RESOLVE_TIMEOUT_MS}ms — no callback received")
                transitionTo(TransferState.FAILED)
                reportError(context, "Mac resolve timed out")
            }
        }
        mainHandler.postDelayed(resolveTimeout, RESOLVE_TIMEOUT_MS)

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                mainHandler.removeCallbacks(resolveTimeout)
                Log.e(TAG, "NSD: resolve FAILED for '${serviceInfo.serviceName}' — errorCode=$errorCode")
                transitionTo(TransferState.FAILED)
                reportError(context, "Could not reach Mac — resolve failed (code $errorCode)")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                mainHandler.removeCallbacks(resolveTimeout)
                val host = serviceInfo.host?.hostAddress
                val port = serviceInfo.port
                Log.d(TAG, "NSD: resolved '${serviceInfo.serviceName}' → host=$host port=$port")
                if (host == null) {
                    Log.e(TAG, "NSD: ❌ resolved but host is null")
                    transitionTo(TransferState.FAILED)
                    reportError(context, "Mac address not resolved")
                    return
                }

                if (host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") {
                    Log.e(TAG, "NSD: ❌ resolved to loopback/invalid address $host — cannot reach Mac")
                    transitionTo(TransferState.FAILED)
                    reportError(context, "Mac resolved to loopback address — check network")
                    return
                }

                Log.d(TAG, "HTTP: connecting to Mac at $host:$port — payload=${imageBytes.size} bytes")
                transitionTo(TransferState.CONNECTING)
                scope.launch {
                    performHTTPSend(context, host, port, imageBytes, encryptionKey)
                }
            }
        }

        this.resolveListener = listener

        try {
            nsm.resolveService(service, listener)
        } catch (e: Exception) {
            mainHandler.removeCallbacks(resolveTimeout)
            Log.e(TAG, "Failed to resolve service", e)
            transitionTo(TransferState.FAILED)
            reportError(context, "Service resolve failed")
        }
    }

    /**
     * Encrypts the image, computes HMAC, and sends via HTTP POST to the Mac's LocalImageServer.
     */
    private suspend fun performHTTPSend(
        context: Context,
        host: String,
        port: Int,
        imageBytes: ByteArray,
        encryptionKey: String
    ) {
        try {
            // 1. Encrypt image with AES-256-GCM
            val encryptedData = encryptImageData(imageBytes, encryptionKey)

            // 2. Compute HMAC-SHA256 signature
            val signature = computeHMAC(encryptedData, encryptionKey)

            // 3. Get pairing ID
            val pairingId = DeviceManager.getPairingId(context)
                ?: throw IllegalStateException("No pairing ID")

            // 4. HTTP POST
            val url = URL("http", host, port, "/receive-image")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECTION_TIMEOUT_MS
                readTimeout = UPLOAD_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Content-Length", encryptedData.size.toString())
                setRequestProperty("X-Pairing-Id", pairingId)
                setRequestProperty("X-Signature", signature)
                setRequestProperty("Connection", "close")
            }

            // 5. Stream the encrypted body (8 KB chunks to avoid OOM)
            BufferedOutputStream(connection.outputStream, BUFFER_SIZE).use { out ->
                var offset = 0
                while (offset < encryptedData.size) {
                    val chunkSize = minOf(BUFFER_SIZE, encryptedData.size - offset)
                    out.write(encryptedData, offset, chunkSize)
                    offset += chunkSize
                }
                out.flush()
            }

            // 6. Read response
            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode == 200) {
                Log.d(TAG, "HTTP: ✅ image sent successfully (${imageBytes.size} raw bytes, encrypted=${encryptedData.size} bytes, HTTP $responseCode)")
                transitionTo(TransferState.COMPLETED)
                showToast(context, "Image sent to Mac ✓")
            } else {
                Log.e(TAG, "HTTP: ❌ Mac returned HTTP $responseCode for /receive-image")
                transitionTo(TransferState.FAILED)
                reportError(context, "Transfer failed (HTTP $responseCode)")
            }

        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Upload timeout", e)
            transitionTo(TransferState.FAILED)
            reportError(context, "Transfer timed out")
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Connection refused", e)
            transitionTo(TransferState.FAILED)
            reportError(context, "Could not reach Mac — check firewall")
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            transitionTo(TransferState.FAILED)
            reportError(context, "Image transfer failed")
        }
    }

    // ── Receiving: Mac → Android (embedded HTTP server) ───────────────────────

    /**
     * Starts a minimal HTTP server on [LOCAL_SERVER_PORT] that accepts
     * `POST /receive-image` requests from the Mac.
     */
    private fun startLocalServer(context: Context) {
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOCAL_SERVER_PORT))
        }
        isServerRunning = true
        Log.d(TAG, "Local image server started on port $LOCAL_SERVER_PORT")

        serverJob = scope.launch {
            while (isActive && isServerRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch { handleClientConnection(context, clientSocket) }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Server accept error", e)
                }
            }
        }
    }

    /**
     * Handles a single inbound HTTP connection from the Mac.
     * Validates pairing, verifies HMAC, decrypts, and writes image to clipboard.
     */
    private suspend fun handleClientConnection(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 30_000 // 30s read timeout
            val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
            val output = socket.getOutputStream()

            // Parse HTTP headers
            val headers = parseHTTPHeaders(input) ?: run {
                Log.e(TAG, "Failed to parse HTTP headers from Mac — sending 400")
                sendHTTPResponse(output, 400)
                return
            }

            // Security gate 1: verify endpoint
            val requestLine = headers["request-line"] ?: ""
            if (!requestLine.contains("POST /receive-image")) {
                sendHTTPResponse(output, 404)
                return
            }

            // Security gate 2: verify pairing ID
            val incomingPairingId = headers["x-pairing-id"]
            val currentPairingId = DeviceManager.getPairingId(context)
            if (incomingPairingId == null || currentPairingId == null || incomingPairingId != currentPairingId) {
                Log.w(TAG, "Rejected: pairing ID mismatch")
                sendHTTPResponse(output, 403)
                return
            }

            // Read body
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0 || contentLength > 50 * 1024 * 1024) { // 50 MB max
                sendHTTPResponse(output, 400)
                return
            }

            val body = readBody(input, contentLength)

            // Security gate 3: verify HMAC signature
            val encryptionKey = DeviceManager.getEncryptionKey(context)
            if (encryptionKey == null) {
                Log.w(TAG, "Rejected: no encryption key available")
                sendHTTPResponse(output, 403)
                return
            }

            val signature = headers["x-signature"] ?: ""
            if (!verifyHMAC(body, signature, encryptionKey)) {
                Log.w(TAG, "Rejected: HMAC signature mismatch")
                sendHTTPResponse(output, 401)
                return
            }

            // Security gate 4: decrypt
            val decryptedData = decryptImageData(body, encryptionKey)
            if (decryptedData == null) {
                Log.w(TAG, "Rejected: decryption failed")
                sendHTTPResponse(output, 400)
                return
            }

            Log.d(TAG, "Received image from Mac (${decryptedData.size} bytes)")

            // Write to clipboard via ClipboardGhostActivity
            writeImageToClipboard(context, decryptedData)

            sendHTTPResponse(output, 200)

        } catch (e: Exception) {
            Log.e(TAG, "Client connection error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Writes decrypted image bytes to the Android clipboard and suppresses echo.
     *
     * Uses a dedicated subdirectory (`clipsync_images/`) that matches the restricted
     * path declared in `file_paths.xml`. Each image gets a unique timestamp-based
     * filename to avoid race conditions if two images arrive in quick succession.
     * Old images are pruned to cap disk usage.
     *
     * On API 26+ the system ClipboardService automatically grants
     * `FLAG_GRANT_READ_URI_PERMISSION` to any app that calls `getPrimaryClip()`,
     * so WhatsApp / Instagram / etc. can read the FileProvider URI without extra work.
     */
    private fun writeImageToClipboard(context: Context, imageBytes: ByteArray) {
        try {
            val imageDir = java.io.File(context.cacheDir, "clipsync_images")
            imageDir.mkdirs()

            // Unique filename prevents overwrites when images arrive back-to-back.
            val imageFile = java.io.File(imageDir, "img_${System.currentTimeMillis()}.png")
            imageFile.writeBytes(imageBytes)

            // Housekeeping: keep only the 5 most recent images to limit disk usage.
            cleanupOldImages(imageDir, keep = 5)

            // Set the ignore flag so the accessibility service doesn't echo this paste.
            ignoreNextClipboardChange = true

            mainHandler.post {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        imageFile
                    )
                    val clip = android.content.ClipData.newUri(context.contentResolver, "ClipSync Image", uri)
                    clipboard.setPrimaryClip(clip)
                    Log.d(TAG, "Image written to clipboard via content URI: $uri")

                    // Clear the ignore flag after a delay.
                    mainHandler.postDelayed({ ignoreNextClipboardChange = false }, 2000)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write image to clipboard", e)
                    ignoreNextClipboardChange = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save received image", e)
            ignoreNextClipboardChange = false
        }
    }

    /**
     * Deletes the oldest files in [directory], keeping only the [keep] most recent.
     */
    private fun cleanupOldImages(directory: java.io.File, keep: Int) {
        try {
            val files = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(keep).forEach { it.delete() }
        } catch (_: Exception) {}
    }

    // ── HTTP parsing helpers ──────────────────────────────────────────────────

    /**
     * Reads HTTP/1.1 headers from the input stream line by line.
     * Returns a map of lowercased header names to values, plus "request-line".
     */
    private fun parseHTTPHeaders(input: InputStream): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        val lineBuffer = StringBuilder()
        var isFirstLine = true
        var prevChar = 0

        // Read byte-by-byte until we hit \r\n\r\n
        while (true) {
            val b = input.read()
            if (b == -1) return null

            if (b == '\n'.code && prevChar == '\r'.code) {
                val line = lineBuffer.toString().trimEnd('\r')
                lineBuffer.clear()

                if (line.isEmpty()) break // End of headers (\r\n\r\n)

                if (isFirstLine) {
                    headers["request-line"] = line
                    isFirstLine = false
                } else {
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        val name = line.substring(0, colonIndex).trim().lowercase()
                        val value = line.substring(colonIndex + 1).trim()
                        headers[name] = value
                    }
                }
            } else if (b != '\r'.code) {
                lineBuffer.append(b.toChar())
            }
            prevChar = b
        }

        return if (headers.isNotEmpty()) headers else null
    }

    /**
     * Reads exactly [length] bytes from [input] in 8 KB chunks.
     */
    private fun readBody(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(BUFFER_SIZE)
        val result = ByteArrayOutputStream(length)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(BUFFER_SIZE, remaining)
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            result.write(buffer, 0, read)
            remaining -= read
        }
        return result.toByteArray()
    }

    private fun sendHTTPResponse(output: java.io.OutputStream, statusCode: Int) {
        val statusLine = if (statusCode == 200) "200 OK" else "$statusCode Error"
        val response = "HTTP/1.1 $statusLine\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    // ── NSD registration (advertise Android server) ───────────────────────────

    private fun registerNsdService(context: Context) {
        val nsm = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsm

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = "_clipsync._tcp"
            port = LOCAL_SERVER_PORT
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: error $errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: error $errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service registered: ${serviceInfo.serviceName}")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service unregistered")
            }
        }

        nsdRegistration = listener

        try {
            nsm.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    private fun unregisterNsdService(context: Context) {
        nsdRegistration?.let { listener ->
            try {
                val nsm = context.getSystemService(Context.NSD_SERVICE) as NsdManager
                nsm.unregisterService(listener)
            } catch (_: Exception) {}
        }
        nsdRegistration = null
    }

    // ── Discovery control ─────────────────────────────────────────────────────

    private fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                if (isDiscovering) {
                    nsdManager?.stopServiceDiscovery(listener)
                }
            } catch (_: Exception) {}
        }
        discoveryListener = null
        isDiscovering = false
    }

    // ── Encryption ────────────────────────────────────────────────────────────

    /**
     * Encrypts raw image bytes with AES-256-GCM.
     * Output: [12-byte IV][ciphertext][16-byte GCM tag]
     */
    private fun encryptImageData(imageBytes: ByteArray, keyHex: String): ByteArray {
        val keySpec = SecretKeySpec(hexStringToByteArray(keyHex), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(imageBytes)
        // Concatenate: IV + ciphertext (includes GCM tag appended by JCE)
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    /**
     * Decrypts AES-256-GCM encrypted image data.
     * Expected: [12-byte IV][ciphertext][16-byte GCM tag]
     */
    private fun decryptImageData(encryptedData: ByteArray, keyHex: String): ByteArray? {
        return try {
            if (encryptedData.size < 28) return null // 12 IV + 16 tag minimum
            val keySpec = SecretKeySpec(hexStringToByteArray(keyHex), "AES")
            val iv = encryptedData.copyOfRange(0, 12)
            val ciphertext = encryptedData.copyOfRange(12, encryptedData.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Image decryption failed", e)
            null
        }
    }

    /**
     * Computes HMAC-SHA256 over [data] using the session key and returns Base64-encoded result.
     */
    private fun computeHMAC(data: ByteArray, keyHex: String): String {
        val keySpec = SecretKeySpec(hexStringToByteArray(keyHex), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val hmacBytes = mac.doFinal(data)
        return android.util.Base64.encodeToString(hmacBytes, android.util.Base64.NO_WRAP)
    }

    /**
     * Verifies HMAC-SHA256 signature with constant-time comparison.
     */
    private fun verifyHMAC(data: ByteArray, base64Signature: String, keyHex: String): Boolean {
        return try {
            val expectedSignature = computeHMAC(data, keyHex)
            java.security.MessageDigest.isEqual(
                expectedSignature.toByteArray(Charsets.UTF_8),
                base64Signature.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            Log.e(TAG, "HMAC verification error", e)
            false
        }
    }

    // ── State management ──────────────────────────────────────────────────────

    private fun transitionTo(newState: TransferState) {
        currentState = newState
        when (newState) {
            TransferState.COMPLETED -> {
                // Auto-reset after 2 seconds
                mainHandler.postDelayed({ if (currentState == TransferState.COMPLETED) currentState = TransferState.IDLE }, 2000)
            }
            TransferState.FAILED -> {
                // Auto-reset after 3 seconds
                mainHandler.postDelayed({ if (currentState == TransferState.FAILED) currentState = TransferState.IDLE }, 3000)
            }
            else -> {}
        }
    }

    // ── UI feedback ───────────────────────────────────────────────────────────

    private fun reportError(context: Context, message: String) {
        Log.e(TAG, "Error: $message")
        showToast(context, "ClipSync: $message")
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Hex helper ────────────────────────────────────────────────────────────

    private fun hexStringToByteArray(s: String): ByteArray {
        require(s.length % 2 == 0) { "Invalid hex length: ${s.length}" }
        val data = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val high = Character.digit(s[i], 16)
            val low = Character.digit(s[i + 1], 16)
            require(high != -1 && low != -1) { "Invalid hex character at index $i" }
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }
}
