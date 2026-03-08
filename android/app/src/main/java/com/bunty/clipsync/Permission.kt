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

/**
 * PermissionPage is the onboarding screen that guides the user through granting the four
 * Android permissions ClipSync requires before it can operate reliably in the background.
 *
 * **Screen structure (top → bottom, all entrance-animated):**
 *  1. **Header text** – a bold instruction prompt ("Just allow a few permissions to keep
 *     things smooth") that slides down from 40 px above its resting position.
 *  2. **Permissions card** – a semi-transparent rounded card (purple→blue vertical
 *     gradient at 30 % opacity) housing four independently animated [PermissionItem] rows:
 *       - Notification    (optional)  – alerts when sync pauses or an update arrives.
 *       - Accessibility   (mandatory) – detects clipboard changes via the foreground service.
 *       - Display Over Apps (mandatory) – allows the background sync overlay to appear.
 *       - SMS Access      (optional)  – auto-reads incoming OTP codes for instant sync.
 *  3. **"Finish Setup" button** – a pill-shaped glass button that appears last. It only
 *     navigates forward when **both** mandatory permissions (Accessibility + Display Over
 *     Apps) are confirmed; otherwise it shows a context-aware [Toast] identifying which
 *     required permission is still missing.
 *
 * **Staggered entrance animation** – each UI element has its own `showXxx` boolean flag
 * flipped to `true` with a 100–150 ms inter-step delay inside a single [LaunchedEffect],
 * producing a smooth cascading reveal that draws the user's eye downward through the UI.
 *
 * **Live permission polling** – a second [LaunchedEffect] runs an infinite `while(true)`
 * loop with a 1 000 ms `delay` that re-checks every permission state on each tick. This
 * automatically keeps the toggle switches in sync when the user grants a permission inside
 * Android Settings and returns to the app, without requiring any manual refresh. The loop
 * also detects the accessibility service transitioning from disabled → enabled and fires a
 * confirmation [Toast] exactly once for that transition.
 *
 * **Notification permission** – handled separately because [Manifest.permission.POST_NOTIFICATIONS]
 * became a runtime permission only on API 33 (Android 13). On API 33+ a standard
 * [rememberLauncherForActivityResult] runtime-permission launcher is used; on older APIs
 * the app's system notification settings page is opened directly via an explicit [Intent].
 *
 * **SMS permission** – [Manifest.permission.READ_SMS] and [Manifest.permission.RECEIVE_SMS]
 * are requested together in a single [ActivityResultContracts.RequestMultiplePermissions]
 * call. Both must be granted for `smsPermissionGranted` to become `true`.
 *
 * @param onFinishSetup Invoked when the user taps "Finish Setup" and both mandatory
 *                      permissions are confirmed. The caller is responsible for
 *                      navigating to the main [Homescreen].
 */
