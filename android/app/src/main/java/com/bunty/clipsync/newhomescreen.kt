package com.bunty.clipsync

import android.widget.Toast
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import com.bunty.clipsync.UrlAllowlistManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bunty.clipsync.db.HistoryRepository
import android.text.format.DateUtils
import android.content.Intent
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.runtime.saveable.rememberSaveable

// ============================================================================
// DATA MODELS
// ============================================================================

data class SyncSettingItem(
    val title: String,
    val subtitle: String,
    val iconResId: Int,
    val isEnabled: Boolean
)

data class QuickActionItem(
    val label: String,
    val iconResId: Int
)

data class RecentSyncItem(
    val content: String,
    val direction: String, // "From Mac" or "Sent to Mac"
    val timeAgo: String,
    val iconResId: Int,
    val isSuccess: Boolean = true,
    val type: String = "All" // For filtering (e.g. "Text", "OTP", "Links", "Screenshots")
)

// ============================================================================
// MAIN SCREEN
// ============================================================================

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewHomeScreen(
    modifier: Modifier = Modifier,
    onRepairClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by rememberSaveable { mutableStateOf<UpdateNotificationManager.UpdateInfo?>(null) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    // Floating banner: true if an APK is already downloaded and waiting to be installed
    var showInstallBanner by rememberSaveable { mutableStateOf(false) }
    var installBannerVersion by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // Check if a previously downloaded APK is waiting to be installed
        if (AppUpdateInstaller.hasReadyApk(context)) {
            installBannerVersion = AppUpdateInstaller.getReadyApkVersion(context) ?: ""
            showInstallBanner = true
        }

        val release = GithubUpdateChecker.checkForUpdate(context)
        if (release != null) {
            UpdateNotificationManager.savePendingUpdate(context, release.version, release.downloadUrl, release.releaseNotes)
        }
        val pending = UpdateNotificationManager.getPendingUpdate(context)
        if (pending != null) {
            val currentVersion = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0" } catch (e: Exception) { "0.0.0" }
            if (GithubUpdateChecker.isVersionNewer(currentVersion, pending.version)) {
                updateInfo = pending
                showUpdateDialog = true
            } else {
                UpdateNotificationManager.clearPendingUpdate(context)
            }
        }
    }

    val tabs = remember { NavigationTab.entries }
    // FIX: Track both current and previous page so we can determine slide direction.
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var previousPage by rememberSaveable { mutableIntStateOf(0) }
    val view = LocalView.current

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent
        ) { innerPadding ->

            Box(modifier = Modifier.fillMaxSize()) {
                // ── THE FLOATING TOOLBAR ──
                LiquidGlassNavBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(1f),
                    items = tabs.mapIndexed { index, tab ->
                        ToolbarItem(
                            lottieResId = tab.lottieResId,
                            label = tab.title,
                            onClick = {
                                HapticUtil.performUIHaptic(view)
                                previousPage = currentPage
                                currentPage = index
                            },
                            hasBadge = false
                        )
                    },
                    selectedIndex = currentPage,
                    onItemSelected = { index ->
                        previousPage = currentPage
                        currentPage = index
                    }
                )

                // ── PAGE CONTENT WITH FIXED ANIMATED TRANSITIONS ──
                // FIX 1: Use horizontal slide for tab navigation (natural left/right feel).
                // FIX 2: Direction is determined by tab index — going right slides left-to-right,
                //        going left slides right-to-left. This eliminates the jitter caused by
                //        both screens sliding in the same vertical direction simultaneously.
                // FIX 3: Add Modifier.clip(true) via clipToBounds on the AnimatedContent
                //        container so content does not overflow its bounds during the transition.
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        val goingForward = targetState > initialState
                        val slideDistance = 300
                        if (goingForward) {
                            (fadeIn(tween(350)) + slideInHorizontally(tween(350)) { slideDistance })
                                .togetherWith(
                                    fadeOut(tween(350)) + slideOutHorizontally(tween(350)) { -slideDistance }
                                )
                        } else {
                            (fadeIn(tween(350)) + slideInHorizontally(tween(350)) { -slideDistance })
                                .togetherWith(
                                    fadeOut(tween(350)) + slideOutHorizontally(tween(350)) { slideDistance }
                                )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        // FIX 4: Clip the container so neither the entering nor the exiting
                        //        screen overflows the bounds of this Box during the transition.
                        .then(Modifier.wrapContentSize(unbounded = false)),
                    label = "Tab Transition"
                ) { targetPage ->
                    val statusBarHeight = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding()

                    val contentPadding = PaddingValues(
                        top = statusBarHeight,
                        bottom = 150.dp,
                        start = 16.dp,
                        end = 16.dp
                    )

                    when (tabs[targetPage]) {
                        NavigationTab.HOME -> {
                            HomeContent(
                                contentPadding = contentPadding,
                                onRepairClick = onRepairClick
                            )
                        }
                        NavigationTab.HISTORY -> {
                            HistoryScreen(contentPadding = contentPadding)
                        }
                    }
                }

                // Top Action Buttons (Figma design) - Drawn on top of the AnimatedContent
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Check for Updates Button
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.6f))
                            .clickable {
                                scope.launch {
                                    val release = GithubUpdateChecker.checkForUpdate(context)
                                    if (release != null) {
                                        UpdateNotificationManager.savePendingUpdate(context, release.version, release.downloadUrl, release.releaseNotes)
                                        val pending = UpdateNotificationManager.getPendingUpdate(context)
                                        if (pending != null) {
                                            updateInfo = pending
                                            showUpdateDialog = true
                                        }
                                    } else {
                                        Toast.makeText(context, "App is up to date!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                        painter = painterResource(id = R.drawable.ic_update),
                        contentDescription = "Check for Updates",
                        tint = Color(0xFF1A1A2E),
                        modifier = Modifier.size(24.dp)
                    )
                    }

                    // Buy me a coffee Button
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.6f))
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/clipsync")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.bmc_logo),
                            contentDescription = "Buy me a coffee",
                            modifier = Modifier.size(27.dp, 39.dp)
                        )
                    }
                }
            } // End of inner Box
        } // End of Scaffold
    } // End of outer Box

    // ─────────────────────────────────────────────────────────────────────────
    // Floating "Install ready" banner — appears when an APK is already downloaded
    // ─────────────────────────────────────────────────────────────────────────
    if (showInstallBanner) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showInstallBanner,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
                exit  = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it }
            ) {
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 110.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A2E)
                    ),
                    elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF4ADE80).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_update),
                                contentDescription = null,
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Update ready to install",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            if (installBannerVersion.isNotBlank()) {
                                Text(
                                    text = "ClipSync $installBannerVersion downloaded",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Dismiss
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showInstallBanner = false }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Later", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                            }
                            // Install
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF4ADE80))
                                    .clickable {
                                        AppUpdateInstaller.installReadyApk(context)
                                        AppUpdateInstaller.clearReadyApk(context)
                                        showInstallBanner = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Install", color = Color(0xFF0A0A1A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Update available dialog — M3 Expressive style with scrollable release notes
    // ─────────────────────────────────────────────────────────────────────────
    if (showUpdateDialog && updateInfo != null) {
        val scope = rememberCoroutineScope()
        UpdateAvailableDialog(
            updateInfo = updateInfo!!,
            currentVersion = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0" } catch (e: Exception) { "1.0.0" },
            onDismiss = { showUpdateDialog = false },
            onDontRemind = {
                showUpdateDialog = false
                UpdateNotificationManager.clearPendingUpdate(context)
            },
            onInstall = { AppUpdateInstaller.downloadAndInstall(context, updateInfo!!.downloadUrl, updateInfo!!.version, scope) },
            onCancelDownload = { AppUpdateInstaller.cancelDownload() }
        )
    }
}

