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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bunty.clipsync.R

@Composable
fun Homescreen(
    onRepairClick: () -> Unit = {},
    onResetPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp
    // --- Responsive Scaling Constants ---
    // Reference Design: 412x915 dp
    val screenWidth = configuration.screenWidthDp.dp
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight / 915f
    val scale = min(widthScale, heightScale)
    val titleFontSize = (58 * scale).coerceIn(42f, 58f).sp
    
    val scope = rememberCoroutineScope()
    val macDeviceName = remember { DeviceManager.getPairedMacDeviceName(context) }
    
    // Roboto Font Family
    val robotoFontFamily = remember {
        FontFamily(
            Font(R.font.roboto_regular, FontWeight.Normal),
            Font(R.font.roboto_medium, FontWeight.Medium),
            Font(R.font.roboto_bold, FontWeight.Bold),
            Font(R.font.roboto_black, FontWeight.Black)
        )
    }

    // --- State Initialization ---
    
    // UI Visibility State
    var showContent by remember { mutableStateOf(false) }
    
    // Permission States
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isBatteryUnrestricted by remember { mutableStateOf(false) }
    var isSmsPermissionGranted by remember { mutableStateOf(false) }
    var isNotificationListenerEnabled by remember { mutableStateOf(false) }

    // Update Checker State (Version Management)
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val currentVersion = "1.0.0"

    // Feature Toggles (Preferences)
    var syncToMac by remember { mutableStateOf(DeviceManager.isSyncToMacEnabled(context)) }
    var syncFromMac by remember { mutableStateOf(DeviceManager.isSyncFromMacEnabled(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Check permissions function
    fun checkPermissions() {
        isAccessibilityEnabled = checkServiceStatus(context, ClipboardAccessibilityService::class.java)
        
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)

        isSmsPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                                 ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        // Check if notification listener is enabled
        isNotificationListenerEnabled = isNotificationServiceEnabled(context)
    }

    // Auto-refresh on Resume
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

    LaunchedEffect(Unit) {
        delay(100)
        showContent = true
        checkPermissions() // Initial check
        
        // Check for updates
        scope.launch {
            val info = UpdateChecker.checkForUpdates("v$currentVersion")
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            }
        }
    }

    // Update Dialog
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(text = "Update Available ") },
            text = { 
                Column {
                    Text("A new version (${updateInfo!!.version}) is available!")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Safe to update? Yes. It's from your own repo.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo!!.downloadUrl))
                        context.startActivity(intent)
                        showUpdateDialog = false
                    }
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Later")
                }
            }
        )
    }

    // Reset Pairing Confirmation Dialog
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

    // --- Side Effects ---

    // 1. Service Listener for Real-Time Sync
    // Listens to Firestore changes and notifies user/updates state if needed
    DisposableEffect(Unit) {
        val registration = FirestoreManager.listenToClipboard(context) { text ->
             // Only process if Sync FROM Mac is enabled
             if (DeviceManager.isSyncFromMacEnabled(context)) {
                 // The service or listener handles the actual clipboard set logic
                 // But we might want to show a toast or something here if managed in UI
             }
        }
        onDispose { registration?.remove() }
    }

    // Animation State
    val contentAlpha by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = tween(1000)
    )

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
            Spacer(modifier = Modifier.height((72 * heightScale).dp))

            // Title - "Settings" (White Color as requested)
            Text(
                text = "Settings",
                fontFamily = robotoFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = titleFontSize,
                color = Color.White, // CHANGED TO WHITE
                letterSpacing = (-0.03).em,
                modifier = Modifier
                    .alpha(contentAlpha)
                    .padding(start = 4.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Main Content
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { 40 }, animationSpec = tween(400))
            ) {
                 Column(
                    verticalArrangement = Arrangement.spacedBy((28 * scale).dp)
                ) {
                    // --- Device Management ---
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

                    // --- Preferences (Sync Toggles) ---
                    Column {
                        SectionHeader(text = "Preferences", fontFamily = robotoFontFamily, scale = scale)
                        
                        InnerWhiteCard(scale = scale) {
                            Column(modifier = Modifier.padding((20 * scale).dp)) {
                                // Sync To Mac
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
                                
                                // Sync From Mac
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

                    // --- System Status ---
                    Column {
                        SectionHeader(text = "System Status", fontFamily = robotoFontFamily, scale = scale)
                        
                        InnerWhiteCard(scale = scale) {
                            Column(modifier = Modifier.padding((20 * scale).dp)) {
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

                                // Remote Device Status
                                StatusRow(
                                    label = "Mac Clipboard", // Changed name
                                    isActive = (macDeviceName != "Unknown Device"),
                                    fontFamily = robotoFontFamily,
                                    scale = scale
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = (16 * scale).dp), color = Color(0xFFE5E5EA))

                                // Battery Optimization Status
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

                                // OTP Detection Status (SMS)
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

                                // Email OTP Detection Status
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
                        
                        // Show warning if ANY critical permission is missing
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

                    // --- Actions ---
                    Column {
                        SectionHeader(text = "Actions", fontFamily = robotoFontFamily, scale = scale)

                        // Send Test
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
                        
                        // Clear Cloud Clipboard
                        ActionButton(
                            text = "Clear Cloud Clipboard",
                            icon = Icons.Default.Delete,
                            backgroundColor = Color(0xFFFF3B30), // Red
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

                        // Test OTP Detection
                        ActionButton(
                            text = "Test OTP Detection",
                            icon = Icons.Default.CheckCircle,
                            backgroundColor = Color(0xFF34C759), // Green
                            fontFamily = robotoFontFamily,
                            scale = scale
                        ) {
                            // Generate a test OTP code
                            val testOTP = (100000..999999).random().toString()

                            // Copy to clipboard using ClipboardGhostActivity
                            ClipboardGhostActivity.copyToClipboard(context, testOTP)

                            // Send OTP notification to Mac
                            OTPNotificationService.notifyOTPDetected(context, testOTP)

                            // Show confirmation
                            Toast.makeText(
                                context,
                                "Test OTP sent to Mac: $testOTP",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Spacer(modifier = Modifier.height((16 * scale).dp))

                        // Reset Pairing
                        ActionButton(
                            text = "Reset Pairing",
                            icon = Icons.Default.Refresh,
                            backgroundColor = Color(0xFFFF9500), // Orange
                            fontFamily = robotoFontFamily,
                            scale = scale
                        ) {
                            showResetDialog = true
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Footer
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = (32 * scale).dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ClipSync v1.0.0",
                    fontFamily = robotoFontFamily,
                    fontSize = (12 * scale).coerceIn(10f, 12f).sp,
                    color = Color(0xFF3C3C43).copy(alpha = 0.4f)
                )
            }
            
            Spacer(modifier = Modifier.height((24 * scale).dp))
        }
    }
}


// --- Helper Composables ---

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
                checkedTrackColor = Color(0xFF34C759), // iOS Green
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE9E9EA),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

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
        val icon = when {
            isActive -> Icons.Default.CheckCircle
            else -> Icons.Default.Warning
        }
        val iconColor = when {
            isActive -> Color(0xFF34C759)
            isWarning -> Color(0xFFFF9500)
            else -> Color(0xFFFF3B30) // Red for error/inactive
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
            // "Fix" Button
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

@Composable
fun ActionButton(text: String, icon: ImageVector, backgroundColor: Color, fontFamily: FontFamily, scale: Float = 1f, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((56 * scale).dp)
            .shadow(
                elevation = (4 * scale).dp,
                shape = RoundedCornerShape((28 * scale).dp),
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
                tint = backgroundColor, // Icon color matches the theme color
                modifier = Modifier.size((22 * scale).dp)
            )
            Spacer(modifier = Modifier.width((10 * scale).dp))
            Text(
                text = text,
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (17 * scale).coerceIn(15f, 17f).sp,
                color = backgroundColor // Text color matches the theme color
            )
        }
    }
}

private fun checkServiceStatus(context: Context, service: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.name == service.name }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    return enabledListeners?.contains(packageName) == true
}

