package com.bunty.clipsync

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

data class DiagnosticResult(
    val label: String,
    val status: Status,
    val detail: String
) {
    enum class Status { PASS, WARN, FAIL }
}

sealed class ConsoleLine {
    data class Command(val text: String) : ConsoleLine()
    data class Result(val text: String, val status: DiagnosticResult.Status) : ConsoleLine()
    object Summary : ConsoleLine()
}

class ConnectionDiagnostics(private val context: Context) {

    private val log = mutableListOf<String>()
    private fun logLine(msg: String) {
        log.add(msg)
    }

    fun runFullScanStream(): Flow<ConsoleLine> = flow {
        emit(ConsoleLine.Command("Checking Bluetooth permission..."))
        delay(300)
        val btPerm = checkBluetoothPermission()
        emit(btPerm.toConsoleLine())
        if (btPerm.status == DiagnosticResult.Status.FAIL) {
            emit(ConsoleLine.Summary)
            return@flow
        }

        emit(ConsoleLine.Command("Checking Bluetooth adapter..."))
        delay(300)
        val btEnabled = checkBluetoothEnabled()
        emit(btEnabled.toConsoleLine())
        if (btEnabled.status == DiagnosticResult.Status.FAIL) {
            emit(ConsoleLine.Summary)
            return@flow
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            emit(ConsoleLine.Command("Checking location services..."))
            delay(300)
            val locEnabled = checkLocationEnabled()
            emit(locEnabled.toConsoleLine())
            if (locEnabled.status == DiagnosticResult.Status.FAIL) {
                emit(ConsoleLine.Summary)
                return@flow
            }
        }

        emit(ConsoleLine.Command("Checking Wi-Fi..."))
        delay(300)
        val wifiEnabled = checkWifiEnabled()
        emit(wifiEnabled.toConsoleLine())
        if (wifiEnabled.status == DiagnosticResult.Status.FAIL) {
            emit(ConsoleLine.Summary)
            return@flow
        }

        // Active Diagnostics
        emit(ConsoleLine.Command("Sending BLE diagnostic ping to Mac..."))
        val bleResult = testBlePing()
        emit(bleResult.toConsoleLine())

        emit(ConsoleLine.Command("Sending TCP diagnostic ping to Mac..."))
        val tcpResult = testTcpPing()
        emit(tcpResult.toConsoleLine())

        if (tcpResult.status == DiagnosticResult.Status.FAIL) {
            emit(ConsoleLine.Command("Checking network match..."))
            delay(400)
            emit(checkSameNetwork().toConsoleLine())

            emit(ConsoleLine.Command("Checking VPN..."))
            delay(300)
            emit(checkVpnActive().toConsoleLine())
        }

        emit(ConsoleLine.Command("Checking battery optimization..."))
        delay(300)
        emit(checkBatteryOptimization().toConsoleLine())

        emit(ConsoleLine.Summary)
    }