// M3 Expressive — Update Dialog matching ClipSync's blue theme
private val ClipSyncAccent = Color(0xFF0A84FF)
private val ClipSyncAccentContainer = Color(0xFF0A84FF).copy(alpha = 0.16f)
private val ClipSyncSurface = Color(0xFF1A1B23)          // dialog background
private val ClipSyncSurfaceElevated = Color(0xFF25262F)  // nested cards
private val ClipSyncOnSurface = Color.White
private val ClipSyncOnSurfaceMuted = Color.White.copy(alpha = 0.6f)

@Composable
fun UpdateAvailableDialog(
    updateInfo: UpdateNotificationManager.UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDontRemind: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val downloadState by AppUpdateInstaller.downloadState.collectAsStateWithLifecycle()
    val isDownloading = downloadState is AppUpdateInstaller.DownloadState.Downloading
    val progress = (downloadState as? AppUpdateInstaller.DownloadState.Downloading)?.progress ?: 0f
    val downloadedMb = (downloadState as? AppUpdateInstaller.DownloadState.Downloading)?.downloadedMb ?: 0f
    val totalMb = (downloadState as? AppUpdateInstaller.DownloadState.Downloading)?.totalMb ?: 0f
    val context = LocalContext.current
    
    LaunchedEffect(downloadState) {
        if (downloadState is AppUpdateInstaller.DownloadState.ReadyToInstall) {
            AppUpdateInstaller.installReadyApk(context)
            onDismiss()
            AppUpdateInstaller.resetState()
        }
        if (downloadState is AppUpdateInstaller.DownloadState.Failed) {
            android.widget.Toast.makeText(context, (downloadState as AppUpdateInstaller.DownloadState.Failed).message, android.widget.Toast.LENGTH_SHORT).show()
            AppUpdateInstaller.resetState()
        }
    }

    var animate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { animate = true }
    val scale by animateFloatAsState(
        targetValue = if (animate) 1f else 0.85f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "scale"
    )

    Dialog(onDismissRequest = { if (!isDownloading) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .graphicsLayer { scaleX = scale; scaleY = scale },
            shape = RoundedCornerShape(32.dp),
            color = ClipSyncSurface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {

                Text(
                    text = if (isDownloading) "DOWNLOADING" else "UPDATE",
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = ClipSyncOnSurfaceMuted,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(ClipSyncAccentContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_update),
                            contentDescription = null,
                            tint = ClipSyncAccent,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        Text("Update Available", fontFamily = RobotoFontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = ClipSyncOnSurface)
                        Text("A new version is ready", fontFamily = RobotoFontFamily, fontSize = 15.sp, color = ClipSyncOnSurfaceMuted)
                    }
                }

                Spacer(Modifier.height(20.dp))

                Surface(shape = RoundedCornerShape(20.dp), color = ClipSyncSurfaceElevated) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VersionBadgeColumn(label = "Current", version = currentVersion, filled = false)
                        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = ClipSyncAccent, modifier = Modifier.size(20.dp))
                        VersionBadgeColumn(label = "New", version = updateInfo.version, filled = true)
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (isDownloading) {
                    Surface(shape = RoundedCornerShape(20.dp), color = ClipSyncSurfaceElevated) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = ClipSyncAccent,
                                    strokeWidth = 2.5.dp
                                )
                                Text("Downloading...", fontFamily = RobotoFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ClipSyncOnSurface)
                            }
                            Spacer(Modifier.height(14.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = ClipSyncAccent,
                                trackColor = Color.White.copy(alpha = 0.1f)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${downloadedMb.format(1)} / ${totalMb.format(1)} MB", fontFamily = RobotoFontFamily, fontSize = 13.sp, color = ClipSyncOnSurfaceMuted)
                                Text("${(progress * 100).toInt()}%", fontFamily = RobotoFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ClipSyncAccent)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ClipSyncOnSurface)
                    ) { Text("Cancel", fontWeight = FontWeight.Bold) }
                } else {
                    Text("What's New", fontFamily = RobotoFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ClipSyncOnSurface, modifier = Modifier.padding(bottom = 10.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = ClipSyncSurfaceElevated) {
                        Column(modifier = Modifier.padding(18.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                            Text("Highlights", fontFamily = RobotoFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ClipSyncOnSurface)
                            Spacer(Modifier.height(8.dp))
                            updateInfo.releaseNotes.lines().filter { it.isNotBlank() }.forEach { line ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text("•  ", color = ClipSyncOnSurfaceMuted, fontSize = 14.sp)
                                    Text(line.trim().removePrefix("-").removePrefix("*").trim(), fontFamily = RobotoFontFamily, fontSize = 14.sp, color = ClipSyncOnSurfaceMuted, lineHeight = 20.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClipSyncAccent, contentColor = Color.White)
                    ) {
                        Icon(painterResource(R.drawable.ic_update), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download & Install", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDontRemind) {
                            Text("Don't remind me", color = ClipSyncOnSurfaceMuted, fontSize = 14.sp)
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ClipSyncOnSurface)
                        ) { Text("Later", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionBadgeColumn(label: String, version: String, filled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontFamily = RobotoFontFamily, fontSize = 13.sp, color = ClipSyncOnSurfaceMuted)
        Surface(
            shape = RoundedCornerShape(50),
            color = if (filled) ClipSyncAccent else Color.White.copy(alpha = 0.08f)
        ) {
            Text(
                text = version,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (filled) Color.White else ClipSyncOnSurface
            )
        }
    }
}

private fun Float.format(digits: Int) = "%.${digits}f".format(this)

@Composable
private fun HomeContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onRepairClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val macDeviceName = remember { DeviceManager.getPairedMacDeviceName(context) }
    
    val localSyncState by LocalSyncManager.state.collectAsStateWithLifecycle()
    val isReceiving by AndroidTcpReceiver.isReceiving.collectAsStateWithLifecycle()
    val receiveProgress by AndroidTcpReceiver.receiveProgress.collectAsStateWithLifecycle()
    val receiveSpeedString by AndroidTcpReceiver.receiveSpeedString.collectAsStateWithLifecycle()
    
    val wifiManager = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager }
    val connectivityManager = remember { context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager }
    
    var activeNetworkName by rememberSaveable { mutableStateOf("Checking...") }
    LaunchedEffect(Unit) {
        while(true) {
            val isWifiConnected = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = connectivityManager?.activeNetwork
                val caps = connectivityManager?.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                connectivityManager?.activeNetworkInfo?.let { it.type == android.net.ConnectivityManager.TYPE_WIFI && it.isConnected } == true
            }

            if (!isWifiConnected) {
                activeNetworkName = "Disconnected"
            } else {
                val localIp = try {
                    java.net.NetworkInterface.getNetworkInterfaces().toList()
                        .flatMap { it.inetAddresses.toList() }
                        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        ?.hostAddress
                } catch (e: Exception) { null }
                val macIp = DeviceManager.getMacLocalIp(context)
                
                activeNetworkName = when {
                    macIp == null -> "Wi-Fi connected"
                    localIp?.substringBeforeLast(".") == macIp.substringBeforeLast(".") -> "Same network as Mac"
                    else -> "Different network than Mac"
                }
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Sending file to Mac...", Toast.LENGTH_SHORT).show()
            LocalSyncManager.onClipboardContent(context, "", "file", uri = uri)
        }
    }

    // FIX 5: Remove the 100ms delay + showContent state that was causing a second
    //        layout pass mid-transition (a key source of jitter). Instead use
    //        animateFloatAsState with a direct true target — the fade-in from the
    //        AnimatedContent transition already handles the entry feel. The staggered
    //        AnimatedVisibility blocks below now also start at 'true' so they don't
    //        re-trigger during the tab animation.
    var showContent by rememberSaveable { mutableStateOf(true) }

    var syncToMac by rememberSaveable { mutableStateOf(DeviceManager.isSyncToMacEnabled(context)) }
    var syncFromMac by rememberSaveable { mutableStateOf(DeviceManager.isSyncFromMacEnabled(context)) }
    var otpSync by rememberSaveable { mutableStateOf(DeviceManager.isAutoSyncOTPsEnabled(context)) }
    var screenshotSync by rememberSaveable { mutableStateOf(DeviceManager.isAutoSyncScreenshotsEnabled(context)) }

    // Map history to UI items
    val historyRepo = remember { HistoryRepository.getInstance(context) }
    val allHistory by historyRepo.allHistory.collectAsStateWithLifecycle()

    val recentSyncs = remember(allHistory) {
        allHistory.take(2).map { entity ->
            val iconResId = when (entity.type) {
                "OTP" -> R.raw.home_icon_otp
                "Links" -> R.raw.history_link
                "Screenshots" -> R.raw.home_icon_clipboard
                else -> R.raw.history_document
            }
            val timeAgo = DateUtils.getRelativeTimeSpanString(entity.timestamp).toString()
            RecentSyncItem(
                content = entity.content,
                direction = entity.direction,
                timeAgo = timeAgo,
                iconResId = iconResId,
                isSuccess = entity.isSuccess,
                type = entity.type
            )
        }
    }

    Box(
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = 72.dp + contentPadding.calculateTopPadding(),
                bottom = 150.dp
            )
        ) {
            // ── Title ──
            item {
                Text(
                    text = "Home",
                    textAlign = TextAlign.Start,
                    fontSize = 48.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.03).em,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.27f),
                            offset = Offset(0f, 4f),
                            blurRadius = 42.3f
                        )
                    ),
                    modifier = Modifier
                        .alpha(1f)
                        .padding(start = 4.dp, bottom = 16.dp)
                )
            }

            // ── Devices Section ──
            item {
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(400)) + slideInHorizontally(
                        initialOffsetX = { 40 },
                        animationSpec = tween(400)
                    )
                ) {
                    Column {
                        HomeSectionHeader(text = "Devices")

                        GlassCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Computer,
                                        contentDescription = "Laptop",
                                        tint = Color(0xFF007AFF),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = "Connected to",
                                            fontFamily = RobotoFontFamily,
                                            fontSize = 13.sp,
                                            color = Color(0xFF3C3C43).copy(alpha = 0.6f)
                                        )
                                        Text(
                                            text = macDeviceName,
                                            fontFamily = RobotoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp,
                                            color = Color.Black,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xFF007AFF))
                                        .clickable(
                                            interactionSource = null,
                                            indication = null
                                        ) { onRepairClick() }
                                        .padding(horizontal = 18.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "Re-pair",
                                        fontFamily = RobotoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Status Section ──
            item {
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(500)) + slideInHorizontally(
                        initialOffsetX = { 50 },
                        animationSpec = tween(500)
                    )
                ) {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        HomeSectionHeader(text = "Status")
                        ConnectionRouteCard(
                            syncState = localSyncState,
                            activeNetworkName = activeNetworkName,
                            isReceiving = isReceiving,
                            receiveProgress = receiveProgress,
                            receiveSpeedString = receiveSpeedString
                        )
                    }
                }
            }

            // ── Sync Settings Section ──
            item {
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(600)) + slideInHorizontally(
                        initialOffsetX = { 60 },
                        animationSpec = tween(600)
                    )
                ) {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        HomeSectionHeader(text = "Sync Settings")

                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                SyncToggleCard(
                                    title = "Sync to Mac",
                                    subtitle = "Send copied items",
                                    iconResId = R.raw.synctomac,
                                    isChecked = syncToMac,
                                    onCheckedChange = {
                                        syncToMac = it
                                        DeviceManager.setSyncToMacEnabled(context, it)
                                        // Send broadcast/intent to service to pause/resume interception
                                        val intent = Intent(context, ClipboardAccessibilityService::class.java).apply {
                                            action = if (it) "RESUME_SYNC" else "PAUSE_SYNC"
                                        }
                                        context.startService(intent)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                SyncToggleCard(
                                    title = "Sync from Mac",
                                    subtitle = "Receive copied items",
                                    iconResId = R.raw.syncfrommac,
                                    isChecked = syncFromMac,
                                    onCheckedChange = {
                                        syncFromMac = it
                                        DeviceManager.setSyncFromMacEnabled(context, it)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                SyncToggleCard(
                                    title = "OTP Sync",
                                    subtitle = "Auto-forward codes",
                                    iconResId = R.raw.home_icon_otp,
                                    isChecked = otpSync,
                                    onCheckedChange = { 
                                        otpSync = it 
                                        DeviceManager.setAutoSyncOTPsEnabled(context, it)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                SyncToggleCard(
                                    title = "Captures",
                                    subtitle = "Sync copied images to the Mac",
                                    iconResId = R.raw.sendscreenshot,
                                    isChecked = screenshotSync,
                                    onCheckedChange = { 
                                        screenshotSync = it 
                                        DeviceManager.setAutoSyncScreenshotsEnabled(context, it)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // ── Quick Actions Section ──
            item {
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(700)) + slideInHorizontally(
                        initialOffsetX = { 70 },
                        animationSpec = tween(700)
                    )
                ) {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        QuickActionsCard(
                            onSendTest = {
                                LocalSyncManager.onClipboardContent(context, "Hello from ClipSync!", "text")
                                Toast.makeText(context, "Sent to Mac via Local Network!", Toast.LENGTH_SHORT).show()
                            },
                            onSendFile = {
                                filePickerLauncher.launch("*/*")
                            },
                            onHotspot = {
                                Toast.makeText(context, "coming soon", Toast.LENGTH_SHORT).show()
                            },
                            onRepair = { onRepairClick() }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

// ============================================================================
// SECTION HEADER
// ============================================================================

@Composable
private fun HomeSectionHeader(text: String) {
    Text(
        text = text,
        fontFamily = RobotoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        color = Color(0xFF4D5269),
        letterSpacing = (-0.03).em,
        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
    )
}

// ============================================================================
// GLASS CARD (frosted white container)
// ============================================================================

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.03f)
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.8f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.4f),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        content()
    }
}

// ============================================================================
// CONNECTION ROUTE CARD (Status section)
// ============================================================================

@Composable
private fun ConnectionRouteCard(
    syncState: LocalSyncManager.SyncState,
    activeNetworkName: String,
    isReceiving: Boolean = false,
    receiveProgress: Float = 0f,
    receiveSpeedString: String = ""
) {
    val isSending = syncState is LocalSyncManager.SyncState.Streaming
    val isStreaming = isSending || isReceiving
    val progress = if (isSending) (syncState as LocalSyncManager.SyncState.Streaming).progress else if (isReceiving) receiveProgress else 0f
    val speedStr = if (isSending) (syncState as LocalSyncManager.SyncState.Streaming).speedString else if (isReceiving) (if (receiveSpeedString.isNotEmpty()) receiveSpeedString else "Calculating...") else "0 MB/s"

    GlassCard {
        Column(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ACTIVE NETWORK",
                        fontFamily = RobotoFontFamily,
                        fontSize = 10.sp,
                        color = Color(0xFF444654).copy(alpha = 0.7f),
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HomeSvgIcon(
                            rawResId = R.raw.wifi,
                            modifier = Modifier.size(width = 18.dp, height = 10.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = activeNetworkName,
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF1A1B23),
                            lineHeight = 20.sp,
                            maxLines = 1
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "STATUS",
                        fontFamily = RobotoFontFamily,
                        fontSize = 10.sp,
                        color = Color(0xFF444654).copy(alpha = 0.7f),
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isStreaming) "SYNCING" else "READY",
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF1A1B23),
                        lineHeight = 20.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (isStreaming) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSending) "SENDING..." else "RECEIVING...",
                        fontFamily = RobotoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFF1A1B23),
                        lineHeight = 15.sp
                    )
                    Text(
                        text = "${(progress * 100).toInt()}% COMPLETE",
                        fontFamily = RobotoFontFamily,
                        fontSize = 12.sp,
                        color = Color(0xFF1A1B23),
                        lineHeight = 15.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            SegmentedProgressBar(
                progress = if (isStreaming) progress else 1f, 
                segments = 13,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SPEED",
                        fontFamily = RobotoFontFamily,
                        fontSize = 9.sp,
                        color = Color(0xFF444654).copy(alpha = 0.6f),
                        lineHeight = 13.5.sp
                    )
                    Text(
                        text = speedStr,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF1A1B23),
                        lineHeight = 16.sp
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "STATUS",
                        fontFamily = RobotoFontFamily,
                        fontSize = 9.sp,
                        color = Color(0xFF444654).copy(alpha = 0.6f),
                        lineHeight = 13.5.sp
                    )
                    Text(
                        text = "Wi-Fi",
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF1A1B23),
                        lineHeight = 16.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "HEALTH",
                        fontFamily = RobotoFontFamily,
                        fontSize = 9.sp,
                        color = Color(0xFF444654).copy(alpha = 0.6f),
                        lineHeight = 13.5.sp
                    )
                    Text(
                        text = "100%",
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF1A1B23),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

// ============================================================================
// SEGMENTED PROGRESS BAR
// ============================================================================

@Composable
private fun SegmentedProgressBar(
    progress: Float,
    segments: Int,
    modifier: Modifier = Modifier
) {
    val filledSegments = (progress * segments).toInt()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 0 until segments) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (i < filledSegments)
                            Color(0xFF0A84FF).copy(alpha = 0.8f)
                        else
                            Color(0xFF0A84FF).copy(alpha = 0.2f)
                    )
            )
        }
    }
}

