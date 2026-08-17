package com.bunty.clipsync

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.CancellationException
import android.os.ParcelFileDescriptor

data class SharedFile(
    val fileName: String,
    val fileSize: Long,
    val pfd: ParcelFileDescriptor? = null,
    val uri: Uri? = null,
    val file: File? = null
)

/**
 * LocalSyncManager — the brain of the WiFi+BLE transfer engine.
 *
 * Responsibilities:
 * 1. Discover the Mac's LAN IP via NSD/mDNS (service type: `_clipsync._tcp.`).
 * 2. Probe reachability (500 ms TCP probe).
 * 3. If reachable → send BLE wakeup ping → open TCP → stream encrypted payload.
 * 4. If not reachable → start LocalOnlyHotspot → send fresh BLE ping → stream.
 * 5. If hotspot also fails (or sync mode ≠ hybrid):
 *      - hybrid mode  → prompt user for cloud (Firestore) transfer
 *      - local mode   → notify user; no cloud fallback
 *
 * The [state] flow drives the [LocalNetworkScreen] UI so every step is reflected live.
 */
object LocalSyncManager {

    private const val TAG                  = "LocalSync"
    const val TCP_PORT                     = 8765
    private const val TCP_PORT_FALLBACK    = 8765
    private const val LAN_PROBE_MS         = 3000
    /** How long to wait for BLE IP resolution before falling back to cached IP (ms). */
    private const val BLE_IP_RESOLVE_MS    = 8_000L
    private const val NOTIF_CHANNEL        = "clipsync_file_transfer"
    private const val SEND_NOTIF_ID        = 7702

    // ── State ─────────────────────────────────────────────────────────────────

    sealed class SyncState {
        object Idle                        : SyncState()
        object CheckingWifi                : SyncState()
        object DiscoveringMac              : SyncState()
        data class Found(val macIp: String): SyncState()
        object SendingWakeup               : SyncState()
        object Connecting                  : SyncState()
        data class Streaming(val progress: Float, val speedString: String = "0 MB/s") : SyncState()
        object Success                     : SyncState()
        /** Queued — waiting for user to decide on cloud transfer. */
        data class PendingCloudApproval(val contentSizeBytes: Long) : SyncState()
        data class Failed(val reason: String) : SyncState()
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var cachedMacIp: String? = null
    private var cachedMacIpTime: Long = 0L
    private var activeJob: Job? = null

    /** How long (ms) a BLE-resolved Mac IP stays trusted before we re-resolve. */
    private const val IP_CACHE_TTL_MS = 120_000L   // 2 minutes

    private fun describeState(state: SyncState): String = when (state) {
        SyncState.Idle -> "Idle"
        SyncState.CheckingWifi -> "CheckingWifi"
        SyncState.DiscoveringMac -> "DiscoveringMac"
        is SyncState.Found -> "Found(macIp=${state.macIp})"
        SyncState.SendingWakeup -> "SendingWakeup"
        SyncState.Connecting -> "Connecting"
        is SyncState.Streaming -> "Streaming(progress=${"%.2f".format(state.progress)}, speed=${state.speedString})"
        SyncState.Success -> "Success"
        is SyncState.PendingCloudApproval -> "PendingCloudApproval(size=${state.contentSizeBytes})"
        is SyncState.Failed -> "Failed(reason=${state.reason})"
    }

    private fun setState(state: SyncState, reason: String) {
        _state.value = state
    }

