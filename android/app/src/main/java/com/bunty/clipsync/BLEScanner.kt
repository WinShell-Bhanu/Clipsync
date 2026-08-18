package com.bunty.clipsync

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.annotation.SuppressLint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Scans for a ClipSync Mac using BLE.
 *
 * Strategy: filter by the ClipSync service UUID so only ClipSync devices
 * are returned. Take the first result and stop immediately — no dedup by
 * MAC address because Android randomizes them, and there should only ever
 * be one ClipSync device nearby at a time.
 */
object BLEScanner {

    /** The custom ClipSync BLE service UUID — must match BLEDiscover.swift on the Mac. */
    val SERVICE_UUID: UUID = UUID.fromString("C11C5AC0-0001-1000-8000-00805F9B34FB")

    data class DiscoveredDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )

    sealed class ScanState {
        object Idle : ScanState()
        object Scanning : ScanState()
        data class Found(val devices: List<DiscoveredDevice>) : ScanState()
        data class Failed(val reason: String) : ScanState()
    }

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null

    /** Start scanning for the ClipSync service UUID. Call only after BLE permissions are granted. */
    fun startScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            _state.value = ScanState.Failed("Bluetooth is not enabled")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            android.util.Log.e("BLEDebug", "BLE scanner not available")
            _state.value = ScanState.Failed("BLE scanner not available")
            return
        }


        // Only surface devices advertising the ClipSync service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            val pendingDevices = mutableSetOf<String>()

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // If scanning was cancelled/failed, ignore results
                if (_state.value is ScanState.Idle || _state.value is ScanState.Failed) return

                val mac = result.device.address
                val currentDevices = (_state.value as? ScanState.Found)?.devices ?: emptyList()
                
                // Ignore if we already resolved this MAC or are currently resolving it
                if (currentDevices.any { it.address == mac } || pendingDevices.contains(mac)) {
                    return
                }

                pendingDevices.add(mac)
                
                fetchNameOverGatt(context, mac) { fetchedName ->
                    if (_state.value is ScanState.Idle || _state.value is ScanState.Failed) return@fetchNameOverGatt

                    val finalName = fetchedName ?: "ClipSync Mac"
                    val device = DiscoveredDevice(
                        name = finalName,
                        address = mac,
                        rssi = result.rssi
                    )
                    
                    val updatedDevices = (_state.value as? ScanState.Found)?.devices ?: emptyList()
                    if (updatedDevices.none { it.name == finalName || it.address == mac }) {
                        _state.value = ScanState.Found(updatedDevices + device)
                    }
                }
            }
            
            @SuppressLint("MissingPermission")
            private fun fetchNameOverGatt(ctx: Context, address: String, onResult: (String?) -> Unit) {
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                val device = try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null }
                if (device == null) return onResult(null)

                var gatt: BluetoothGatt? = null
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}
                    onResult(null)
                }
                timeoutHandler.postDelayed(timeoutRunnable, 3000)

                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                        else if (newState == BluetoothProfile.STATE_DISCONNECTED) g.close()
                    }
                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val char = g.getService(SERVICE_UUID)?.getCharacteristic(java.util.UUID.fromString("C11C5AC1-0001-1000-8000-00805F9B34FB"))
                        if (char != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.readCharacteristic(char)
                            else @Suppress("DEPRECATION") g.readCharacteristic(char)
                        } else {
                            g.disconnect(); g.close()
                        }
                    }
                    private fun handleRes(g: BluetoothGatt, value: String?) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        g.disconnect(); g.close()
                        val name = try { org.json.JSONObject(value ?: "").optString("name", "ClipSync Mac") } catch (e: Exception) { value }
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(name) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                        @Suppress("DEPRECATION")
                        handleRes(g, if (status == BluetoothGatt.GATT_SUCCESS) c.value?.toString(Charsets.UTF_8) else null)
                    }
                    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
                        handleRes(g, if (status == BluetoothGatt.GATT_SUCCESS) v.toString(Charsets.UTF_8) else null)
                    }
                }
                
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(ctx, false, cb)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e("BLEDebug", "Scan failed with error code $errorCode")
                _state.value = ScanState.Failed("Scan failed (error $errorCode)")
            }
        }

        scanCallback = callback
        _state.value = ScanState.Scanning
        bleScanner = scanner
        scanner.startScan(listOf(filter), settings, callback)
    }

    /** Stop an active scan. Does not change state if a device was already found. */
    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            bleScanner?.stopScan(cb)
        } catch (_: Exception) { /* ignore — BT may have been turned off */ }
        bleScanner = null
    }

    /** Reset back to Idle so a new scan can be started. */
    fun reset() {
        stopScan()
        _state.value = ScanState.Idle
    }
}