// ============================================================================
// SYNC TOGGLE CARD (2x2 grid item)
// ============================================================================

@Composable
private fun SyncToggleCard(
    title: String,
    subtitle: String,
    iconResId: Int,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(
                            elevation = 1.dp,
                            shape = RoundedCornerShape(12.dp),
                            spotColor = Color.Black.copy(alpha = 0.05f),
                            ambientColor = Color.Transparent
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.9f))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    HomeSvgIcon(
                        rawResId = iconResId,
                        modifier = Modifier.size(16.dp),
                        colorFilter = ColorFilter.tint(Color(0xFF2E4FCF))
                    )
                }
                Switch(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF0A84FF),
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFDAD9E4),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF1A1B23),
                    lineHeight = 24.sp
                )
                Text(
                    text = subtitle,
                    fontFamily = RobotoFontFamily,
                    fontSize = 12.sp,
                    color = Color(0xFF444654),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// ============================================================================
// QUICK ACTIONS CARD (bento row of 4 icons)
// ============================================================================

@Composable
private fun QuickActionsCard(
    onSendTest: () -> Unit,
    onSendFile: () -> Unit,
    onHotspot: () -> Unit,
    onRepair: () -> Unit
) {
    GlassCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(21.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QuickActionButton(label = "Send Test", iconResId = R.raw.sendtest, onClick = onSendTest)
            QuickActionButton(label = "Send File", iconResId = R.raw.sendfile, onClick = onSendFile)
            QuickActionButton(label = "Hotspot", iconResId = R.raw.hotspot, onClick = onHotspot)
            QuickActionButton(label = "Repair", iconResId = R.raw.repair, onClick = onRepair)
        }
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    iconResId: Int,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(
                    elevation = 1.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = Color.Black.copy(alpha = 0.05f),
                    ambientColor = Color.Transparent
                )
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.9f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            HomeSvgIcon(rawResId = iconResId, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            fontFamily = RobotoFontFamily,
            fontSize = 11.sp,
            color = Color(0xFF444654),
            textAlign = TextAlign.Center,
            lineHeight = 13.75.sp
        )
    }
}