@Composable
fun PermissionPage(onFinishSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    // Scale factors derived from the 412×915 dp reference canvas. The unified `scale`
    // uses the smaller axis to preserve proportions on both tall and wide displays.
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight.value / 915f
    val scale = min(widthScale, heightScale)

    val robotoFontFamily = FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal),
        Font(R.font.roboto_medium, FontWeight.Medium),
        Font(R.font.roboto_bold, FontWeight.Bold),
        Font(R.font.roboto_black, FontWeight.Black)
    )

    // ── Live permission state ─────────────────────────────────────────────────────────────
    // These three flags are refreshed every second by the polling LaunchedEffect below.
    var accessibilityGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var smsPermissionGranted by remember { mutableStateOf(false) }

    // ── Staggered entrance gate flags ─────────────────────────────────────────────────────
    // Each flag gates a separate AnimatedVisibility layer. They are flipped to `true`
    // in sequence inside LaunchedEffect to produce the cascading top-down reveal.
    var showHeader by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var showItem1 by remember { mutableStateOf(false) }  // Notification row
    var showItem2 by remember { mutableStateOf(false) }  // Accessibility row
    var showItem3 by remember { mutableStateOf(false) }  // Display Over Apps row
    var showItem4 by remember { mutableStateOf(false) }  // SMS Access row
    var showButton by remember { mutableStateOf(false) }

    // Stagger each element 100–150 ms apart so the eye is guided naturally downward
    // through the header → card → items → button hierarchy.
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

    // Notification permission: only a runtime permission on API 33+; automatically
    // granted on older Android versions and treated as always-on below that threshold.
    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Notification permission is implicitly granted below Android 13.
            }
        )
    }

    // Runtime launcher for POST_NOTIFICATIONS (API 33+). The result callback updates
    // `notificationGranted` immediately so the toggle reflects the user's decision.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            notificationGranted = isGranted
        }
    )

    // Multi-permission launcher that requests READ_SMS and RECEIVE_SMS together in a
    // single system dialog. Both must be granted for `smsPermissionGranted` to be true.
    val smsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            smsPermissionGranted = permissions[Manifest.permission.RECEIVE_SMS] == true &&
                                   permissions[Manifest.permission.READ_SMS] == true
        }
    )

    // Polls every 1 000 ms to detect permission changes made in Android Settings while
    // the app is in the background. This keeps the toggle switches in sync without
    // requiring the user to manually refresh or re-open the app.
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

            // Fire a confirmation toast exactly once when accessibility transitions
            // from disabled to enabled, giving the user positive feedback without
            // them having to look at the screen to confirm the change took effect.
            if (wasEnabled != accessibilityGranted && accessibilityGranted) {
                Toast.makeText(context, " Accessibility Enabled!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        // ── HEADER ────────────────────────────────────────────────────────────────────────
        // Bold instruction text centred at the top of the screen. Slides 40 px down into
        // position while fading in over 400 ms for a clean, unobtrusive entrance.
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

        // ── PERMISSIONS CARD ──────────────────────────────────────────────────────────────
        // A rounded card filled with a vertical purple→blue gradient at 30 % opacity.
        // The four permission rows are positioned absolutely inside it and each animates
        // in from the left independently, creating a left-to-right wave of toggles.
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

                // Row 1 – Notification: optional permission; on API 33+ uses a runtime
                // request, on older APIs opens the app's system notification settings page.
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
                                    // API 33+: POST_NOTIFICATIONS must be requested at runtime.
                                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // Pre-API 33: open the app's notification settings directly,
                                    // since this version does not support runtime permission requests.
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

                // Row 2 – Accessibility: mandatory permission. Opens the system Accessibility
                // Settings page and shows a toast to help the user locate the ClipSync toggle.
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
                                // Send the user to Android's Accessibility Settings so they can
                                // locate and enable the ClipboardAccessibilityService toggle.
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                                Toast.makeText(context, "Enable ClipSync in Accessibility", Toast.LENGTH_LONG).show()
                            }
                        },
                        fontFamily = robotoFontFamily,
                        scale = scale
                     )
                 }

                // Row 3 – Display Over Other Apps: mandatory permission. Opens the
                // MANAGE_OVERLAY_PERMISSION page scoped to ClipSync's own package.
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
                                // Deep-link to the overlay permission settings page for this
                                // specific package so the user lands on the correct toggle directly.
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

                // Row 4 – SMS Access: optional permission for OTP auto-detection.
                // READ_SMS and RECEIVE_SMS are requested together in one system dialog.
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
                                // Request both READ_SMS and RECEIVE_SMS simultaneously so the
                                // user only sees a single permission dialog for SMS access.
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

        // ── FINISH SETUP BUTTON ───────────────────────────────────────────────────────────
        // Scales in with a slight grow-from-80% entrance. Tapping it re-checks the two
        // mandatory permissions at the moment of the tap (not just relying on the polled
        // state) and either proceeds or shows a targeted toast identifying what's missing.
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
                            // Show a specific toast so the user knows exactly which of the
                            // two mandatory permissions is blocking progression.
                            if (!isAccessibilityServiceEnabled(context)) {
                                Toast.makeText(context, "Please enable Accessibility first", Toast.LENGTH_SHORT).show()
                            } else if (!Settings.canDrawOverlays(context)) {
                                Toast.makeText(context, "Please enable Display Over Apps", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            ) {
                // Checkmark icon on the left side of the pill button.
                Icon(
                    painter = painterResource(id = R.drawable.check),
                    contentDescription = "Check",
                    modifier = Modifier
                        .size((30 * scale).dp)
                        .offset(x = (13 * scale).dp, y = (13 * scale).dp),
                    tint = Color.Black
                )

                // "Finish Setup" label positioned to the right of the checkmark icon.
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

/**
 * PermissionItem renders a single, self-contained permission toggle row inside the
 * permissions card on [PermissionPage].
 *
 * **Layout** – the row uses a horizontal [Box] + [Row] arrangement:
 *  - An [Icon] on the far left visually identifies the permission category at a glance
 *    (e.g. a notification bell, an accessibility person, a shield for overlay).
 *  - A [Column] in the centre carries two [Text] nodes: a bold medium-weight title (e.g.
 *    "Accessibility") on the first line, and a lighter normal-weight one-line description
 *    below explaining *why* the permission is needed. The column takes all remaining
 *    horizontal space via `Modifier.weight(1f)`.
 *  - A [Switch] on the far right reflects the current granted state. Its track turns
 *    iOS-style blue (`#007AFF`) when checked and grey when unchecked. The thumb is white
 *    in both states to stay consistent with iOS switch aesthetics.
 *
 * **Interaction** – tapping the switch when `isChecked` is `false` invokes [onToggle],
 * which the caller uses to launch the appropriate runtime permission request or to open
 * the relevant Android Settings deep-link. When `isChecked` is already `true` the toggle
 * is effectively a no-op (no action is needed to un-grant a permission from here).
 *
 * **Sizing** – the row is sized at 350 × 80 dp (design-canvas units) and all internal
 * spacers, icon sizes, and font sizes are multiplied by [scale] so the row remains
 * visually proportional on every supported device density.
 *
 * @param iconRes     Drawable resource ID for the permission category icon.
 * @param title       Short, human-readable permission name displayed in bold (e.g. "Accessibility").
 * @param description Brief one-line rationale explaining why this permission is needed.
 * @param isChecked   `true` if the permission is currently granted; drives the switch state.
 * @param onToggle    Invoked with the new boolean value when the user changes the switch.
 *                    The caller is responsible for the actual permission request logic.
 * @param fontFamily  The Roboto [FontFamily] instance shared by the parent composable.
 * @param isStatic    Reserved parameter; if `true` the item is intended to be non-interactive.
 *                    Not currently wired to any behaviour but present for future use.
 * @param scale       Unified density-independent scale factor for responsive sizing,
 *                    derived from the device screen dimensions vs. the design canvas.
 */
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
            .padding(horizontal = (12 * scale).dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            // Category icon: visually identifies the permission type at a glance before
            // the user reads the title (e.g. bell → notifications, person → accessibility).
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                modifier = Modifier.size((30 * scale).dp),
                tint = Color.Black
            )

            Spacer(modifier = Modifier.width((12 * scale).dp))

            // Text column: bold title on the first line establishes the permission name;
            // the lighter description below gives just enough context without overwhelming.
            Column(
                modifier = Modifier
                    .weight(1f)
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

            // Toggle switch: blue track (#007AFF) when on mirrors the iOS convention
            // familiar to the target audience; white thumb in both states for consistency.
            Switch(
                checked = isChecked,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .scale(scale),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF007AFF),   // iOS-style blue track when permission is granted
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.Gray
                )
            )
        }
    }
}

