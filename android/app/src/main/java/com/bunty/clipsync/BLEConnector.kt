package com.bunty.clipsync

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages the GATT connection to the Mac after BLE discovery.
 *
 * Flow:
 *   connect(context, address)
 *       → GATT connected
 *       → discoverServices()
 *       → find ClipSync service + DeviceName characteristic
 *       → readCharacteristic()
 *       → emit Connected(deviceName)
 *
 * If nothing succeeds within 10 seconds, closes the GATT and emits Failed("Timeout").
 */
object BLEConnector {

    /** The GATT characteristic UUID that the Mac serves the device name on. Must match BLEDiscover.swift. */
    private val DEVICE_NAME_CHAR_UUID: UUID = UUID.fromString("C11C5AC1-0001-1000-8000-00805F9B34FB")
    /** Writable characteristic used to signal the pairing handshake. */
    private val WAKEUP_CHAR_UUID: UUID = UUID.fromString("C11C5AC2-0001-1000-8000-00805F9B34FB")

    sealed class ConnectionState {
        object Idle : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeoutJob: Job? = null
    private var pendingDeviceName: String? = null
    /** Application context stored so [succeed] can save the Mac BLE address. */
    private var appContext: android.content.Context? = null

    /**
     * Connect to the device at [address] and read the DeviceName GATT characteristic.
     * Automatically fails with a "Timeout" error if nothing happens within 10 seconds.
     */
    fun connect(context: Context, address: String) {
        if (_state.value is ConnectionState.Connecting) return
        appContext = context.applicationContext
        _state.value = ConnectionState.Connecting
        startTimeout()

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: run {
            fail("Bluetooth not available")
            return
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            fail("Invalid device address")
            return
        }

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Connected — now discover services
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (_state.value is ConnectionState.Connecting) {
                        fail("Disconnected before completing handshake")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Service discovery failed (status $status)")
                    return
                }

                val service = gatt.getService(BLEScanner.SERVICE_UUID)
                if (service == null) {
                    fail("ClipSync service not found on this device")
                    return
                }

                val characteristic = service.getCharacteristic(DEVICE_NAME_CHAR_UUID)
                if (characteristic == null) {
                    fail("DeviceName characteristic not found")
                    return
                }

                // Read the device name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.readCharacteristic(characteristic)
                } else {
                    @Suppress("DEPRECATION")
                    gatt.readCharacteristic(characteristic)
                }
            }

            @Deprecated("Deprecated in Java — required for API < 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val name = characteristic.value?.toString(Charsets.UTF_8) ?: "ClipSync Mac"
                    continueHandshake(gatt, name)
                } else {
                    fail("Failed to read DeviceName characteristic")
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val name = value.toString(Charsets.UTF_8)
                    continueHandshake(gatt, name)
                } else {
                    fail("Failed to read DeviceName characteristic")
                }
            }
            
            private fun continueHandshake(gatt: BluetoothGatt, name: String) {
                pendingDeviceName = name
                val service = gatt.getService(BLEScanner.SERVICE_UUID)
                val wakeupChar = service?.getCharacteristic(WAKEUP_CHAR_UUID)
                if (wakeupChar == null) {
                    fail("Wakeup characteristic not found")
                    return
                }
                
                val payload = "{\"type\":\"pair\"}".toByteArray(Charsets.UTF_8)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(wakeupChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) // Mac processes writeWithoutResponse
                    succeed(name)
                } else {
                    @Suppress("DEPRECATION")
                    wakeupChar.value = payload
                    @Suppress("DEPRECATION")
                    wakeupChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(wakeupChar)
                    succeed(name)
                }
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
    }

    /** Close GATT and reset state so a new connection attempt can be made. */
    fun reset() {
        timeoutJob?.cancel()
        gatt?.close()
        gatt = null
        _state.value = ConnectionState.Idle
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (_state.value is ConnectionState.Connecting) {
                    delay(100)
                }
            } ?: run {
                if (_state.value is ConnectionState.Connecting) {
                    fail("Timeout — is the Mac nearby and awake?")
                }
            }
        }
    }

    private fun succeed(deviceName: String) {
        timeoutJob?.cancel()
        // Save the BLE address of the Mac so WakeupPingSender can find it later.
        gatt?.device?.address?.let { addr ->
            appContext?.let { ctx -> DeviceManager.saveMacBleAddress(ctx, addr) }
        }
        gatt?.close()
        gatt = null
        _state.value = ConnectionState.Connected(deviceName)
    }

    private fun fail(reason: String) {
        timeoutJob?.cancel()
        gatt?.close()
        gatt = null
        _state.value = ConnectionState.Failed(reason)
    }
}
