package com.bunty.clipsync

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import kotlin.math.min
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import android.widget.Toast
import android.util.Log
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun PermissionPage(onFinishSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    // Reference dimensions: 412x915
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight.value / 915f
    val scale = min(widthScale, heightScale)

    val robotoFontFamily = FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal),
        Font(R.font.roboto_medium, FontWeight.Medium),
        Font(R.font.roboto_bold, FontWeight.Bold),
        Font(R.font.roboto_black, FontWeight.Black)
    )

    var accessibilityGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var smsPermissionGranted by remember { mutableStateOf(false) }

    // --- Animation Sequence (Staggered Entrance) ---
    var showHeader by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var showItem1 by remember { mutableStateOf(false) }
    var showItem2 by remember { mutableStateOf(false) }
    var showItem3 by remember { mutableStateOf(false) }
    var showItem4 by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    // Trigger Animations Sequence
    LaunchedEffect(Unit) {
        delay(100)
        showHeader = true
        delay(150)
        showCard = true
        delay(100)
        showItem1 = true
        delay(100)
        showItem2 = true
        delay(100)
        showItem3 = true
        delay(100)
        showItem4 = true
        delay(150)
        showButton = true
    }

    // Launcher for POST_NOTIFICATIONS
    var notificationGranted by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Explicit permission not needed below Android 13
            }
        ) 
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            notificationGranted = isGranted
        }
    )

    // Launcher for SMS Permissions
    val smsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            smsPermissionGranted = permissions[Manifest.permission.RECEIVE_SMS] == true &&
                                   permissions[Manifest.permission.READ_SMS] == true
        }
    )

    // Check on first load & Periodic check
    LaunchedEffect(Unit) {
        accessibilityGranted = isAccessibilityServiceEnabled(context)
        smsPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                               ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        while (true) {
            delay(1000)
            val wasEnabled = accessibilityGranted
            accessibilityGranted = isAccessibilityServiceEnabled(context)
            overlayGranted = Settings.canDrawOverlays(context)
            smsPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                                   ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= 33) {
                notificationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }

            if (wasEnabled != accessibilityGranted && accessibilityGranted) {
                Toast.makeText(context, " Accessibility Enabled!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Main Container
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top Header
        AnimatedVisibility(
            visible = showHeader,
            enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { -40 }, animationSpec = tween(400)),
            modifier = Modifier
                .width((350 * scale).dp)
                .align(Alignment.TopCenter)
                .offset(y = (100 * heightScale).dp)
        ) {
            Text(
                text = "Just allow a few permissions to keep things smooth",
                fontFamily = robotoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (32 * scale).coerceIn(24f, 32f).sp,
                letterSpacing = (-0.02).em,
                lineHeight = (38 * scale).coerceIn(28f, 38f).sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        // Main Permission Card
        AnimatedVisibility(
            visible = showCard,
            enter = fadeIn(tween(400)) + slideInVertically(initialOffsetY = { 40 }, animationSpec = tween(400)),
            modifier = Modifier.offset(x = (10 * widthScale).dp, y = (243 * heightScale).dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = (390 * scale).dp, height = (467 * scale).dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF907ADD).copy(alpha = 0.3f),
                                Color(0xFF4F87C3).copy(alpha = 0.3f)
                            )
                        ),
                        shape = RoundedCornerShape((32 * scale).dp)
                    )
            ) {
                 // --- Item 1: Notification ---
                 AnimatedVisibility(
                    visible = showItem1,
                    enter = fadeIn(tween(300)) + slideInHorizontally(initialOffsetX = { -40 }, animationSpec = tween(300)),
                    modifier = Modifier.offset(x = (20 * scale).dp, y = (33 * scale).dp)
                 ) {
                     PermissionItem(
                        iconRes = R.drawable.notifications,
                        title = "Notification",
                        description = "To alert you if sync pauses or updates arrives",
                        isChecked = notificationGranted,
                        onToggle = { 
                            if (!notificationGranted) {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // Open Settings as fallback
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        },
                        fontFamily = robotoFontFamily,
                         scale = scale
                     )
                 }

                 // --- Item 2: Accessibility ---
                 AnimatedVisibility(
                    visible = showItem2,
                    enter = fadeIn(tween(300)) + slideInHorizontally(initialOffsetX = { -40 }, animationSpec = tween(300)),
                    modifier = Modifier.offset(x = (20 * scale).dp, y = (139 * scale).dp)
                 ) {
                     PermissionItem(
                        iconRes = R.drawable.accessibility,
                        title = "Accessibility",
                        description = "To detect when you copy something and sync is instantly",
                        isChecked = accessibilityGranted,
                        onToggle = {
                            if (!accessibilityGranted) {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                                Toast.makeText(context, "Enable ClipSync in Accessibility", Toast.LENGTH_LONG).show()
                            }
                        },
                        fontFamily = robotoFontFamily,
                        scale = scale
                     )
                 }

                 // --- Item 3: Display Over Apps ---
                 AnimatedVisibility(
                    visible = showItem3,
                    enter = fadeIn(tween(300)) + slideInHorizontally(initialOffsetX = { -40 }, animationSpec = tween(300)),
                    modifier = Modifier.offset(x = (20 * scale).dp, y = (250 * scale).dp)
                 ) {
                     PermissionItem(
                        iconRes = R.drawable.batteryshield,
                        title = "Display Over Apps",
                        description = "Required for background clipboard sync.",
                        isChecked = overlayGranted,
                        onToggle = {
                            if (!overlayGranted) {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                intent.data = android.net.Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                                Toast.makeText(context, "Enable 'Allow display over other apps'", Toast.LENGTH_LONG).show()
                            }
                        },
                        fontFamily = robotoFontFamily,
                        scale = scale
                     )
                 }

                 // --- Item 4: SMS Access ---
                 AnimatedVisibility(
                    visible = showItem4,
                    enter = fadeIn(tween(300)) + slideInHorizontally(initialOffsetX = { -40 }, animationSpec = tween(300)),
                    modifier = Modifier.offset(x = (20 * scale).dp, y = (360 * scale).dp)
                 ) {
                     PermissionItem(
                        iconRes = R.drawable.notiaccess,
                        title = "SMS Access",
                        description = "Auto-detect OTP codes for instant sync.",
                        isChecked = smsPermissionGranted,
                        onToggle = {
                            if (!smsPermissionGranted) {
                                smsLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_SMS
                                    )
                                )
                            }
                        },
                        fontFamily = robotoFontFamily,
                        scale = scale
                     )
                 }
            }
        }

        // Finish Button
        AnimatedVisibility(
            visible = showButton,
            enter = fadeIn(tween(400)) + scaleIn(initialScale = 0.8f, animationSpec = tween(400)),
            modifier = Modifier.offset(x = (113 * widthScale).dp, y = (761 * heightScale).dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = (195 * scale).dp, height = (59 * scale).dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape((32 * scale).dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White,
                        shape = RoundedCornerShape((32 * scale).dp)
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context)) {
                            onFinishSetup()
                        } else {
                            if (!isAccessibilityServiceEnabled(context)) {
                                Toast.makeText(context, "Please enable Accessibility first", Toast.LENGTH_SHORT).show()
                            } else if (!Settings.canDrawOverlays(context)) {
                                Toast.makeText(context, "Please enable Display Over Apps", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.check),
                    contentDescription = "Check",
                    modifier = Modifier
                        .size((30 * scale).dp)
                        .offset(x = (13 * scale).dp, y = (13 * scale).dp),
                    tint = Color.Black
                )
    
                Text(
                    text = "Finish Setup",
                    fontFamily = robotoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = (24 * scale).coerceIn(20f, 24f).sp,
                    letterSpacing = (-0.03).em,
                    color = Color.Black,
                    modifier = Modifier
                        .size(width = (141 * scale).dp, height = (28 * scale).dp)
                        .offset(x = (46 * scale).dp, y = (12 * scale).dp)
                )
            }
        }
    }
}

