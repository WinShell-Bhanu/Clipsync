package com.bunty.clipsync

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * 30-byte BLE wakeup ping sent from Android to Mac before each TCP transfer.
 *
 * The ping tells the Mac:
 *  - Where Android's TCP server is listening (ip + port)
 *  - How large the incoming payload is (so Mac can show progress)
 *  - What type of content is coming ("text" | "image" | "file")
 *
 * Serialised as compact JSON to stay under the BLE MTU (≤ 512 bytes).
 * Typical payload: {"ip":"192.168.1.15","p":8765,"s":2048,"t":"text"} — 54 bytes.
 */
data class WakeupPing(
    val localIp:     String,
    val tcpPort:     Int    = LocalSyncManager.TCP_PORT,
    val payloadSize: Long,
    val payloadType: String,  // "text" | "image" | "file"
    val directPayload: String? = null,
    val battery: Int? = null,
    val network: String? = null,
    val deviceName: String? = null,
    val isDiagnostic: Boolean = false
) {
    fun toJsonBytes(): ByteArray = JSONObject().apply {
        if (isDiagnostic) {
            put("diagnostic", true)
        }
        if (directPayload != null) {
            put("d", directPayload)
            put("t", payloadType)
            put("s", payloadSize)
        } else {
            put("ip", localIp)
            put("p",  tcpPort)
            put("s",  payloadSize)
            put("t",  payloadType)
        }
        if (battery != null) put("b", battery)
        if (network != null) put("n", network)
        if (deviceName != null) put("dev", deviceName)
    }.toString().toByteArray(Charsets.UTF_8)
}

// ── BLE Wakeup Sender ─────────────────────────────────────────────────────────

/**
 * Connects to the Mac's BLE GATT server, performs a single GATT session that:
 *   1. Reads the DeviceName characteristic → parses {"name":..., "ip":...} to get the Mac's live IP.
 *   2. Writes the WakeupPing to the Wakeup characteristic.
 *   3. Disconnects.
 *
 * This completely replaces mDNS/Bonjour for MAC IP discovery. No hardcoded IPs.
 * The resolved Mac IP is returned via [onReady] so LocalSyncManager can use it for TCP.
 */
object WakeupPingSender {

    private const val TAG = "WakeupPing"

    /** Must match the characteristic UUID registered in WakeupReceiver.swift on Mac. */
    val WAKEUP_CHAR_UUID: UUID = UUID.fromString("C11C5AC2-0001-1000-8000-00805F9B34FB")

    /** Mac→Android push characteristic: Mac calls updateValue() to notify Android of incoming content. */
    val SEND_REQUEST_CHAR_UUID: UUID = UUID.fromString("C11C5AC3-0001-1000-8000-00805F9B34FB")

    /** Standard GATT CCCD descriptor UUID — must be written to enable notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** DeviceName char UUID — Mac serves {"name":..., "ip":...} JSON from this. */
    private val DEVICE_NAME_CHAR_UUID: UUID = UUID.fromString("C11C5AC1-0001-1000-8000-00805F9B34FB")