    private suspend fun testBlePing(): DiagnosticResult {
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == "com.bunty.clipsync.DIAGNOSTIC_ACK") {
                        try { context.unregisterReceiver(this) } catch (e: Exception) {}
                        if (cont.isActive) {
                            cont.resume(DiagnosticResult("BLE Ping", DiagnosticResult.Status.PASS, "Mac received ping and ACKed"))
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter("com.bunty.clipsync.DIAGNOSTIC_ACK"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter("com.bunty.clipsync.DIAGNOSTIC_ACK"))
            }

            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val localIpStr = intToIp(wm.connectionInfo.ipAddress)

            val ping = WakeupPing(
                localIp = localIpStr,
                payloadSize = 0,
                payloadType = "diagnostic",
                isDiagnostic = true
            )
            
            WakeupPingSender.send(context, ping,
                onSent = { _, _ ->
                    // Just wait for ACK via BroadcastReceiver
                },
                onFailed = { msg ->
                    try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
                    if (cont.isActive) {
                        cont.resume(DiagnosticResult("BLE Ping", DiagnosticResult.Status.FAIL, "Failed: $msg"))
                    }
                }
            )

            CoroutineScope(Dispatchers.IO).launch {
                delay(8000)
                if (cont.isActive) {
                    try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
                    cont.resume(DiagnosticResult("BLE Ping", DiagnosticResult.Status.FAIL, "Timed out waiting for Mac ACK"))
                }
            }
        }
    }

    private suspend fun testTcpPing(): DiagnosticResult {
        val macIp = DeviceManager.getMacLocalIp(context)
        val macPort = DeviceManager.getMacLocalPort(context)
        if (macIp == null) {
            return DiagnosticResult("TCP Ping", DiagnosticResult.Status.FAIL, "Mac IP unknown")
        }

        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(macIp, macPort), 3000)
                
                // Write 24 byte header
                val dos = DataOutputStream(socket.getOutputStream())
                dos.writeInt(0x434C5359) // Magic: "CLSY"
                dos.writeByte(1) // version
                dos.writeByte(0x99) // typeCode for diagnostic
                dos.writeShort(0) // reserved
                dos.writeLong(0L) // totalSize
                dos.writeLong(0L) // chunkSize
                dos.flush()
                
                socket.close()
                DiagnosticResult("TCP Ping", DiagnosticResult.Status.PASS, "Successfully connected to $macIp:$macPort")
            } catch (e: Exception) {
                DiagnosticResult("TCP Ping", DiagnosticResult.Status.FAIL, "Connection failed: ${e.message}")
            }
        }
    }

    private fun DiagnosticResult.toConsoleLine() = ConsoleLine.Result(
        text = detail,
        status = status
    )

    private fun checkBluetoothPermission(): DiagnosticResult {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        logLine("[BT Permission] granted=$granted")
        return DiagnosticResult(
            "Bluetooth Permission",
            if (granted) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL,
            if (granted) "Granted" else "Not granted — required to discover your Mac"
        )
    }

    private fun checkBluetoothEnabled(): DiagnosticResult {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val enabled = adapter?.isEnabled == true
        logLine("[BT Enabled] enabled=$enabled")
        return DiagnosticResult(
            "Bluetooth Enabled",
            if (enabled) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL,
            if (enabled) "On" else "Bluetooth is turned off"
        )
    }

    private fun checkLocationEnabled(): DiagnosticResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = lm.isLocationEnabled
        logLine("[Location] enabled=$enabled (required for BLE scan on API <=30)")
        return DiagnosticResult(
            "Location Services",
            if (enabled) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL,
            if (enabled) "On" else "Required for Bluetooth scanning on this Android version"
        )
    }

    private fun checkWifiEnabled(): DiagnosticResult {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val enabled = wm.isWifiEnabled
        logLine("[WiFi Enabled] enabled=$enabled")
        return DiagnosticResult(
            "Wi-Fi Enabled",
            if (enabled) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL,
            if (enabled) "On" else "Wi-Fi is turned off"
        )
    }

    private fun checkVpnActive(): DiagnosticResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        logLine("[VPN] active=$vpnActive")
        return DiagnosticResult(
            "VPN Check",
            if (vpnActive) DiagnosticResult.Status.WARN else DiagnosticResult.Status.PASS,
            if (vpnActive) "VPN active — often blocks local network discovery" else "No VPN detected"
        )
    }

    private fun checkSameNetwork(): DiagnosticResult {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val localIp = intToIp(wm.connectionInfo.ipAddress)
        val macIp = DeviceManager.getMacLocalIp(context)

        logLine("[Network] localIp=$localIp macIp=$macIp")

        if (macIp.isNullOrEmpty()) {
            return DiagnosticResult(
                "Same Network",
                DiagnosticResult.Status.WARN,
                "No known Mac IP yet — can't verify subnet match"
            )
        }

        val sameSubnet = localIp.substringBeforeLast(".") == macIp.substringBeforeLast(".")
        return DiagnosticResult(
            "Same Network",
            if (sameSubnet) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL,
            if (sameSubnet) "Same subnet as Mac ($localIp)" 
            else "Different subnet — phone on $localIp, Mac last seen on $macIp. Check 2.4GHz/5GHz band or guest network."
        )
    }

    private fun checkBatteryOptimization(): DiagnosticResult {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        logLine("[Battery Optimization] ignoring=$ignoring")
        return DiagnosticResult(
            "Battery Optimization",
            if (ignoring) DiagnosticResult.Status.PASS else DiagnosticResult.Status.WARN,
            if (ignoring) "Exempted" else "App may be killed in background, causing dropped syncs"
        )
    }

    fun getFullLog(): String = log.joinToString("\n")

    private fun intToIp(ip: Int): String =
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
}