// --- Helper Composable to avoid duplication ---
@Composable
fun PermissionItem(
    iconRes: Int,
    title: String,
    description: String,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit,
    fontFamily: FontFamily,
    isStatic: Boolean = false,
    scale: Float = 1f
) {
    Box(
        modifier = Modifier
            .size(width = (350 * scale).dp, height = (80 * scale).dp)
            .background(
                color = Color.White.copy(alpha = 0.4f),
                shape = RoundedCornerShape((32 * scale).dp)
            )
            .padding(horizontal = (12 * scale).dp) // Add padding for Row content
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                modifier = Modifier.size((30 * scale).dp),
                tint = Color.Black
            )

            Spacer(modifier = Modifier.width((12 * scale).dp))

            // Text Column
            Column(
                modifier = Modifier
                    .weight(1f) // Fill available space between Icon and Switch
                    .padding(end = (8 * scale).dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = (18 * scale).coerceIn(14f, 18f).sp,
                    letterSpacing = (-0.03).em,
                    color = Color.Black
                )

                Text(
                    text = description,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = (14 * scale).coerceIn(10f, 14f).sp,
                    lineHeight = (18 * scale).coerceIn(14f, 18f).sp,
                    letterSpacing = (-0.03).em,
                    color = Color(0xFF555050)
                )
            }

            // Switch
            Switch(
                checked = isChecked,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .scale(scale),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF007AFF),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.Gray
                )
            )
        }
    }
}

// Helper function to check accessibility - CHECKS ALL POSSIBLE FORMATS
fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        Log.d("AccessibilityCheck", "Enabled Services: $enabledServices")

        // Check if ANY of these patterns match
        val possibleNames = listOf(
            "com.bunty.clipsync/com.bunty.clipsync.ClipboardAccessibilityService",
            "com.bunty.clipsync/.ClipboardAccessibilityService",
            "ClipboardAccessibilityService",
            "ClipSync" // Some ROMs just show the label
        )

        for (name in possibleNames) {
            if (enabledServices.contains(name, ignoreCase = true)) {
                return true
            }
        }
        return false

    } catch (e: Exception) {
        Log.e("AccessibilityCheck", "ERROR: ${e.message}")
        return false
    }
}


@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun PermissionPreview() {
    MaterialTheme {
        PermissionPage()
    }
}

