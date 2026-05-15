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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * The sole Activity in the ClipSync Android application.
 *
 * ClipSync is a single-Activity app built with Jetpack Compose. All navigation between screens
 * is handled inside the Compose tree by [ClipSyncNavigation] via the Compose Navigation library,
 * so no additional Activities or Fragments are required.
 *
 * On startup, [onCreate] determines which screen the user should land on by consulting
 * [DeviceManager.isPaired]:
 *  - If a pairing already exists the user is sent directly to "homescreen", skipping onboarding.
 *  - If no pairing exists the user starts at "landing" to begin the QR-based setup flow.
 *
 * The animated [MeshBackground] is instantiated once inside [ClipSyncNavigation] and persists
 * across all navigation transitions so the gradient never resets or flickers between screens.
 */
class MainActivity : ComponentActivity() {

    /**
     * Entry point for the Activity. Enables edge-to-edge rendering so the Compose UI can draw
     * behind the system bars, then inflates the root Compose content with the correct start
     * destination and any flags passed through the launching Intent.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Query local device storage to decide which screen to open first.
        // For testing the UI, we force this to always go to the dashboard:
        val isPaired = DeviceManager.isPaired(this)
        val startDestination = "homescreen" // FORCED FOR TESTING

        // For already-paired devices, refresh the FCM push token so the Mac can reach this
        // device. Runs on Dispatchers.IO to avoid a network call on the main thread; the
        // coroutine lifetime is tied to the Activity so it is cancelled on destroy.
        if (isPaired) {
            lifecycleScope.launch(Dispatchers.IO) {
                FCMTokenManager.registerFCMToken(this@MainActivity)
            }
        }

        val showUpdateDialog = intent.getBooleanExtra("show_update_dialog", false)

        setContent {
            ClipSyncTheme {
                ClipSyncNavigation(
                    startDestination = startDestination,
                    showUpdateDialogOnStart = showUpdateDialog
                )
            }
        }
    }
}

/**
 * Defines the complete Compose Navigation graph for the ClipSync application and manages
 * the lifecycle of the [MeshBackground] animation that underlies every screen.
 *
 * All five routes share a single [MeshBackground] instance rendered at the root of this
 * composable so the animated gradient persists without interruption as the user navigates.
 * The background's animation speed is governed by two boolean flags:
 *
 *  - [isPulsing]    : briefly set to `true` when the user taps "Get Started" on the landing
 *                     screen, causing the background to surge at 4× speed for ~500 ms.
 *  - [isRoutePaused]: set to `true` on every route except "landing" (with a 1 s grace period
 *                     so that entering transitions finish before the background freezes).
 *  - [isAppVisible] : tracks the Activity lifecycle; the animation is suspended whenever the
 *                     app moves to the background to avoid burning CPU on invisible frames.
 *
 * Routes:
 * | Route                        | Composable         | Purpose                              |
 * |------------------------------|--------------------|--------------------------------------|
 * | `landing`                    | [LandingScreen]    | Welcome screen for first-time users  |
 * | `qrscan?startCamera={bool}`  | [QRScanScreen]     | Camera-based Mac pairing via QR code |
 * | `connection`                 | [ConnectionPage]   | Success confirmation after pairing   |
 * | `permission`                 | [PermissionPage]   | Notification / permission onboarding |
 * | `homescreen`                 | [Homescreen]       | Main dashboard and settings          |
 *
 * @param startDestination       Route name the NavHost opens first ("landing" or "homescreen").
 *                               Determined at Activity creation time based on pairing state.
 * @param showUpdateDialogOnStart When `true` the [Homescreen] surfaces an update prompt
 *                               immediately after composition. Sourced from the launch Intent.
 */
@Composable
fun ClipSyncNavigation(startDestination: String, showUpdateDialogOnStart: Boolean = false) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Set to true for ~500 ms when the user taps "Get Started", triggering a speed burst in
    // MeshBackground that adds kinetic energy to the transition into the QR scan screen.
    var isPulsing by remember { mutableStateOf(false) }
    // Freezes the background on every route except "landing". The animation is only meaningful
    // on the landing screen; pausing everywhere else conserves CPU and battery.
    var isRoutePaused by remember { mutableStateOf(false) }
    // Mirrors the Activity's foreground/background state. Animation pauses when the user
    // switches away from ClipSync and resumes when the app returns to the foreground.
    var isAppVisible by remember { mutableStateOf(true) }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    // Reacts to every navigation event. "landing" keeps the animation fully active;
    // all other routes freeze it after a 1-second delay so entering transitions (slide-in,
    // fade, etc.) have time to complete before the background canvas stops redrawing.
    LaunchedEffect(currentBackStackEntry?.destination?.route) {
        val route = currentBackStackEntry?.destination?.route
        if (route == "landing") {
            isRoutePaused = false
        } else {
            delay(1000)
            isRoutePaused = true
        }
    }

