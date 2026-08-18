package com.bunty.clipsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay

// ─── Nav bar items ───────────────────────────────────────────────────────────
private enum class NavTab { Home, Settings }

@Composable
fun DashboardScreen(
    showUpdateDialogOnStart: Boolean = false,
    onRepairClick: () -> Unit = {},
    onResetPairing: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val application =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                            as android.app.Application)
                DashboardViewModel(application)
            }
        }
    )
) {
    val state        by viewModel.uiState.collectAsState()
    val context      = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showContent by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(NavTab.Home) }

    // ── Permission checks ────────────────────────────────────────────────────
    fun checkPermissions() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        viewModel.updatePermissionsState(
            isAccessibilityEnabled      = checkServiceStatus(context, ClipboardAccessibilityService::class.java),
            isBatteryUnrestricted       = pm.isIgnoringBatteryOptimizations(context.packageName),
            isNotificationListenerEnabled = isNotificationServiceEnabled(context)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        delay(100)
        showContent = true
        checkPermissions()
    }

    // ── Root layout: transparent so MeshBackground in MainActivity shows through ──
    Scaffold(
        containerColor = Color.Transparent,

            // ── Top bar ──────────────────────────────────────────────────────
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Dashboard",
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Black,
                        fontSize = 34.sp,
                        color = TextPrimary          // WHITE — explicit, not from theme
                    )
                }
            },

            // ── Bottom navigation bar ─────────────────────────────────────
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceGlass)
                            .border(1.dp, SurfaceGlassBorder, RoundedCornerShape(20.dp))
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Home tab
                        NavBarItem(
                            label       = "Home",
                            icon        = if (selectedTab == NavTab.Home) Icons.Filled.Home else Icons.Outlined.Home,
                            selected    = selectedTab == NavTab.Home,
                            onClick     = { selectedTab = NavTab.Home },
                            modifier    = Modifier.weight(1f)
                        )
                        // Settings tab
                        NavBarItem(
                            label       = "Settings",
                            icon        = if (selectedTab == NavTab.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                            selected    = selectedTab == NavTab.Settings,
                            onClick     = { selectedTab = NavTab.Settings },
                            modifier    = Modifier.weight(1f)
                        )
                    }
                }
            }
        ) { paddingValues ->

            AnimatedVisibility(
                visible = showContent,
                enter   = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY  = { 40 },
                    animationSpec   = tween(400)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // 1. Device card
                    DeviceCard(
                        macDeviceName = state.macDeviceName,
                        onRepairClick = onRepairClick
                    )

                    // 2. Connection route card
                    ConnectionRouteCard(
                        routeName        = state.activeRouteName,
                        routeDescription = state.activeRouteDescription
                    )

                    // 3. Sync controls
                    SyncControlsCard(
                        syncToMac            = state.syncToMac,
                        onSyncToMacChange    = viewModel::toggleSyncToMac,
                        syncFromMac          = state.syncFromMac,
                        onSyncFromMacChange  = viewModel::toggleSyncFromMac,
                        autoSyncOTPs         = state.autoSyncOTPs,
                        onAutoSyncOTPsChange = viewModel::toggleAutoSyncOTPs
                    )

                    // 4. Action buttons
                    QuickActionsGrid(
                        onSendFileClick    = { Toast.makeText(context, "Send File (Coming Soon)", Toast.LENGTH_SHORT).show() },
                        onStartHotspotClick = { Toast.makeText(context, "Start Hotspot (Coming Soon)", Toast.LENGTH_SHORT).show() }
                    )

                    // 5. Recent activity
                    RecentActivityList()

                    // Permission warning banner (shown only when something is off)
                    if (!state.isAccessibilityEnabled ||
                        !state.isBatteryUnrestricted  ||
                        !state.isNotificationListenerEnabled
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(StatusWarning.copy(alpha = 0.15f))
                                .border(1.dp, StatusWarning.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector     = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint            = StatusWarning,
                                modifier        = Modifier.size(16.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text       = "Some features are disabled. Check Android Settings.",
                                fontFamily = RobotoFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize   = 13.sp,
                                color      = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

// ── Nav bar item composable ──────────────────────────────────────────────────
@Composable
private fun NavBarItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tintColor   = if (selected) ActionBlue else TextCaption
    val labelColor  = if (selected) ActionBlue else TextCaption

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ActionBlue.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(
                interactionSource = null,
                indication        = null
            ) { onClick() }
            .padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector    = icon,
            contentDescription = label,
            tint           = tintColor,
            modifier       = Modifier.size(22.dp)
        )
        Text(
            text       = label,
            fontFamily = RobotoFontFamily,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize   = 11.sp,
            color      = labelColor
        )
    }
}

// ── Preview ──────────────────────────────────────────────────────────────────
@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardScreenPreview() {
    ClipSyncTheme {
        DashboardScreen()
    }
}

// ── Permission helpers ────────────────────────────────────────────────────────
private fun isNotificationServiceEnabled(context: Context): Boolean {
    val packageName     = context.packageName
    val enabledListeners = android.provider.Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    )
    return enabledListeners?.contains(packageName) == true
}

private fun checkServiceStatus(context: Context, service: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any { it.resolveInfo.serviceInfo.name == service.name }
}
