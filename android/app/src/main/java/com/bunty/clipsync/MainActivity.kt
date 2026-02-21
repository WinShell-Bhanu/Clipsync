package com.bunty.clipsync


import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * MainActivity is the single Activity used by the entire ClipSync app.
 *
 * On creation it checks whether the device is already paired with a Mac
 * ([DeviceManager.isPaired]) and directs the [NavHost] to the appropriate start destination:
 * - Paired     → "homescreen"
 * - Not paired → "landing"
 *
 * The [MeshBackground] animated gradient is rendered once here and shared across
 * all destinations so the background doesn't flash or restart on navigation.
 */
class MainActivity : ComponentActivity() {

    /**
     * Called when the Activity is first created.
     * Sets up edge-to-edge display and inflates the Compose UI.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Decide the initial destination based on whether the user has already paired
        val isPaired = DeviceManager.isPaired(this)
        val startDestination = if (isPaired) "homescreen" else "landing"

        setContent {
            MaterialTheme {
                ClipSyncNavigation(
                    startDestination = startDestination
                )
            }
        }
    }
}

/**
 * ClipSyncNavigation defines the entire navigation graph for the app.
 *
 * All screens are rendered on top of the shared [MeshBackground] animated gradient.
 * The background animation speed is controlled here:
 * - It pulses (speeds up) briefly when the user taps "Get Started" on the landing screen.
 * - It pauses on all non-landing routes and when the app is backgrounded (saves battery).
 *
 * Navigation routes:
 * - `landing`        → [LandingScreen]     (entry point for new users)
 * - `qrscan`         → [QRScanScreen]      (QR code scanner for pairing)
 * - `connection`     → [ConnectionPage]    (pairing success confirmation)
 * - `permission`     → [PermissionPage]    (permission onboarding)
 * - `homescreen`     → [Homescreen]        (main settings & status screen)
 *
 * @param startDestination The initial route to navigate to ("landing" or "homescreen").
 */
@Composable
fun ClipSyncNavigation(startDestination: String) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // isPulsing → briefly speeds up the MeshBackground when transitioning from landing
    var isPulsing by remember { mutableStateOf(false) }
    // isRoutePaused → freezes the background on screens where animation isn't needed
    var isRoutePaused by remember { mutableStateOf(false) }
    // isAppVisible → pauses the background when the app is in the background
    var isAppVisible by remember { mutableStateOf(true) }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    // Pause the background animation on all routes except "landing" (after a 1 s delay
    // to let the entrance animation play first)
    LaunchedEffect(currentBackStackEntry?.destination?.route) {
        val route = currentBackStackEntry?.destination?.route
        if (route == "landing") {
            isRoutePaused = false
        } else {
            delay(1000)
            isRoutePaused = true
        }
    }

    // Auto-reset the pulse flag after 500 ms so the background speeds up only briefly
    LaunchedEffect(isPulsing) {
        if (isPulsing) {
            delay(500)
            isPulsing = false
        }
    }

    // Pause the background when the app leaves the foreground to save battery
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> isAppVisible = true

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> isAppVisible = false

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // MeshBackground wraps the entire NavHost so the animated gradient
    // persists across all navigation transitions
    MeshBackground(
        modifier = Modifier.fillMaxSize(),
        onPulse = isPulsing,
        isPaused = isRoutePaused || !isAppVisible
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
            // ── Landing screen ─────────────────────────────────────────────
            // Shown to first-time / unpaired users. Navigates to QR scan on tap.
            composable("landing") {
                LandingScreen(
                    onGetStartedClick = {
                        isPulsing = true  // trigger a pulse on the background
                        navController.navigate("qrscan")
                    }
                )
            }

            // ── QR Scan screen ─────────────────────────────────────────────
            // Accepts an optional `startCamera` boolean argument so the Re-pair
            // flow can open the camera immediately without an extra tap.
            composable(
                route = "qrscan?startCamera={startCamera}",
                arguments = listOf(navArgument("startCamera") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val context = navController.context
                val startCamera = backStackEntry.arguments?.getBoolean("startCamera") ?: false

                QRScanScreen(
                    initialCameraActive = startCamera,
                    onQRScanned = { qrData ->

                        // Parse the raw QR string into a data map (macId, fcmToken, region, etc.)
                        val parsedData = FirestoreManager.parseQRData(qrData)

                        if (parsedData != null) {
                            // If the QR code's region differs from the detected device region,
                            // update the stored region before creating the Firestore pairing
                            val qrRegion = parsedData["serverRegion"] as? String ?: "IN"
                            val initializedRegion = DeviceManager.initializedRegion

                            if (qrRegion != initializedRegion) {
                                DeviceManager.setTargetRegion(context, qrRegion)
                            }

                            // Write the pairing data to Firestore and navigate to the confirmation screen
                            FirestoreManager.createPairing(
                                context = context,
                                qrData = parsedData,
                                onSuccess = {
                                    scope.launch {
                                        navController.navigate("connection") {
                                            popUpTo("landing") { inclusive = true }
                                        }
                                    }
                                },
                                onFailure = { e ->
                                    scope.launch {
                                        Toast.makeText(context, "Pairing failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        navController.popBackStack()
                                    }
                                }
                            )
                        } else {
                            // QR data couldn't be parsed – show an error and let the user try again
                            scope.launch {
                                Toast.makeText(context, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                                navController.navigate("qrscan") {
                                    popUpTo("landing")
                                }
                            }
                        }
                    }
                )
            }

            // ── Connection confirmation screen ─────────────────────────────
            // Shown after a successful pairing. Proceeds to Permission setup.
            composable("connection") {
                ConnectionPage(
                    onContinue = {
                        navController.navigate("permission") {
                            popUpTo("qrscan") { inclusive = true }
                        }
                    },
                    onUnpair = {
                        // Clear local pairing data and go back to landing (error recovery)
                        DeviceManager.clearPairing(navController.context)
                        navController.navigate("landing") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            // ── Permission onboarding screen ───────────────────────────────
            composable("permission") {
                PermissionPage(
                    onFinishSetup = {
                        navController.navigate("homescreen") {
                            popUpTo("permission") { inclusive = true }
                        }
                    }
                )
            }

            // ── Main settings / homescreen ─────────────────────────────────
            composable("homescreen") {
                Homescreen(
                    onRepairClick = {
                        // Clear pairing and restart the QR scan with the camera active immediately
                        DeviceManager.clearPairing(navController.context)
                        navController.navigate("qrscan?startCamera=true") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onResetPairing = {
                        // Navigate back to landing after the cloud pairing data is wiped
                        navController.navigate("landing") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