// ============================================================================
// RECENT SYNCS CARD
// ============================================================================

@Composable
fun RecentSyncsCard(recentSyncs: List<RecentSyncItem>) {
    GlassCard {
        Column(
            modifier = Modifier.padding(21.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Syncs",
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Color(0xFF204399),
                    lineHeight = 32.sp
                )
                Text(text = "→", fontSize = 18.sp, color = Color(0xFF204399))
            }
            Column {
                recentSyncs.forEachIndexed { index, item ->
                    RecentSyncRow(item = item)
                    if (index < recentSyncs.size - 1) {
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSyncRow(item: RecentSyncItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(
                    elevation = 1.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = Color.Black.copy(alpha = 0.03f),
                    ambientColor = Color.Transparent
                )
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF2F1F8)),
            contentAlignment = Alignment.Center
        ) {
            HomeSvgIcon(rawResId = item.iconResId, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.content,
                fontFamily = RobotoFontFamily,
                fontSize = 16.sp,
                color = Color(0xFF1A1B23),
                lineHeight = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                Text(text = item.direction, fontFamily = RobotoFontFamily, fontSize = 14.sp, color = Color(0xFF444654), lineHeight = 20.sp)
                Text(text = " · ", fontFamily = RobotoFontFamily, fontSize = 14.sp, color = Color(0xFF444654), lineHeight = 20.sp)
                Text(text = item.timeAgo, fontFamily = RobotoFontFamily, fontSize = 14.sp, color = Color(0xFF444654), lineHeight = 20.sp)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (item.isSuccess) Color(0xFF34C759) else Color(0xFFFF3B30))
        )
    }
}

// ============================================================================
// SVG ICON HELPER
// ============================================================================

@Composable
private fun HomeSvgIcon(
    rawResId: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    colorFilter: ColorFilter? = null
) {
    val context = LocalContext.current
    val model = remember(rawResId, context) {
        ImageRequest.Builder(context)
            .data("android.resource://${context.packageName}/$rawResId")
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        colorFilter = colorFilter,
        modifier = modifier
    )
}

// ============================================================================
// PREVIEW
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun NewHomeScreenPreview() {
    ClipSyncTheme {
        NewHomeScreen()
    }
}