    fun cancelTransfer() {
        activeJob?.cancel()
        setState(SyncState.Failed("Cancelled by user"), "User initiated cancel")
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Called from [ClipboardAccessibilityService] whenever clipboard content changes.
     *
     * Picks the fastest available route and streams the payload to the Mac.
     *
     * @param context     Application context.
     * @param content     Plain-text clipboard content (will be UTF-8 encoded).
     * @param contentType "text" | "image" | "file"
     * @param file        Populated only when [contentType] == "file".
     */
    fun onClipboardContent(
        context:     Context,
        content:     String,
        contentType: String = "text",
        file:        File?  = null,
        uri:         Uri?   = null
    ) {
        activeJob?.cancel()
        activeJob = scope.launch {
            trySendWithRoute(context, content, contentType, file, uri)
        }
    }

    fun onClipboardContentSharedFiles(context: Context, sharedFiles: List<SharedFile>) {
        if (sharedFiles.isEmpty()) return
        activeJob?.cancel()
        activeJob = scope.launch {
            for ((index, sf) in sharedFiles.withIndex()) {
                trySendWithRoute(context, "", "file", sharedFile = sf)
            }
            setState(SyncState.Idle, "Finished sending ${sharedFiles.size} files")
        }
    }

    /**
     * Called from [LocalNetworkScreen] during onboarding.
     *
     * This is a connectivity handshake, not a clipboard transfer: it verifies that the
     * Mac's TCP listener is reachable before onboarding moves to the connected screen.
     */
    fun startOnboardingHandshake(context: Context) {
        activeJob?.cancel()
        activeJob = scope.launch {
            verifyLocalRoute(context.applicationContext)
        }
    }

    /**
     * Passive pre-warm: sends a BLE ping to resolve the Mac's IP and cache it,
     * so the first real transfer doesn't need to wait for BLE resolution.
     */
    fun startDiscovery(context: Context) {
        scope.launch {
            if (!isWifiConnected(context)) return@launch
            resolveMacIpViaBle(context)?.let { ip ->
            }
        }
    }

    /** Approve cloud transfer after user confirmation. */
    fun approveCloudTransfer(context: Context, content: String) {
        scope.launch {
            withContext(Dispatchers.Main) {
                FirestoreManager.sendClipboard(
                    context   = context,
                    text      = content,
                    onSuccess = { _state.value = SyncState.Success },
                    onFailure = { e -> _state.value = SyncState.Failed("Cloud: ${e.message}") }
                )
            }
        }
    }

    fun stopDiscovery() {
        // No-op: NSD removed; BLE discovery is event-driven
    }

    fun resetToIdle() {
        setState(SyncState.Idle, "reset requested")
    }

    /**
     * Sent immediately after Android scans a local-only QR code.
     *
     * Now returns whether the ack was actually delivered (GATT write-with-response
     * acknowledged by the Mac's Bluetooth stack) instead of firing blind. Callers
     * must branch on the result — a failed ack means the Mac may not have seen this
     * scan and may still be stuck on QRGen.swift.
     */
    suspend fun sendPairingScanAck(context: Context): Boolean {
        val appContext = context.applicationContext
        val myIp = getLocalIpAddress(context) ?: ""
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val info = wifiManager?.connectionInfo
        val rawSsid = info?.ssid?.removeSurrounding("\"")
        val ssid = if (rawSsid.isNullOrBlank()) null else if (rawSsid == "<unknown ssid>") (if (info.networkId != -1) "Wi-Fi Connected" else null) else rawSsid
        val deviceName = DeviceManager.getAndroidDeviceName(context)

        repeat(3) { attempt ->
            val ping = WakeupPing(
                localIp = myIp,
                tcpPort = TCP_PORT,
                payloadSize = 0,
                payloadType = "pairing_ack",
                battery = battery,
                network = ssid,
                deviceName = deviceName
            )

            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            WakeupPingSender.send(
                context = appContext,
                ping = ping,
                onSent = { resolvedIp, resolvedPort ->
                    if (resolvedIp.isNotEmpty()) {
                        cachedMacIp = resolvedIp
                        DeviceManager.saveMacLocalEndpoint(context, resolvedIp, resolvedPort)
                    }
                    deferred.complete(true)
                },
                onFailed = { reason ->
                    Log.w(TAG, "Local QR scan ack failed (attempt ${attempt + 1}): $reason")
                    deferred.complete(false)
                }
            )

            if (deferred.await()) {
                return true
            }
            if (attempt < 2) delay(700)
        }
        Log.e(TAG, "Pairing ack failed after 3 attempts — Mac may still be stuck on QRGen")
        return false
    }

    // ── Core routing logic ────────────────────────────────────────────────────

    private suspend fun trySendWithRoute(
        context:     Context,
        content:     String,
        contentType: String,
        file:        File? = null,
        uri:         Uri? = null,
        sharedFile:  SharedFile? = null
    ) {
        setState(SyncState.CheckingWifi, "transfer route: starting")

        val hexKey = DeviceManager.getEncryptionKey(context)
        if (hexKey.isNullOrEmpty()) {
            setState(SyncState.Failed("Not paired — no encryption key"), "transfer route aborted")
            return
        }

        val payloadSize = when {
            uri != null -> {
                var size = -1L
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                        size = it.length
                    }
                } catch (_: Exception) {}
                if (size <= 0) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0) {
                                size = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                }
                size
            }
            file != null -> file.length()
            else -> content.toByteArray().size.toLong()
        }