    // Automatically resets the pulse flag half a second after it was raised.
    // This makes the background speed burst feel short and snappy rather than permanent.
    LaunchedEffect(isPulsing) {
        if (isPulsing) {
            delay(500)
            isPulsing = false
        }
    }

    // Observes Activity lifecycle events to pause the background when the app is not visible.
    // The observer is attached to the LifecycleOwner and removed via onDispose to prevent
    // a memory leak if this composable leaves the composition while the observer is still live.
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

    // MeshBackground wraps the entire NavHost so the animated gradient layer persists across
    // all navigation transitions without restarting. isPaused combines the route-level and
    // app-visibility conditions so the animation stops if either signals inactivity.
    MeshBackground(
        modifier = Modifier.fillMaxSize(),
        onPulse = isPulsing,
        isPaused = isRoutePaused || !isAppVisible
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
            // Welcome screen shown to first-time and unpaired users.
            // Tapping "Get Started" triggers a brief background pulse and navigates to the QR scanner.
            composable("landing") {
                LandingScreen(
                    onGetStartedClick = {
                        isPulsing = true
                        navController.navigate("qrscan")
                    }
                )
            }

            // QR code scanner screen used to pair the Android device with a Mac.
            // The optional startCamera argument allows the Re-pair flow to open the camera
            // immediately, bypassing the manual "Scan" button tap.
            composable(
                route = "qrscan?startCamera={startCamera}",
                arguments = listOf(navArgument("startCamera") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val context = navController.context
                val startCamera = backStackEntry.arguments?.getBoolean("startCamera") ?: false

                QRScanScreen(
                    initialCameraActive = startCamera,
                    onQRScanned = { qrData ->

                        // Attempt to decode the raw QR string into a structured map containing
                        // the Mac's identifier, FCM push token, server region, and other metadata.
                        val parsedData = FirestoreManager.parseQRData(qrData)

                        if (parsedData != null) {
                            // If the Mac's target server region differs from the region this device
                            // was previously initialised with, update the local configuration first.
                            // This ensures all subsequent Firestore operations hit the correct
                            // regional database instance.
                            val qrRegion = parsedData["serverRegion"] as? String ?: "IN"
                            val initializedRegion = DeviceManager.initializedRegion

                            if (qrRegion != initializedRegion) {
                                DeviceManager.setTargetRegion(context, qrRegion)
                            }

                            // Write the pairing record to Firestore. On success, clear the back
                            // stack up to and including landing so pressing Back from the
                            // connection screen does not return the user to the QR scanner.
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
                            // The scanned QR code did not contain recognisable ClipSync pairing
                            // data. Show an error toast and return the user to the scanner to retry.
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

            // Pairing confirmation screen displayed after Firestore successfully records the link.
            // Proceeding moves the user forward to permission setup; unpairing aborts the flow
            // and returns to landing after clearing the locally stored pairing state.
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

            // Permission onboarding screen that walks the user through granting any required
            // system permissions. Finishing setup navigates to the homescreen and removes this
            // screen from the back stack so Back does not return here.
            composable("permission") {
                PermissionPage(
                    onFinishSetup = {
                        navController.navigate("homescreen") {
                            popUpTo("permission") { inclusive = true }
                        }
                    }
                )
            }

            // Main application dashboard showing clipboard sync status and settings.
            // Re-pair clears the existing pairing and reopens the camera immediately with the
            // scanner active. Reset pairing is triggered after a cloud-side wipe and returns
            // to the landing screen, clearing the entire back stack.
            composable("homescreen") {
                DashboardScreen(
                    showUpdateDialogOnStart = showUpdateDialogOnStart,
                    onRepairClick = {
                        // Erase the local pairing record and immediately open the QR scanner
                        // with the camera active so the user can scan the new Mac QR code right away.
                        DeviceManager.clearPairing(navController.context)
                        navController.navigate("qrscan?startCamera=true") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onResetPairing = {
                        // Called after the cloud-side pairing document has been deleted.
                        // Navigate back to landing and wipe the entire back stack so the user
                        // starts a fresh onboarding session from the beginning.
                        navController.navigate("landing") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