    /** ClipSync service UUID — same as BLEScanner. */
    private val SERVICE_UUID: UUID = UUID.fromString("C11C5AC0-0001-1000-8000-00805F9B34FB")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sends [ping] to the Mac via BLE in a single GATT session:
     * read DeviceName char (to get Mac's live IP) → write wakeup ping → disconnect.
     *
     * @param context   Application context for Bluetooth access.
     * @param ping      The wakeup payload (localIp = Android's IP, filled by caller).
     * @param onSent    Called with the Mac's resolved IP and Port when the write succeeds.
     * @param onFailed  Called with a reason string if anything fails.
     */
    fun send(
        context:  Context,
        ping:     WakeupPing,
        onSent:   (macIp: String, macPort: Int) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val macAddress = DeviceManager.getMacBleAddress(context)
        if (macAddress.isNullOrEmpty()) {
            onFailed("No Mac BLE address stored — pair first")
            return
        }

        val pingBytes = ping.toJsonBytes()

        scope.launch {
            connectReadThenWrite(context, macAddress, pingBytes, ping, onSent, onFailed)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun connectReadThenWrite(
        context:    Context,
        address:    String,
        pingData:   ByteArray,
        ping:       WakeupPing,
        onSent:     (macIp: String, macPort: Int) -> Unit,
        onFailed:   (String) -> Unit
    ) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter   = btManager?.adapter ?: run {
            onFailed("Bluetooth not available")
            return
        }

        if (!adapter.isEnabled) {
            onFailed("Bluetooth is disabled")
            return
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            onFailed("Invalid BLE address: $address")
            return
        }


        val callback = object : BluetoothGattCallback() {
            private var ipReadDone = false
            private var writeAttempted = false
            private var resolvedMacIp: String = ""
            private var resolvedMacPort: Int = LocalSyncManager.TCP_PORT

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        gatt.requestMtu(512)
                    } else {
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!writeAttempted) {
                        Log.w(TAG, "BLE disconnected before write; resolvedIp=$resolvedMacIp, port=$resolvedMacPort")
                        // Even if write wasn't confirmed, if we got the IP, partial success
                        if (resolvedMacIp.isNotEmpty()) {
                            onSent(resolvedMacIp, resolvedMacPort)
                        } else {
                            onFailed("BLE disconnected before wakeup write")
                        }
                    }
                    gatt.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onFailed("BLE service discovery failed ($status)")
                    gatt.close()
                    return
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    onFailed("ClipSync BLE service not found on Mac")
                    gatt.close()
                    return
                }

                // Step 1: Read the DeviceName characteristic to get Mac's live IP
                val nameChar = service.getCharacteristic(DEVICE_NAME_CHAR_UUID)
                if (nameChar != null && !ipReadDone) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.readCharacteristic(nameChar)
                    } else {
                        @Suppress("DEPRECATION")
                        gatt.readCharacteristic(nameChar)
                    }
                } else {
                    // Fallback: skip read, write directly (will use cached IP)
                    Log.w(TAG, "DeviceName char not found; skipping IP read, going directly to write")
                    writeWakeupPing(gatt, service, pingData, resolvedMacIp, ::onFailed)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid == DEVICE_NAME_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val json = characteristic.value?.toString(Charsets.UTF_8) ?: ""
                        val (ip, port) = parseIpAndPortFromJson(json)
                        resolvedMacIp = ip
                        resolvedMacPort = port
                        if (resolvedMacIp.isNotEmpty()) {
                            DeviceManager.saveMacLocalEndpoint(context, resolvedMacIp, resolvedMacPort)
                        }
                    } else {
                        Log.w(TAG, "DeviceName read failed (status=$status); will use cached IP")
                        resolvedMacIp = DeviceManager.getMacLocalIp(context) ?: ""
                        resolvedMacPort = DeviceManager.getMacLocalPort(context)
                    }
                    ipReadDone = true

                    // Step 2: Now write the wakeup ping in the same connection
                    val service = gatt.getService(SERVICE_UUID)
                    if (service != null) {
                        writeWakeupPing(gatt, service, pingData, resolvedMacIp, ::onFailed)
                    } else {
                        onFailed("Service disappeared after IP read")
                        gatt.disconnect()
                    }
                }
            }

            // API 33+ version
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (characteristic.uuid == DEVICE_NAME_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val json = value.toString(Charsets.UTF_8)
                        val (ip, port) = parseIpAndPortFromJson(json)
                        resolvedMacIp = ip
                        resolvedMacPort = port
                        if (resolvedMacIp.isNotEmpty()) {
                            DeviceManager.saveMacLocalEndpoint(context, resolvedMacIp, resolvedMacPort)
                        }
                    } else {
                        Log.w(TAG, "DeviceName read failed API33 (status=$status); using cached IP")
                        resolvedMacIp = DeviceManager.getMacLocalIp(context) ?: ""
                        resolvedMacPort = DeviceManager.getMacLocalPort(context)
                    }
                    ipReadDone = true

                    val service = gatt.getService(SERVICE_UUID)
                    if (service != null) {
                        writeWakeupPing(gatt, service, pingData, resolvedMacIp, ::onFailed)
                    } else {
                        onFailed("Service disappeared after IP read")
                        gatt.disconnect()
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeAttempted = true
                    onSent(resolvedMacIp, resolvedMacPort)
                } else {
                    onFailed("BLE write failed (status $status)")
                }
                gatt.disconnect()
                gatt.close()
            }

            // Helper to keep both code paths DRY
            private fun onSent(ip: String, port: Int) = onSent.invoke(ip, port)
            private fun onFailed(msg: String) = onFailed.invoke(msg)
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }

        // Safety: close the GATT after 10 seconds if nothing happened
        scope.launch {
            delay(10_000)
            try { gatt.close() } catch (_: Exception) {}
        }
    }

    private fun writeWakeupPing(
        gatt:       BluetoothGatt,
        service:    android.bluetooth.BluetoothGattService,
        data:       ByteArray,
        macIp:      String,
        onFailed:   (String) -> Unit
    ) {
        val wakeupChar = service.getCharacteristic(WAKEUP_CHAR_UUID)
        if (wakeupChar == null) {
            onFailed("Wakeup characteristic not found on Mac")
            gatt.close()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(wakeupChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            wakeupChar.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(wakeupChar)
        }
    }

    /** Parse {"name":"...", "ip":"192.168.x.x", "port":8765} — returns the ip and port. */
    fun parseIpAndPortFromJson(jsonStr: String): Pair<String, Int> {
        return try {
            val json = JSONObject(jsonStr)
            val ip = json.optString("ip", "")
            val port = json.optInt("port", LocalSyncManager.TCP_PORT)
            Pair(ip, port)
        } catch (_: Exception) {
            // Legacy: plain text device name (no IP embedded) — return empty
            Log.w(TAG, "DeviceName char is not JSON (legacy Mac?): $jsonStr")
            Pair("", LocalSyncManager.TCP_PORT)
        }
    }
}
