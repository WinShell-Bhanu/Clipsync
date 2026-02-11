package com.bunty.clipsync


import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val isPaired = DeviceManager.isPaired(this)
        val startDestination = if (isPaired) "homescreen" else "landing"

        setContent {
            MaterialTheme {
                ClipSyncNavigation(startDestination = startDestination)
            }
        }
    }
}

@Composable
fun ClipSyncNavigation(startDestination: String) {
    val navController = rememberNavController()

    // Persistent Animation States
    var isPulsing by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }

    // Listen for navigation changes to trigger animation
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    
    LaunchedEffect(currentBackStackEntry) {
        // Resume animation when screen changes
        val route = currentBackStackEntry?.destination?.route
        if (route == "landing") {
            isPaused = false
        } else {
             // Stop animation after transition (approx 1000ms) ONLY if not on landing
             kotlinx.coroutines.delay(1000)
             isPaused = true
        }
    }

    // --- Dynamic Background (Persistent) ---
    // MeshBackground stays active across all screens for seamless transitions
    MeshBackground(
        modifier = Modifier.fillMaxSize(),
        onPulse = isPulsing,
        isPaused = isPaused
    ) {
        // --- Foreground Navigation ---
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
            composable("landing") {
                LandingScreen(
                    onGetStartedClick = {
                        // Trigger pulse and navigate
                        isPulsing = true
                        navController.navigate("qrscan")
                        
                        // Reset pulse
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            isPulsing = false
                        }, 500)
                    }
                )
            }

            composable(
                route = "qrscan?startCamera={startCamera}",
                arguments = listOf(navArgument("startCamera") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val context = navController.context
                val startCamera = backStackEntry.arguments?.getBoolean("startCamera") ?: false

                QRScanScreen(
                    initialCameraActive = startCamera,
                    onQRScanned = { qrData ->
                        // Parse the scanned QR data
                        val parsedData = FirestoreManager.parseQRData(qrData)

                        if (parsedData != null) {
                            val qrRegion = parsedData["serverRegion"] as? String ?: "IN"
                            val initializedRegion = DeviceManager.initializedRegion

                            if (qrRegion != initializedRegion) {
                                DeviceManager.setTargetRegion(context, qrRegion)
                            }

                            FirestoreManager.createPairing(
                                context = context,
                                qrData = parsedData,
                                onSuccess = { pairingId ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        navController.navigate("connection") {
                                            popUpTo("landing") { inclusive = true }
                                        }
                                    }
                                },
                                onFailure = { e ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, "Pairing failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        navController.popBackStack()
                                    }
                                }
                            )
                        } else {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                                navController.navigate("qrscan") {
                                    popUpTo("landing") 
                                }
                            }
                        }
                    }
                )
            }

            composable("connection") {
                ConnectionPage(
                    onContinue = {
                        navController.navigate("permission") {
                            popUpTo("qrscan") { inclusive = true }
                        }
                    },
                    onUnpair = {
                        DeviceManager.clearPairing(navController.context)
                        navController.navigate("landing") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable("permission") {
                PermissionPage(
                    onFinishSetup = {
                        navController.navigate("homescreen") {
                            popUpTo("permission") { inclusive = true }
                        }
                    }
                )
            }

            composable("homescreen") {
                Homescreen(
                    onRepairClick = {
                        DeviceManager.clearPairing(navController.context)
                        navController.navigate("qrscan?startCamera=true") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onResetPairing = {
                        navController.navigate("landing") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
