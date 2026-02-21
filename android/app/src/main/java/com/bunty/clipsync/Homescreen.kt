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


// Purpose: Implements the homescreen operation for this feature.
// Parameters: See signature for parameters.
// Returns: Unit unless returned explicitly.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
fun Homescreen(
    onRepairClick: () -> Unit = {},
    onResetPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp


    val screenWidth = configuration.screenWidthDp.dp
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight / 915f
    val scale = min(widthScale, heightScale)
    val titleFontSize = (58 * scale).coerceIn(42f, 58f).sp

    val scope = rememberCoroutineScope()

    val macDeviceName = remember { DeviceManager.getPairedMacDeviceName(context) }


    val robotoFontFamily = remember {
        FontFamily(
            Font(R.font.roboto_regular, FontWeight.Normal),
            Font(R.font.roboto_medium, FontWeight.Medium),
            Font(R.font.roboto_bold, FontWeight.Bold),
            Font(R.font.roboto_black, FontWeight.Black)
        )
    }


    var showContent by remember { mutableStateOf(false) }


    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    var isBatteryUnrestricted by remember { mutableStateOf(false) }

    var isSmsPermissionGranted by remember { mutableStateOf(false) }

    var isNotificationListenerEnabled by remember { mutableStateOf(false) }


    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    var showUpdateDialog by remember { mutableStateOf(false) }

    var showResetDialog by remember { mutableStateOf(false) }
    val currentVersion = "1.0.0"


    var syncToMac by remember { mutableStateOf(DeviceManager.isSyncToMacEnabled(context)) }
    var syncFromMac by remember { mutableStateOf(DeviceManager.isSyncFromMacEnabled(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current


    // Purpose: Implements the check permissions operation for this feature.
    // Parameters: No parameters.
    // Returns: Unit unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    fun checkPermissions() {
        isAccessibilityEnabled = checkServiceStatus(context, ClipboardAccessibilityService::class.java)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)

        isSmsPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                                 ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED


        isNotificationListenerEnabled = isNotificationServiceEnabled(context)
    }


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
        checkPermissions()

        scope.launch {
            val info = UpdateChecker.checkForUpdates("v$currentVersion")
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            }
        }
    }


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


    DisposableEffect(Unit) {
        val registration = FirestoreManager.listenToClipboard(context) { text ->

             if (DeviceManager.isSyncFromMacEnabled(context)) {

             }
        }
        onDispose { registration?.remove() }
    }


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


            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { 40 }, animationSpec = tween(400))
            ) {
                 Column(
                    verticalArrangement = Arrangement.spacedBy((28 * scale).dp)
                ) {


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


                    Column {
                        SectionHeader(text = "Preferences", fontFamily = robotoFontFamily, scale = scale)

                        InnerWhiteCard(scale = scale) {
                            Column(modifier = Modifier.padding((20 * scale).dp)) {


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


                                StatusRow(
                                    label = "Mac Clipboard",
                                    isActive = (macDeviceName != "Unknown Device"),
                                    fontFamily = robotoFontFamily,
                                    scale = scale
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = (16 * scale).dp), color = Color(0xFFE5E5EA))


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


                    Column {
                        SectionHeader(text = "Actions", fontFamily = robotoFontFamily, scale = scale)


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


                        ActionButton(
                            text = "Test OTP Detection",
                            icon = Icons.Default.CheckCircle,
                            backgroundColor = Color(0xFF34C759),
                            fontFamily = robotoFontFamily,
                            scale = scale
                        ) {

                            val testOTP = (100000..999999).random().toString()


                            ClipboardGhostActivity.copyToClipboard(context, testOTP)


                            OTPNotificationService.notifyOTPDetected(context, testOTP)


                            Toast.makeText(
                                context,
                                "Test OTP sent to Mac: $testOTP",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Spacer(modifier = Modifier.height((16 * scale).dp))


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

            Spacer(modifier = Modifier.weight(1f))


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


@Composable

// Purpose: Implements the section header operation for this feature.
// Parameters: text, fontFamily, scale.
// Returns: Unit unless returned explicitly.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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

// Purpose: Implements the inner white card operation for this feature.
// Parameters: scale, content.
// Returns: Unit unless returned explicitly.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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

// Purpose: Implements the preference row operation for this feature.
// Parameters: label, checked, onCheckedChange, fontFamily, scale.
// Returns: Unit unless returned explicitly.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


@Composable

// Purpose: Implements the status row operation for this feature.
// Parameters: See signature for parameters.
// Returns: Unit unless returned explicitly.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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

// Purpose: Implements the action button operation for this feature.
// Parameters: text, icon, backgroundColor, fontFamily, scale, onClick.
// Returns: Unit unless returned explicitly.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


// Purpose: Implements the check service status operation for this feature.
// Parameters: context, service.
// Returns: Boolean.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
private fun checkServiceStatus(context: Context, service: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.name == service.name }
}


// Purpose: Evaluates whether is notification service enabled.
// Parameters: context.
// Returns: Boolean.
// Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
private fun isNotificationServiceEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    return enabledListeners?.contains(packageName) == true
}