        // If the payload is small enough (text or small files), push it directly
        // over the BLE wakeup ping to completely bypass TCP latency.
        // DO NOT use this for PFD-based sharedFiles (to avoid advancing the cursor and ruining Zero-Copy).
        if (payloadSize <= 4000 && sharedFile == null) {
            val payloadBytes = when {
                uri != null -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                file != null -> file.readBytes()
                else -> content.toByteArray(Charsets.UTF_8)
            }
            val encryptedPayloadBytes = AesGcmCipher.encrypt(payloadBytes, hexKey)
            val base64Payload = android.util.Base64.encodeToString(encryptedPayloadBytes, android.util.Base64.NO_WRAP)
            
            val info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo
            val rawSsid = info?.ssid?.removeSurrounding("\"")
            val ssid = if (rawSsid.isNullOrBlank()) null else if (rawSsid == "<unknown ssid>") (if (info.networkId != -1) "Wi-Fi Connected" else null) else rawSsid
            
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val ping = WakeupPing(
                localIp = "",
                tcpPort = 0,
                payloadSize = payloadSize,
                payloadType = contentType,
                directPayload = base64Payload,
                battery = (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
                network = ssid,
                deviceName = DeviceManager.getAndroidDeviceName(context)
            )
            WakeupPingSender.send(
                context = context,
                ping = ping,
                onSent = { _, _ -> deferred.complete(true) },
                onFailed = { deferred.complete(false) }
            )
            
            val bleSuccess = deferred.await()
            if (bleSuccess) {
                _state.value = SyncState.Success
                com.bunty.clipsync.db.HistoryRepository.getInstance(context).addSent(
                    content = if (contentType == "file" || uri != null || file != null) "File Sent" else if (contentType == "image") "Image Sent" else content,
                    type = contentType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                )
                // Always clean up temp cache files after BLE send
                file?.delete()
                return
            }
            // BLE failed — still clean up so it doesn't orphan if TCP also fails later
            // (TCP path will re-read from URI if available, so file deletion is safe here only
            //  when uri is the source of truth. If file is the sole source, keep it for TCP.)
            if (uri != null) file?.delete()
        }

        if (!isWifiConnected(context)) {
            Log.w(TAG, "Transfer route: Wi-Fi is not connected/enabled")
            handleNoRoute(context, content, contentType)
            return
        }

        // 1. Get Mac's live IP via BLE (or fall back to recent cache)
        val macIp = resolveMacIpViaBle(context)
            ?: DeviceManager.getMacLocalIp(context)
        val macPort = DeviceManager.getMacLocalPort(context)

        if (macIp != null && probeLan(macIp, macPort, "transfer BLE-resolved IP")) {
            sendViaTcp(context, macIp, content, contentType, file, uri, hexKey, sharedFile)
        } else {
            // BLE-resolved IP not reachable or BLE unavailable
            handleNoRoute(context, content, contentType)
        }
    }

