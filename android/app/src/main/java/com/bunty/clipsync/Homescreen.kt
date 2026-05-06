package com.bunty.clipsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import kotlin.math.min
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bunty.clipsync.R


/**
 * Main settings/home screen for ClipSync.
 *
 * Displays the paired Mac device, clipboard sync direction toggles, live system status
 * for each required permission/service, and quick-action buttons. It also handles
 * two modal dialogs: an in-app update prompt and a destructive "reset pairing" confirmation.
 *
 * @param showUpdateDialogOnStart When true the update dialog is shown immediately on first
 *   composition, e.g. when launched from a notification deep-link.
 * @param onRepairClick Callback invoked when the user taps the "Re-pair" button, allowing
 *   the parent to navigate to the pairing flow.
 * @param onResetPairing Callback invoked after a successful pairing reset so the parent can
 *   navigate back to the onboarding screen.
 */
@Composable
fun Homescreen(
    showUpdateDialogOnStart: Boolean = false,
    onRepairClick: () -> Unit = {},
    onResetPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp

    // -------------------------------------------------------------------------
    // Responsive scaling — normalise against a 412 × 915 dp reference device so
    // that font sizes, padding, and icon sizes look proportional on all screens.
    // -------------------------------------------------------------------------
    val screenWidth = configuration.screenWidthDp.dp
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight / 915f
    // Use the smaller axis scale to avoid clipping on either dimension.
    val scale = min(widthScale, heightScale)
    val titleFontSize = (58 * scale).coerceIn(42f, 58f).sp

    val scope = rememberCoroutineScope()

    // Fetch the saved paired Mac name once; it doesn't change during this session.
    val macDeviceName = remember { DeviceManager.getPairedMacDeviceName(context) }

    // -------------------------------------------------------------------------
    // Load the Roboto font family from bundled font resources so all text in this
    // screen uses consistent typography regardless of the system font.
    // -------------------------------------------------------------------------
    val robotoFontFamily = remember {
        FontFamily(
            Font(R.font.roboto_regular, FontWeight.Normal),
            Font(R.font.roboto_medium, FontWeight.Medium),
            Font(R.font.roboto_bold, FontWeight.Bold),
            Font(R.font.roboto_black, FontWeight.Black)
        )
    }

    // -------------------------------------------------------------------------
    // UI visibility / animation state
    // -------------------------------------------------------------------------

    // Toggled to true after a short delay so the fade-in animation plays on first load.
    var showContent by remember { mutableStateOf(false) }

    // -------------------------------------------------------------------------
    // Permission / service status flags — each is re-evaluated on every ON_RESUME.
    // -------------------------------------------------------------------------

    // Whether the ClipboardAccessibilityService is active (required to read the clipboard).
    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    // Whether the app is whitelisted from battery optimisation (keeps background sync alive).
    var isBatteryUnrestricted by remember { mutableStateOf(false) }

    // Whether both RECEIVE_SMS and READ_SMS permissions are granted for OTP detection.
    var isSmsPermissionGranted by remember { mutableStateOf(false) }

    // Whether the app's NotificationListenerService is enabled (used for email OTP detection).
    var isNotificationListenerEnabled by remember { mutableStateOf(false) }

    // -------------------------------------------------------------------------
    // Dialog visibility state
    // -------------------------------------------------------------------------
    var showUpdateDialog by remember { mutableStateOf(showUpdateDialogOnStart) }
    var updateInfo by remember { mutableStateOf<UpdateNotificationManager.UpdateInfo?>(null) }

    var showResetDialog by remember { mutableStateOf(false) }
    val currentVersion = "2.0.0"

    // -------------------------------------------------------------------------
    // Clipboard sync direction preferences — persisted via DeviceManager SharedPrefs.
    // -------------------------------------------------------------------------
    var syncToMac by remember { mutableStateOf(DeviceManager.isSyncToMacEnabled(context)) }
    var syncFromMac by remember { mutableStateOf(DeviceManager.isSyncFromMacEnabled(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // -------------------------------------------------------------------------
    // Re-checks all permissions and service states every time the screen becomes
    // visible (ON_RESUME). This catches changes the user made in system Settings
    // while ClipSync was in the background.
    // -------------------------------------------------------------------------
    fun checkPermissions() {
        isAccessibilityEnabled = checkServiceStatus(context, ClipboardAccessibilityService::class.java)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)

        // Both READ_SMS and RECEIVE_SMS must be granted for full OTP interception.
        isSmsPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                                 ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        isNotificationListenerEnabled = isNotificationServiceEnabled(context)
    }

    // Attach a lifecycle observer so checkPermissions() is called on every resume.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // -------------------------------------------------------------------------
    // Initial setup: short delay before revealing content (gives the background
    // gradient time to render), then run the first permission check and surface
    // any update notification that was saved by UpdateNotificationManager.
    // -------------------------------------------------------------------------
    LaunchedEffect(Unit) {
        delay(100)
        showContent = true
        checkPermissions()

        // If a newer app version was detected in the background, show the update dialog.
        val pending = UpdateNotificationManager.getPendingUpdate(context)
        if (pending != null) {
            updateInfo = pending
            showUpdateDialog = true
        }
    }

    // =========================================================================
    // Update available dialog
    // Shown when UpdateNotificationManager has stored a pending update. Offers a
    // direct "Download" button that opens the release URL in the browser, and a
    // "Later" option that simply dismisses and clears the stored update info.
    // =========================================================================
    if (showUpdateDialog && updateInfo != null) {
        val isUpdateUrlTrusted = UrlAllowlistManager.isUrlTrusted(context, updateInfo!!.downloadUrl)

        AlertDialog(
            onDismissRequest = {
                showUpdateDialog = false
                UpdateNotificationManager.clearPendingUpdate(context)
            },
            title = {
                Text(text = if (isUpdateUrlTrusted) "Update Available 🚀" else "⚠️ Update from untrusted source")
            },
            text = {
                Column {
                    if (!isUpdateUrlTrusted) {
                        Text(
                            text = "Warning: This update links to an unrecognised domain (${UrlAllowlistManager.extractHost(updateInfo!!.downloadUrl)}). Proceed only if you trust this source.",
                            color = Color(0xFFFF9500),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text("Version ${updateInfo!!.version} is now available!")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = updateInfo!!.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Launch the browser to the release download URL, then dismiss.
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo!!.downloadUrl))
                        context.startActivity(intent)
                        showUpdateDialog = false
                        UpdateNotificationManager.clearPendingUpdate(context)
                    }
                ) {
                    Text(if (isUpdateUrlTrusted) "Download" else "Open Anyway")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUpdateDialog = false
                        UpdateNotificationManager.clearPendingUpdate(context)
                    }
                ) {
                    Text("Later")
                }
            }
        )
    }

    // =========================================================================
    // Reset pairing confirmation dialog
    // This is a destructive action: it calls FirestoreManager.clearPairing() which
    // deletes the pairing document from Firestore and navigates the user back to
    // onboarding. Shown in red to reinforce the destructive nature of the action.
    // =========================================================================
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(text = "Reset Pairing?") },
            text = {
                Text("This will unpair your device and delete all pairing data from the cloud. You'll need to pair again to use ClipSync.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false

                        // Delete the pairing record from Firestore, then call back so the
                        // parent composable can navigate away from this screen.
                        FirestoreManager.clearPairing(
                            context,
                            onSuccess = {
                                Toast.makeText(context, "Pairing reset successfully", Toast.LENGTH_SHORT).show()
                                onResetPairing()
                            },
                            onFailure = { e ->
                                Toast.makeText(context, "Failed to reset pairing: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                ) {
                    Text("Reset", color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // =========================================================================
    // Real-time Firestore clipboard listener
    // Subscribes to incoming clipboard updates from the paired Mac. The guard on
    // isSyncFromMacEnabled ensures the user's preference is respected at runtime.
    // The listener registration is automatically removed when the composable leaves
    // the composition via onDispose.
    // =========================================================================
    DisposableEffect(Unit) {
        val registration = FirestoreManager.listenToClipboard(context) { text ->

             if (DeviceManager.isSyncFromMacEnabled(context)) {

             }
        }
        onDispose { registration?.remove() }
    }

    // Animate the entire content area from fully transparent to fully opaque on first load.
    val contentAlpha by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = tween(1000)
    )

    // =========================================================================
    // Root layout: full-screen Box with horizontal padding that scales with the
    // device width, containing a single vertically scrollable Column.
    // =========================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = (16 * widthScale).dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

            // Top padding pushes content below any system status bar / notch area.
            Spacer(modifier = Modifier.height((72 * heightScale).dp))

            // Large bold "Settings" title — fades in with the rest of the content.
            Text(
                text = "Settings",
                fontFamily = robotoFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = titleFontSize,
                color = Color.White,
                letterSpacing = (-0.03).em,
                modifier = Modifier
                    .alpha(contentAlpha)
                    .padding(start = 4.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // All cards slide up and fade in together once showContent becomes true.
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { 40 }, animationSpec = tween(400))
            ) {
                 Column(
                    verticalArrangement = Arrangement.spacedBy((28 * scale).dp)
                ) {

                    // =============================================================
                    // DEVICE SECTION
                    // Shows the name of the currently paired Mac and a Re-pair button
                    // that navigates back to the pairing flow without wiping data.
                    // =============================================================
                    Column {
                        SectionHeader(text = "Device", fontFamily = robotoFontFamily, scale = scale)

                        InnerWhiteCard(scale = scale) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding((20 * scale).dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {

                                // Laptop icon + "Connected to <device name>" label
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.Computer,
                                        contentDescription = "Laptop",
                                        tint = Color(0xFF007AFF),
                                        modifier = Modifier.size((36 * scale).dp)
                                    )
                                    Spacer(modifier = Modifier.width((16 * scale).dp))
                                    Column {
                                        Text(
                                            text = "Connected to",
                                            fontFamily = robotoFontFamily,
                                            fontSize = (13 * scale).coerceIn(11f, 13f).sp,
                                            color = Color(0xFF3C3C43).copy(alpha = 0.6f)
                                        )
                                        Text(
                                            text = macDeviceName,
                                            fontFamily = robotoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (17 * scale).coerceIn(15f, 17f).sp,
                                            color = Color.Black,
                                            maxLines = 1
                                        )
                                    }
                                }

                                // Tapping Re-pair delegates to the parent without deleting pairing data,
                                // allowing the user to re-scan the QR code if the connection is stale.
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape((20 * scale).dp))
                                        .background(Color(0xFF007AFF))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { onRepairClick() }
                                        .padding(horizontal = (18 * scale).dp, vertical = (10 * scale).dp)
                                ) {
                                    Text(
                                        text = "Re-pair",
                                        fontFamily = robotoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (14 * scale).coerceIn(12f, 14f).sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // =============================================================
                    // PREFERENCES SECTION
                    // Two independent toggles controlling clipboard sync direction.
                    // Each toggle persists its state immediately via DeviceManager.
                    // =============================================================
                    Column {
                        SectionHeader(text = "Preferences", fontFamily = robotoFontFamily, scale = scale)

                        InnerWhiteCard(scale = scale) {
                            Column(modifier = Modifier.padding((20 * scale).dp)) {

                                // Send Android clipboard text up to the Mac automatically.
                                PreferenceRow(
                                    label = "Sync to Mac",
                                    checked = syncToMac,
                                    onCheckedChange = {
                                        syncToMac = it
                                        DeviceManager.setSyncToMacEnabled(context, it)
                                    },
                                    fontFamily = robotoFontFamily,
                                    scale = scale
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = (12 * scale).dp), color = Color(0xFFE5E5EA))

                                // Apply text arriving from the Mac to the local Android clipboard.
                                PreferenceRow(
                                    label = "Sync from Mac",
                                    checked = syncFromMac,
                                    onCheckedChange = {
                                        syncFromMac = it
                                        DeviceManager.setSyncFromMacEnabled(context, it)
                                    },
                                    fontFamily = robotoFontFamily,
                                    scale = scale
                                )
                            }
                        }
                    }

                    // =============================================================
                    // TRUSTED DOMAINS SECTION
                    // Displays the URL domain allowlist used to validate URLs from
                    // FCM push notifications. Default domains cannot be removed.
                    // Users can add or remove custom trusted domains.
                    // =============================================================
                    TrustedDomainsSection(
                        fontFamily = robotoFontFamily,
                        scale = scale
                    )

                    // =============================================================
                    // SYSTEM STATUS SECTION
                    // Each row shows whether a required Android permission or service
                    // is active. Tapping an inactive row opens the relevant system
                    // settings screen so the user can grant the missing permission.
                    // =============================================================
                    Column {
                        SectionHeader(text = "System Status", fontFamily = robotoFontFamily, scale = scale)

                        InnerWhiteCard(scale = scale) {
                            Column(modifier = Modifier.padding((20 * scale).dp)) {

                                // Accessibility service — required to intercept clipboard changes.
                                StatusRow(
                                    label = "Clipboard Sync",
                                    isActive = isAccessibilityEnabled,
                                    fontFamily = robotoFontFamily,
                                    scale = scale,
                                    onClick = {
                                        if (!isAccessibilityEnabled) {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            context.startActivity(intent)
                                            Toast.makeText(context, "Enable ClipSync Service", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = (16 * scale).dp), color = Color(0xFFE5E5EA))

                                // Mac pairing status — derived from whether a real device name is stored.
                                StatusRow(
                                    label = "Mac Clipboard",
                                    isActive = (macDeviceName != "Unknown Device"),
                                    fontFamily = robotoFontFamily,
                                    scale = scale
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = (16 * scale).dp), color = Color(0xFFE5E5EA))

                                // Battery optimisation whitelist — Android may kill background services
                                // if this is not granted, interrupting clipboard sync while screen is off.
                                StatusRow(
                                    label = "Background Sync",
                                    isActive = isBatteryUnrestricted,
                                    isWarning = true,
                                    fontFamily = robotoFontFamily,
                                    scale = scale,
                                    onClick = {
                                        if (!isBatteryUnrestricted) {
                                            try {
                                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not open Battery Settings", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = (16 * scale).dp), color = Color(0xFFE5E5EA))

                                // SMS permissions — needed to read incoming OTP messages and forward them to Mac.
                                StatusRow(
                                    label = "SMS OTP Detection",
                                    isActive = isSmsPermissionGranted,
                                    fontFamily = robotoFontFamily,
                                    scale = scale,
                                    onClick = {
                                        if (!isSmsPermissionGranted) {
                                            try {
                                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                }
                                                context.startActivity(intent)
                                                Toast.makeText(context, "Enable SMS Permissions", Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not open App Settings", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = (16 * scale).dp), color = Color(0xFFE5E5EA))

                                // Notification listener — needed to detect OTPs arriving via email apps.
                                StatusRow(
                                    label = "Email OTP Detection",
                                    isActive = isNotificationListenerEnabled,
                                    fontFamily = robotoFontFamily,
                                    scale = scale,
                                    onClick = {
                                        if (!isNotificationListenerEnabled) {
                                            try {
                                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                                context.startActivity(intent)
                                                Toast.makeText(context, "Enable Notification Access for ClipSync", Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not open Notification Settings", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // If any permission is missing, show a concise warning banner below the card
                        // to draw the user's attention without blocking the rest of the UI.
                        if (!isAccessibilityEnabled || !isBatteryUnrestricted || !isSmsPermissionGranted || !isNotificationListenerEnabled) {
                            Spacer(modifier = Modifier.height((12 * scale).dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Warning",
                                    tint = Color(0xFFFF9500),
                                    modifier = Modifier.size((16 * scale).dp).padding(top = (2 * scale).dp)
                                )
                                Spacer(modifier = Modifier.width((8 * scale).dp))
                                Text(
                                    text = "Some features are disabled. Check Android Settings.",
                                    fontFamily = robotoFontFamily,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = (13 * scale).coerceIn(11f, 13f).sp,
                                    color = Color(0xFF3C3C43).copy(alpha = 0.8f),
                                    lineHeight = (18 * scale).coerceIn(14f, 18f).sp
                                )
                            }
                        }
                    }

                    // =============================================================
                    // ACTIONS SECTION
                    // Quick-action buttons for testing and maintenance tasks. These
                    // are primarily useful during setup or debugging.
                    // =============================================================
                    Column {
                        SectionHeader(text = "Actions", fontFamily = robotoFontFamily, scale = scale)

                        // Sends a fixed test string to the Mac via Firestore to verify the
                        // cloud sync pipeline is working end-to-end.
                        ActionButton(
                            text = "Send Test Clipboard",
                            icon = Icons.Default.Share,
                            backgroundColor = Color(0xFF007AFF),
                            fontFamily = robotoFontFamily,
                            scale = scale
                        ) {
                             FirestoreManager.sendClipboard(context, "Hello from ClipSync! ")
                             Toast.makeText(context, "Sent to Mac!", Toast.LENGTH_SHORT).show()
                        }

                        Spacer(modifier = Modifier.height((16 * scale).dp))

                        // Deletes the current clipboard value stored in Firestore so neither
                        // device inadvertently re-applies stale content after a reconnect.
                        ActionButton(
                            text = "Clear Cloud Clipboard",
                            icon = Icons.Default.Delete,
                            backgroundColor = Color(0xFFFF3B30),
                            fontFamily = robotoFontFamily,
                            scale = scale
                        ) {
                            FirestoreManager.clearClipboard(
                                context,
                                onSuccess = {
                                    Toast.makeText(context, "Cloud clipboard cleared", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = {
                                    Toast.makeText(context, "Failed to clear", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height((16 * scale).dp))

                        // Simulates an OTP arriving on the device so the user can confirm that
                        // the full detection → copy → Mac-forward pipeline works without needing
                        // to wait for a real SMS or email OTP.
                        ActionButton(
                            text = "Test OTP Detection",
                            icon = Icons.Default.CheckCircle,
                            backgroundColor = Color(0xFF34C759),
                            fontFamily = robotoFontFamily,
                            scale = scale
                        ) {
                            // Generate a random 6-digit code to mimic a real OTP.
                            val testOTP = (100000..999999).random().toString()

                            // Place the OTP on the local Android clipboard (same path a real SMS would use).
                            ClipboardGhostActivity.copyToClipboard(context, testOTP)

                            // Trigger the notification service so the Mac companion app receives the OTP.
                            OTPNotificationService.notifyOTPDetected(context, testOTP)

                            Toast.makeText(
                                context,
                                "Test OTP sent to Mac: $testOTP",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Spacer(modifier = Modifier.height((16 * scale).dp))

                        // Opens the reset pairing confirmation dialog. The actual Firestore
                        // deletion only happens if the user confirms in the dialog.
                        ActionButton(
                            text = "Reset Pairing",
                            icon = Icons.Default.Refresh,
                            backgroundColor = Color(0xFFFF9500),
                            fontFamily = robotoFontFamily,
                            scale = scale
                        ) {
                            showResetDialog = true
                        }
                    }
                }
            }

            // Push the version footer to the bottom of the scroll area.
            Spacer(modifier = Modifier.weight(1f))

            // Version footer — muted style so it doesn't compete with the action content.
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = (32 * scale).dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ClipSync v2.0.0",
                    fontFamily = robotoFontFamily,
                    fontSize = (12 * scale).coerceIn(10f, 12f).sp,
                    color = Color(0xFF3C3C43).copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height((24 * scale).dp))
        }
    }
}


/**
 * A small section heading displayed above each card group.
 *
 * Uses SemiBold weight and a slightly muted grey colour to provide visual hierarchy
 * without drawing attention away from the card content below it.
 *
 * @param text The heading label to display (e.g. "Device", "Preferences").
 * @param fontFamily The Roboto [FontFamily] to apply.
 * @param scale Responsive scale factor derived from the device screen dimensions.
 */
@Composable
fun SectionHeader(text: String, fontFamily: FontFamily, scale: Float = 1f) {
    Text(
        text = text,
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = (17 * scale).coerceIn(15f, 17f).sp,
        color = Color(0xFF3C3C43).copy(alpha = 0.8f),
        modifier = Modifier.padding(start = (6 * scale).dp, bottom = (10 * scale).dp)
    )
}


/**
 * A frosted-glass style container card used to group related settings rows.
 *
 * Combines a subtle drop shadow, rounded corners, a semi-transparent white
 * background, and a thin border to produce a layered "card on background" effect
 * that is consistent with the app's overall design language.
 *
 * @param scale Responsive scale factor for corner radius and shadow elevation.
 * @param content The composable content to render inside the card.
 */
@Composable
fun InnerWhiteCard(scale: Float = 1f, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = (8 * scale).dp,
                shape = RoundedCornerShape((24 * scale).dp),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .clip(RoundedCornerShape((24 * scale).dp))
            .background(Color.White.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = Color(0xFFF2F2F7),
                shape = RoundedCornerShape((24 * scale).dp)
            )
    ) {
        content()
    }
}


/**
 * A full-width row displaying a labelled toggle switch for a boolean preference.
 *
 * The label sits on the left and the [Switch] on the right. The switch uses iOS-
 * inspired green/grey colours that match the overall design.
 *
 * @param label Human-readable name for the preference.
 * @param checked Current toggle state.
 * @param onCheckedChange Called with the new boolean value when the user flips the switch.
 * @param fontFamily The Roboto [FontFamily] to use for the label.
 * @param scale Responsive scale factor for font sizes and the switch itself.
 */
@Composable
fun PreferenceRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, fontFamily: FontFamily, scale: Float = 1f) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.Black,
            fontSize = (16 * scale).coerceIn(14f, 16f).sp,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(scale),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE9E9EA),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}


/**
 * A tappable row that communicates the live status of a single system permission or service.
 *
 * When [isActive] is true, a green check icon and an "Active" label are shown.
 * When [isActive] is false, a warning/error icon and a red "Fix" pill are shown, and
 * the entire row is tappable via [onClick] so the user can jump directly to the
 * relevant Android settings screen.
 *
 * The [isWarning] flag changes the inactive icon colour from red to amber — used for
 * permissions that degrade performance (e.g. battery optimisation) rather than blocking
 * core functionality entirely.
 *
 * @param label Display name for the feature being checked (e.g. "Clipboard Sync").
 * @param isActive Whether the permission / service is currently active.
 * @param isWarning When true and [isActive] is false, shows an amber warning instead of red error.
 * @param fontFamily The Roboto [FontFamily] for label text.
 * @param scale Responsive scale factor.
 * @param onClick Called when the row is tapped; typically opens a system settings screen.
 */
@Composable
fun StatusRow(
    label: String,
    isActive: Boolean,
    isWarning: Boolean = false,
    fontFamily: FontFamily,
    scale: Float = 1f,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(vertical = (12 * scale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show a checkmark when active, a warning triangle when not.
        val icon = when {
            isActive -> Icons.Default.CheckCircle
            else -> Icons.Default.Warning
        }
        // Green = active, amber = degraded/warning, red = blocked/missing.
        val iconColor = when {
            isActive -> Color(0xFF34C759)
            isWarning -> Color(0xFFFF9500)
            else -> Color(0xFFFF3B30)
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size((24 * scale).dp)
        )
        Spacer(modifier = Modifier.width((14 * scale).dp))
        Text(
            text = label,
            color = Color.Black,
            fontSize = (16 * scale).coerceIn(14f, 16f).sp,
            fontFamily = fontFamily,
            modifier = Modifier.weight(1f)
        )

        if (isActive) {
            Text(
                text = "Active",
                color = Color(0xFF34C759),
                fontSize = (14 * scale).coerceIn(12f, 14f).sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium
            )
        } else {
            // "Fix" pill — the subtle red tint makes it stand out as actionable without
            // being as alarming as a solid red background.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape((14 * scale).dp))
                    .background(Color(0xFFFF3B30).copy(alpha = 0.1f))
                    .padding(horizontal = (12 * scale).dp, vertical = (6 * scale).dp)
            ) {
                Text(
                    text = "Fix",
                    color = Color(0xFFFF3B30),
                    fontSize = (13 * scale).coerceIn(11f, 13f).sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


/**
 * A full-width pill-shaped button used for the quick-action items in the Actions section.
 *
 * Uses a frosted-glass style (semi-transparent white background + coloured border) rather
 * than a solid fill, keeping the button visually lightweight against the app's gradient
 * background. The [backgroundColor] doubles as the icon tint, border colour, and text colour.
 *
 * @param text Button label.
 * @param icon Leading icon displayed to the left of the label.
 * @param backgroundColor Brand colour for this action (drives icon, text, and border tint).
 * @param fontFamily The Roboto [FontFamily] for the button label.
 * @param scale Responsive scale factor for height, corner radius, and font sizes.
 * @param onClick Called when the button is tapped.
 */
@Composable
fun ActionButton(text: String, icon: ImageVector, backgroundColor: Color, fontFamily: FontFamily, scale: Float = 1f, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((56 * scale).dp)
            .shadow(
                elevation = (4 * scale).dp,
                shape = RoundedCornerShape((28 * scale).dp),
                // Tinted shadow colour matches the button's accent colour for a cohesive glow.
                spotColor = backgroundColor.copy(alpha = 0.2f)
            )
            .clip(RoundedCornerShape((28 * scale).dp))
            .background(Color.White.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = backgroundColor.copy(alpha = 0.3f),
                shape = RoundedCornerShape((28 * scale).dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = backgroundColor,
                modifier = Modifier.size((22 * scale).dp)
            )
            Spacer(modifier = Modifier.width((10 * scale).dp))
            Text(
                text = text,
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (17 * scale).coerceIn(15f, 17f).sp,
                color = backgroundColor
            )
        }
    }
}


/**
 * Composable section that displays and manages the trusted URL domain allowlist.
 *
 * Shows default domains (non-removable) and user-added domains (removable via tap).
 * Includes a text field for adding new custom domains. Domains are displayed as
 * chip-like pills — default domains in blue, user domains in green with an "×" to remove.
 *
 * @param fontFamily The Roboto [FontFamily] for consistent typography.
 * @param scale Responsive scale factor derived from screen dimensions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrustedDomainsSection(fontFamily: FontFamily, scale: Float = 1f) {
    val context = LocalContext.current
    var userDomains by remember { mutableStateOf(UrlAllowlistManager.getUserDomains(context)) }
    var newDomain by remember { mutableStateOf("") }
    var showAddField by remember { mutableStateOf(false) }

    Column {
        SectionHeader(text = "Trusted Domains", fontFamily = fontFamily, scale = scale)

        InnerWhiteCard(scale = scale) {
            Column(modifier = Modifier.padding((20 * scale).dp)) {
                Text(
                    text = "URLs from push notifications are checked against these domains. Untrusted URLs show a warning.",
                    fontFamily = fontFamily,
                    fontSize = (13 * scale).coerceIn(11f, 13f).sp,
                    color = Color(0xFF3C3C43).copy(alpha = 0.6f),
                    lineHeight = (18 * scale).coerceIn(14f, 18f).sp
                )

                Spacer(modifier = Modifier.height((16 * scale).dp))

                // Display default (built-in) domains as non-removable chips.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
                    verticalArrangement = Arrangement.spacedBy((8 * scale).dp)
                ) {
                    UrlAllowlistManager.DEFAULT_DOMAINS.sorted().forEach { domain ->
                        DomainChip(
                            domain = domain,
                            isDefault = true,
                            fontFamily = fontFamily,
                            scale = scale,
                            onRemove = {}
                        )
                    }
                    userDomains.sorted().forEach { domain ->
                        DomainChip(
                            domain = domain,
                            isDefault = false,
                            fontFamily = fontFamily,
                            scale = scale,
                            onRemove = {
                                UrlAllowlistManager.removeUserDomain(context, domain)
                                userDomains = UrlAllowlistManager.getUserDomains(context)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height((16 * scale).dp))

                if (showAddField) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = newDomain,
                            onValueChange = { newDomain = it.lowercase().trim() },
                            placeholder = {
                                Text(
                                    "example.com",
                                    fontSize = (14 * scale).coerceIn(12f, 14f).sp
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = (14 * scale).coerceIn(12f, 14f).sp,
                                fontFamily = fontFamily
                            )
                        )
                        Spacer(modifier = Modifier.width((8 * scale).dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape((16 * scale).dp))
                                .background(Color(0xFF34C759))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (newDomain.isNotBlank() && newDomain.contains(".")) {
                                        UrlAllowlistManager.addUserDomain(context, newDomain)
                                        userDomains = UrlAllowlistManager.getUserDomains(context)
                                        newDomain = ""
                                        showAddField = false
                                    }
                                }
                                .padding(horizontal = (14 * scale).dp, vertical = (8 * scale).dp)
                        ) {
                            Text(
                                text = "Add",
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = (14 * scale).coerceIn(12f, 14f).sp,
                                color = Color.White
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape((16 * scale).dp))
                            .background(Color(0xFF007AFF).copy(alpha = 0.1f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showAddField = true }
                            .padding(horizontal = (14 * scale).dp, vertical = (8 * scale).dp)
                    ) {
                        Text(
                            text = "+ Add Domain",
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (14 * scale).coerceIn(12f, 14f).sp,
                            color = Color(0xFF007AFF)
                        )
                    }
                }
            }
        }
    }
}


/**
 * A chip-style pill displaying a single domain name.
 *
 * Default domains are shown in blue and cannot be removed. User-added domains are
 * shown in green with an "×" suffix that triggers removal on tap.
 *
 * @param domain The domain string to display.
 * @param isDefault True if this is a built-in default domain (non-removable).
 * @param fontFamily Font family for the label text.
 * @param scale Responsive scale factor.
 * @param onRemove Callback invoked when a user domain's "×" is tapped.
 */
@Composable
fun DomainChip(
    domain: String,
    isDefault: Boolean,
    fontFamily: FontFamily,
    scale: Float = 1f,
    onRemove: () -> Unit
) {
    val chipColor = if (isDefault) Color(0xFF007AFF) else Color(0xFF34C759)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape((14 * scale).dp))
            .background(chipColor.copy(alpha = 0.1f))
            .then(
                if (!isDefault) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onRemove() }
                } else Modifier
            )
            .padding(horizontal = (12 * scale).dp, vertical = (6 * scale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = domain,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (13 * scale).coerceIn(11f, 13f).sp,
            color = chipColor
        )
        if (!isDefault) {
            Spacer(modifier = Modifier.width((6 * scale).dp))
            Text(
                text = "×",
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (14 * scale).coerceIn(12f, 14f).sp,
                color = chipColor.copy(alpha = 0.7f)
            )
        }
    }
}


/**
 * Returns true if the given accessibility [service] class is currently enabled on the device.
 *
 * Queries the [AccessibilityManager] for all active services and matches by class name.
 * This is the reliable way to check service status because [Context.bindService] cannot be
 * used for accessibility services.
 *
 * @param context Application or Activity context.
 * @param service The [Class] of the accessibility service to check (e.g. [ClipboardAccessibilityService]).
 */
private fun checkServiceStatus(context: Context, service: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.name == service.name }
}


/**
 * Returns true if this app's [NotificationListenerService] is currently enabled.
 *
 * Android stores active notification listeners as a colon-separated string in
 * [Settings.Secure]. This function checks whether the app's package name appears
 * in that string, which is sufficient to confirm the listener is registered.
 *
 * @param context Application or Activity context used to read [Settings.Secure].
 */
private fun isNotificationServiceEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    return enabledListeners?.contains(packageName) == true
}