/**
 * Checks whether ClipSync's [ClipboardAccessibilityService] is currently active in
 * Android's Accessibility Settings.
 *
 * Android stores the list of enabled accessibility services as a colon-separated string
 * in [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]. The format of each entry is not
 * guaranteed to be consistent across Android versions, OEM skins, or launchers — it may
 * appear as the fully-qualified component name, a shortened form starting with `/`, or
 * just the bare class name. To guard against all of these variants, the function tries
 * four candidate name formats in order and returns `true` as soon as any match is found.
 *
 * The entire lookup is wrapped in a `try/catch` block so that a [SecurityException] or
 * any unexpected [Settings.SettingNotFoundException] from the [Settings.Secure] API does
 * not crash the permission-polling loop in [PermissionPage]; it simply returns `false`.
 *
 * @param context The application or activity [Context] used to resolve the content resolver.
 * @return `true` if any of the known name variants for [ClipboardAccessibilityService]
 *         appears in the system's enabled-accessibility-services string; `false` otherwise.
 */
fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val possibleNames = listOf(
            "com.bunty.clipsync/com.bunty.clipsync.ClipboardAccessibilityService",
            "com.bunty.clipsync/.ClipboardAccessibilityService",
            "ClipboardAccessibilityService",
            "ClipSync"
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