    private suspend fun sendViaTcp(
        context:     Context,
        macIp:       String,
        content:     String,
        contentType: String,
        file:        File?,
        uri:         Uri?,
        hexKey:      String,
        sharedFile:  SharedFile? = null
    ) {
        setState(SyncState.Found(macIp), "TCP send route selected")

        val payloadSize = when {
            sharedFile != null -> sharedFile.fileSize
            uri != null -> {
                var size = -1L
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                        size = it.length
                    }
                } catch (_: Exception) {}
                if (size <= 0) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0) {
                                size = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                }
                size
            }
            file != null -> file.length()
            else -> content.toByteArray().size.toLong()
        }

        setState(SyncState.Connecting, "connecting to $macIp:$TCP_PORT")

        val macPort = DeviceManager.getMacLocalPort(context)
        sendBleWakeup(context, macIp, payloadSize, contentType)

        setState(SyncState.Connecting, "connecting to $macIp:$macPort")

        try {
            when {
                sharedFile != null -> {
                    setState(SyncState.Streaming(0f), "file transfer started")
                    
                    val uftEnabled = DeviceManager.isUltraFastModeEnabled(context)
                    if (uftEnabled && sharedFile.pfd != null) {
                        // ULTRA FAST MODE — Zero copy, no encryption, bypasses JVM
                        ClipSyncSender.sendZeroCopyFileStream(
                            ip = macIp,
                            port = macPort,
                            fileName = sharedFile.fileName,
                            fileSize = sharedFile.fileSize,
                            fd = sharedFile.pfd.fileDescriptor,
                            onProgress = { p, s -> 
                                setState(SyncState.Streaming(p, s), "zero copy progress")
                                if (payloadSize > 1_000_000) showSendProgressNotification(context, sharedFile.fileName, (p * 100).toInt(), payloadSize)
                            }
                        )
                    } else {
                        // STANDARD MODE — Encrypted stream
                        ClipSyncSender.sendFileStream(
                            ip = macIp,
                            port = macPort,
                            fileName = sharedFile.fileName,
                            fileSize = sharedFile.fileSize,
                            inputStreamProvider = { 
                                if (sharedFile.pfd != null) java.io.FileInputStream(sharedFile.pfd.fileDescriptor)
                                else context.contentResolver.openInputStream(sharedFile.uri!!) ?: throw Exception("Failed to open stream")
                            },
                            hexKey = hexKey,
                            onProgress = { p, s -> 
                                setState(SyncState.Streaming(p, s), "file transfer progress")
                                if (payloadSize > 1_000_000) showSendProgressNotification(context, sharedFile.fileName, (p * 100).toInt(), payloadSize)
                            }
                        )
                    }
                    
                    // Cleanup PFD after transfer (whether successful or not)
                    try { sharedFile.pfd?.close() } catch (e: Exception) {}
                }
                uri != null -> {
                    var fileName = "shared_file"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                fileName = cursor.getString(nameIndex)
                            }
                        }
                    }

                    setState(SyncState.Streaming(0f), "file transfer started")
                    ClipSyncSender.sendFileStream(
                        ip = macIp,
                        port = macPort,
                        fileName = fileName,
                        fileSize = payloadSize,
                        inputStreamProvider = { context.contentResolver.openInputStream(uri) ?: throw Exception("Failed to open stream") },
                        hexKey = hexKey,
                        onProgress = { p, s -> 
                            setState(SyncState.Streaming(p, s), "file transfer progress")
                            if (payloadSize > 1_000_000) showSendProgressNotification(context, fileName, (p * 100).toInt(), payloadSize)
                        }
                    )
                }
                file != null && contentType == "image" -> {
                    setState(SyncState.Streaming(0f), "image transfer started")
                    ClipSyncSender.sendImageFile(
                        ip         = macIp,
                        port       = macPort,
                        file       = file,
                        hexKey     = hexKey,
                        onProgress = { p, s -> 
                            setState(SyncState.Streaming(p, s), "image transfer progress")
                            if (payloadSize > 1_000_000) showSendProgressNotification(context, "Image", (p * 100).toInt(), payloadSize)
                        }
                    )
                }
                file != null -> {
                    if (DeviceManager.isUltraFastModeEnabled(context)) {
                        setState(SyncState.Streaming(0f), "ultra fast transfer started")
                        
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "clipsync_uft_transfer_lock")
                        
                        try {
                            wifiLock.acquire()
                            java.io.FileInputStream(file).use { fis ->
                                ClipSyncSender.sendZeroCopyFileStream(
                                    ip         = macIp,
                                    port       = macPort,
                                    fileName   = file.name,
                                    fileSize   = payloadSize,
                                    fd         = fis.fd,
                                    onProgress = { p, s -> 
                                        setState(SyncState.Streaming(p, s), "ultra fast transfer progress")
                                        if (payloadSize > 1_000_000) showSendProgressNotification(context, "File", (p * 100).toInt(), payloadSize)
                                    }
                                )
                            }
                        } finally {
                            if (wifiLock.isHeld) {
                                wifiLock.release()
                            }
                            DeviceManager.setUltraFastModeEnabled(context, false)
                        }
                    } else {
                        setState(SyncState.Streaming(0f), "file transfer started")
                        ClipSyncSender.sendFile(
                            ip         = macIp,
                            port       = macPort,
                            file       = file,
                            hexKey     = hexKey,
                            onProgress = { p, s -> 
                                setState(SyncState.Streaming(p, s), "file transfer progress")
                                if (payloadSize > 1_000_000) showSendProgressNotification(context, "File", (p * 100).toInt(), payloadSize)
                            }
                        )
                    }
                }
                contentType == "image" -> {
                    setState(SyncState.Streaming(0f), "image transfer started")
                    ClipSyncSender.sendImage(macIp, macPort, content.toByteArray(), hexKey)
                }
                else -> {
                    setState(SyncState.Streaming(0f), "text transfer started")
                    ClipSyncSender.sendText(macIp, macPort, content, hexKey)
                }
            }
            setState(SyncState.Success, "transfer complete via LAN")
            com.bunty.clipsync.db.HistoryRepository.getInstance(context).addSent(
                content = if (contentType == "file" || uri != null || file != null) "File Sent" else if (contentType == "image") "Image Sent" else content,
                type = contentType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
            )
            if (payloadSize > 1_000_000 || uri != null) showSendCompleteNotification(context)
        } catch (e: CancellationException) {
            Log.w(TAG, "TCP send cancelled")
            showSendFailedNotification(context, "Cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "TCP send failed: ${e.message}")
            com.bunty.clipsync.db.HistoryRepository.getInstance(context).addSent(
                content = if (contentType == "file" || uri != null || file != null) "File Transfer Failed" else if (contentType == "image") "Image Transfer Failed" else content,
                type = contentType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() },
                isSuccess = false
            )
            showSendFailedNotification(context, "Connection error")
            handleNoRoute(context, content, contentType)
        } finally {
            if (file != null && file.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }


    private fun handleNoRoute(context: Context, content: String, contentType: String) {
        val syncMode = DeviceManager.getSyncMode(context)

        if (syncMode == "hybrid" && contentType != "file") {
            val sizeBytes = content.toByteArray().size.toLong()
            if (sizeBytes <= 100 * 1024 * 1024) {
                setState(SyncState.PendingCloudApproval(sizeBytes), "cloud fallback available")
                return
            }
        }

        val failure = if (syncMode == "hybrid") "File too large for cloud (>100 MB). Connect to same Wi-Fi."
            else "Not on same network. Local sync only — connect to same Wi-Fi."
        setState(SyncState.Failed(failure), "no route available; syncMode=$syncMode")
    }

    private suspend fun verifyLocalRoute(context: Context) {
        setState(SyncState.CheckingWifi, "onboarding: checking local prerequisites")

        if (!DeviceManager.isPaired(context)) {
            setState(SyncState.Failed("Pairing data missing — scan the QR again"), "onboarding aborted: missing pairing")
            return
        }

        if (DeviceManager.getEncryptionKey(context).isNullOrEmpty()) {
            setState(SyncState.Failed("Secure pairing key missing — scan the QR again"), "onboarding aborted: missing encryption key")
            return
        }

        if (!isWifiConnected(context)) {
            setState(SyncState.Failed("Wi-Fi not connected"), "onboarding aborted: Wi-Fi disabled or unavailable")
            return
        }

        // Try BLE-based IP resolution first
        setState(SyncState.DiscoveringMac, "onboarding: resolving Mac IP via BLE")
        val macIp = resolveMacIpViaBle(context)
        val macPort = DeviceManager.getMacLocalPort(context)

        if (macIp != null) {
            setState(SyncState.Found(macIp), "onboarding: Mac IP resolved via BLE")
            if (probeLan(macIp, macPort, "onboarding BLE-resolved IP")) {
                markOnboardingRouteReady(context, macIp, macPort)
                return
            }
            Log.w(TAG, "Onboarding BLE-resolved IP ($macIp) not reachable on LAN")
        }

        setState(SyncState.Failed("Mac not reachable — ensure both devices are on the same Wi-Fi and Bluetooth is on."), "onboarding: BLE IP resolve failed or not reachable on LAN")
    }

    private suspend fun markOnboardingRouteReady(context: Context, macIp: String, macPort: Int = TCP_PORT_FALLBACK) {
        DeviceManager.saveMacLocalEndpoint(context, macIp, macPort)
        setState(SyncState.SendingWakeup, "onboarding: sending BLE handshake")
        sendBleWakeup(context, macIp, payloadSize = 0, type = "handshake")
        setState(SyncState.Connecting, "onboarding: handshake sent, completing route")
        delay(300)
        setState(SyncState.Success, "onboarding: local route verified")

        // Subscribe to Mac's SendRequest characteristic so Mac can push text/files to us at any time.
        // This runs on a background coroutine so it doesn't block the onboarding flow.
        scope.launch(Dispatchers.IO) {
            delay(500) // brief pause to let the GATT stack settle after the wakeup ping
            MacPushForegroundService.startIfNeeded(context)
        }
    }


    // ── BLE wakeup helper ─────────────────────────────────────────────────────

    private fun sendBleWakeup(context: Context, macIp: String, payloadSize: Long, type: String) {
        setState(SyncState.SendingWakeup, "BLE wakeup requested: type=$type macIp=$macIp payload=$payloadSize")
        val androidIp = getLocalIpAddress(context) ?: macIp   // fallback: Mac already knows its own IP
        
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val info = wifiManager?.connectionInfo
        val rawSsid = info?.ssid?.removeSurrounding("\"")
        val ssid = if (rawSsid.isNullOrBlank()) null else if (rawSsid == "<unknown ssid>") (if (info.networkId != -1) "Wi-Fi Connected" else null) else rawSsid
        val deviceName = DeviceManager.getAndroidDeviceName(context)

        val ping = WakeupPing(androidIp, TCP_PORT, payloadSize, type, null, battery, ssid, deviceName)
        WakeupPingSender.send(
            context  = context,
            ping     = ping,
            onSent   = { resolvedIp, resolvedPort ->
                if (resolvedIp.isNotEmpty()) {
                    cachedMacIp = resolvedIp
                    cachedMacIpTime = System.currentTimeMillis()
                    DeviceManager.saveMacLocalEndpoint(context, resolvedIp, resolvedPort)
                }
            },
            onFailed = { reason -> Log.w(TAG, "BLE wakeup skipped: $reason") }
        )
        // Continue regardless — TCP still works without BLE wakeup (just slightly slower)
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(NOTIF_CHANNEL, "File Transfers", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun showSendProgressNotification(context: Context, filename: String, percent: Int, totalBytes: Long) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sizeMb = String.format("%.1f MB", totalBytes / 1_048_576.0)

        val cancelIntent = Intent(context, CancelTransferReceiver::class.java).apply {
            action = CancelTransferReceiver.ACTION_CANCEL_SEND
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending to Mac")
            .setContentText("$filename — $percent% of $sizeMb")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
        nm.notify(SEND_NOTIF_ID, notif)
    }

    private fun showSendCompleteNotification(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(SEND_NOTIF_ID)
        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Sent to Mac")
            .setContentText("Transfer complete")
            .setAutoCancel(true)
            .build()
        nm.notify(SEND_NOTIF_ID + 1, notif)
    }

    private fun showSendFailedNotification(context: Context, reason: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(SEND_NOTIF_ID)
        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Transfer Failed")
            .setContentText(reason)
            .setAutoCancel(true)
            .build()
        nm.notify(SEND_NOTIF_ID + 1, notif)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun probeLan(host: String, port: Int, reason: String): Boolean {
        val ok = ClipSyncSender.probeTcp(host, port, LAN_PROBE_MS)
        return ok
    }

    // ── BLE IP Discovery ──────────────────────────────────────────────────────

    /**
     * Sends a BLE wakeup ping to the Mac and extracts the Mac's live IP from the
     * device-info JSON it serves on the DeviceName characteristic.
     *
     * Returns the resolved IP, or null if BLE is unavailable or timed out.
     * Also caches the result in [cachedMacIp] and [DeviceManager] for reuse.
     */
    private suspend fun resolveMacIpViaBle(context: Context): String? {
        // ── SSID-aware cache invalidation ────────────────────────────────────
        // If the current WiFi network differs from the one where we cached the
        // Mac's IP, that IP is stale (Mac got a new address on the new network).
        // Wipe both the in-memory cache and the persisted value so we are forced
        // to do a fresh BLE probe below.
        val currentSsid = getCurrentSsid(context)
        val cachedSsid  = DeviceManager.getMacCacheNetwork(context)
        if (currentSsid != null && cachedSsid != null && currentSsid != cachedSsid) {
            cachedMacIp     = null
            cachedMacIpTime = 0L
            DeviceManager.saveMacLocalEndpoint(context, "", 0)
        }

        // Return in-memory cache if fresh (< 2 min)
        val now = System.currentTimeMillis()
        cachedMacIp?.let { ip ->
            if (now - cachedMacIpTime < IP_CACHE_TTL_MS) {
                return ip
            }
        }

        val myIp = getLocalIpAddress(context) ?: ""
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val info = wifiManager?.connectionInfo
        val rawSsid = info?.ssid?.removeSurrounding("\"")
        val ssid = if (rawSsid.isNullOrBlank()) null else if (rawSsid == "<unknown ssid>") (if (info.networkId != -1) "Wi-Fi Connected" else null) else rawSsid
        val deviceName = DeviceManager.getAndroidDeviceName(context)

        val ping = WakeupPing(
            localIp = myIp,
            tcpPort = TCP_PORT,
            payloadSize = 0,
            payloadType = "ip_probe",
            battery = battery,
            network = ssid,
            deviceName = deviceName
        )

        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
        WakeupPingSender.send(
            context  = context,
            ping     = ping,
            onSent   = { resolvedIp, resolvedPort ->
                if (resolvedIp.isNotEmpty()) {
                    DeviceManager.saveMacLocalEndpoint(context, resolvedIp, resolvedPort)
                }
                deferred.complete(resolvedIp.ifEmpty { null })
            },
            onFailed = { reason ->
                Log.w(TAG, "BLE IP probe failed: $reason")
                deferred.complete(null)
            }
        )

        val resolvedIp = withTimeoutOrNull(BLE_IP_RESOLVE_MS) { deferred.await() }
        if (!resolvedIp.isNullOrEmpty()) {
            cachedMacIp     = resolvedIp
            cachedMacIpTime = System.currentTimeMillis()
            DeviceManager.saveMacLocalIp(context, resolvedIp)
            // Record the SSID so future calls can detect a network switch.
            DeviceManager.saveMacCacheNetwork(context, getCurrentSsid(context))
        }
        return resolvedIp
    }

    private fun isWifiConnected(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val enabled = wm?.isWifiEnabled == true
        return enabled
    }

    /** Returns the current WiFi SSID, or null if unavailable / no permissions. */
    private fun getCurrentSsid(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val rawSsid = wm?.connectionInfo?.ssid?.removeSurrounding("\"") ?: return null
        return when {
            rawSsid.isBlank()              -> null
            rawSsid == "<unknown ssid>"    -> null
            else                           -> rawSsid
        }
    }

    private fun getLocalIpAddress(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val rawIp = wm.connectionInfo.ipAddress
            if (rawIp == 0) null
            else String.format(
                "%d.%d.%d.%d",
                rawIp and 0xff,
                (rawIp shr 8) and 0xff,
                (rawIp shr 16) and 0xff,
                (rawIp shr 24) and 0xff
            )
        } catch (_: Exception) { null }
    }

    private fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        multicastLock = wm.createMulticastLock("clipsync_mdns_lock").apply {
            setReferenceCounted(true)
            try {
                acquire()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
            }
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
            if (multicastLock != null) {
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release multicast lock: ${e.message}")
        } finally {
            multicastLock = null
        }
    }
}
