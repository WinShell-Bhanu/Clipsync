package com.bunty.clipsync

import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlin.math.min
import androidx.compose.runtime.saveable.rememberSaveable

private const val DesignWidth  = 412f
private const val DesignHeight = 915f

// Step index constants for readability
private const val STEP_WIFI      = 0
private const val STEP_FIND_MAC  = 1
private const val STEP_CONNECT   = 2
private const val TAG = "LocalNetworkScreen"

private enum class StepStatus { PENDING, ACTIVE, DONE }

@Composable
fun LocalNetworkScreen(modifier: Modifier = Modifier) {
    val context  = LocalContext.current
    val syncState by LocalSyncManager.state.collectAsState()

    // Verify the local TCP route when this screen appears. Onboarding should not
    // advance until the Mac listener is actually reachable.
    LaunchedEffect(Unit) {
        LocalSyncManager.startOnboardingHandshake(context)
    }
    LaunchedEffect(syncState) {
    }
    DisposableEffect(Unit) {
        onDispose {
            LocalSyncManager.stopDiscovery()
        }
    }

    // Cloud-transfer approval dialog
    val pendingCloud = syncState as? LocalSyncManager.SyncState.PendingCloudApproval
    if (pendingCloud != null) {
        CloudApprovalDialog(
            sizeBytes = pendingCloud.contentSizeBytes,
            onApprove = {
                val lastContent = ClipboardAccessibilityService.lastSyncedContent
                LocalSyncManager.approveCloudTransfer(context, lastContent)
            },
            onDismiss = { LocalSyncManager.resetToIdle() }
        )
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        LocalNetworkContent(
            syncState = syncState,
            modifier  = Modifier.fillMaxSize()
        )
    }
}

private fun describeLocalNetworkState(state: LocalSyncManager.SyncState): String = when (state) {
    LocalSyncManager.SyncState.Idle -> "Idle"
    LocalSyncManager.SyncState.CheckingWifi -> "Checking Wi-Fi"
    LocalSyncManager.SyncState.DiscoveringMac -> "Discovering Mac via Bonjour"
    is LocalSyncManager.SyncState.Found -> "Found Mac at ${state.macIp}"
    LocalSyncManager.SyncState.SendingWakeup -> "Sending BLE wakeup/handshake"
    LocalSyncManager.SyncState.Connecting -> "Connecting"
    is LocalSyncManager.SyncState.Streaming -> "Streaming progress=${state.progress}"
    LocalSyncManager.SyncState.Success -> "Success"
    is LocalSyncManager.SyncState.PendingCloudApproval -> "Pending cloud approval size=${state.contentSizeBytes}"
    is LocalSyncManager.SyncState.Failed -> "Failed: ${state.reason}"
}

// ── Cloud approval dialog ─────────────────────────────────────────────────────

@Composable
private fun CloudApprovalDialog(
    sizeBytes: Long,
    onApprove: () -> Unit,
    onDismiss: () -> Unit
) {
    val sizeMb = "%.1f".format(sizeBytes / (1024f * 1024f))
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Not on same network") },
        text    = {
            Text(
                "Your Mac isn't reachable on this network. " +
                "Send $sizeMb MB via cloud (Firebase)? " +
                "This uses your internet connection."
            )
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) { Text("Send via Cloud") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Skip") }
        }
    )
}

// ── Main content ──────────────────────────────────────────────────────────────

@Suppress("UnusedBoxWithConstraintsScope")
@Composable
private fun LocalNetworkContent(
    syncState: LocalSyncManager.SyncState,
    modifier:  Modifier = Modifier
) {
    // Map SyncState → step statuses
    val steps: Array<StepStatus> = when (syncState) {
        is LocalSyncManager.SyncState.Idle,
        is LocalSyncManager.SyncState.CheckingWifi ->
            arrayOf(StepStatus.ACTIVE, StepStatus.PENDING, StepStatus.PENDING)

        is LocalSyncManager.SyncState.DiscoveringMac ->
            arrayOf(StepStatus.DONE, StepStatus.ACTIVE, StepStatus.PENDING)

        is LocalSyncManager.SyncState.Found,
        is LocalSyncManager.SyncState.SendingWakeup ->
            arrayOf(StepStatus.DONE, StepStatus.DONE, StepStatus.ACTIVE)

        is LocalSyncManager.SyncState.Connecting,
        is LocalSyncManager.SyncState.Streaming ->
            arrayOf(StepStatus.DONE, StepStatus.DONE, StepStatus.ACTIVE)

        is LocalSyncManager.SyncState.Success ->
            arrayOf(StepStatus.DONE, StepStatus.DONE, StepStatus.DONE)

        is LocalSyncManager.SyncState.Failed,
        is LocalSyncManager.SyncState.PendingCloudApproval ->
            arrayOf(StepStatus.DONE, StepStatus.DONE, StepStatus.PENDING)
    }

    val statusLabel = when (syncState) {
        is LocalSyncManager.SyncState.Idle            -> "Waiting for clipboard…"
        is LocalSyncManager.SyncState.CheckingWifi    -> "Checking Wi-Fi…"
        is LocalSyncManager.SyncState.DiscoveringMac  -> "Scanning for Mac on your network…"
        is LocalSyncManager.SyncState.Found           -> "Mac found at ${syncState.macIp}"
        is LocalSyncManager.SyncState.SendingWakeup   -> "Waking up Mac…"
        is LocalSyncManager.SyncState.Connecting      -> "Connecting…"
        is LocalSyncManager.SyncState.Streaming       -> {
            val pct = ((syncState.progress * 100).toInt()).coerceIn(0, 100)
            "Sending… $pct%"
        }
        is LocalSyncManager.SyncState.Success         -> "Local route ready"
        is LocalSyncManager.SyncState.PendingCloudApproval -> "Not on same network"
        is LocalSyncManager.SyncState.Failed          -> syncState.reason
    }

    BoxWithConstraints(modifier = modifier) {
        val scale = min(maxWidth.value / DesignWidth, maxHeight.value / DesignHeight)

        fun dp(v: Float): Dp  = (v * scale).dp
        fun sp(v: Float)      = (v * scale).sp

        Box(
            modifier = Modifier
                .width(dp(DesignWidth))
                .height(dp(DesignHeight))
                .align(Alignment.TopCenter)
        ) {
            // ── Title ────────────────────────────────────────────────────────
            Text(
                text        = "Setting up fast\nlocal sync...",
                fontSize    = sp(48f),
                fontFamily  = RobotoFontFamily,
                fontWeight  = FontWeight.Bold,
                color       = Color.White,
                textAlign   = TextAlign.Center,
                lineHeight  = sp(54f),
                letterSpacing = (-0.03).em,
                style       = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color      = Color.Black.copy(alpha = 0.27f),
                        offset     = Offset(0f, 4f),
                        blurRadius = 42.3f
                    )
                ),
                modifier = Modifier
                    .offset(x = dp(56f), y = dp(146f))
                    .width(dp(300f))
            )

            // ── Router icon ───────────────────────────────────────────────────
            LocalNetworkSvgAsset(
                rawResId           = R.raw.localnetwork_router,
                contentDescription = "Router",
                modifier = Modifier
                    .offset(x = dp((DesignWidth - 96f) / 2f), y = dp(315f))
                    .size(dp(96f))
            )

            // ── Progress steps card ───────────────────────────────────────────
            Box(
                modifier = Modifier
                    .offset(x = dp(35f), y = dp(468f))
                    .size(width = dp(342f), height = dp(200f))
                    .shadow(
                        elevation    = dp(20f),
                        shape        = RoundedCornerShape(dp(24f)),
                        ambientColor = Color.Black.copy(alpha = 0.1f),
                        spotColor    = Color.Black.copy(alpha = 0.1f)
                    )
                    .clip(RoundedCornerShape(dp(24f)))
                    .background(Color(0xFF141E5A).copy(alpha = 0.35f))
                    .border(dp(1f), Color.White.copy(alpha = 0.2f), RoundedCornerShape(dp(24f)))
                    .padding(dp(25f))
            ) {
                // Vertical connector line
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(dp(32f))
                        .drawBehind {
                            val lineX  = size.width / 2f
                            val startY = dp(32f).toPx()
                            val endY   = size.height - dp(32f).toPx()
                            drawLine(
                                color       = Color.White.copy(alpha = 0.2f),
                                start       = Offset(lineX, startY),
                                end         = Offset(lineX, endY),
                                strokeWidth = dp(1f).toPx()
                            )
                        }
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(dp(24f)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    SyncStep(
                        label    = "Connected to same Wi-Fi",
                        status   = steps[STEP_WIFI],
                        scale    = scale,
                        iconRes  = R.raw.localnetwork_overlay_border
                    )
                    SyncStep(
                        label    = "Finding Mac on network…",
                        status   = steps[STEP_FIND_MAC],
                        scale    = scale,
                        iconRes  = R.raw.localnetwork_sync
                    )
                    SyncStep(
                        label    = "Establishing HTTPS route…",
                        status   = steps[STEP_CONNECT],
                        scale    = scale,
                        iconRes  = null
                    )
                }
            }

            // ── Status pill (bottom) ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .offset(x = dp(52f), y = dp(694f))
                    .size(width = dp(308f), height = dp(42f))
                    .shadow(
                        elevation    = dp(2f),
                        shape        = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.05f),
                        spotColor    = Color.Black.copy(alpha = 0.05f)
                    )
                    .clip(CircleShape)
                    .background(Color(0xFF0A84FF).copy(alpha = 0.2f))
                    .border(dp(1f), Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment   = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LocalNetworkSvgAsset(
                        rawResId           = R.raw.localnetwork_targeticon,
                        contentDescription = "Status",
                        modifier = Modifier.size(dp(12f))
                    )
                    Spacer(modifier = Modifier.width(dp(8f)))
                    Text(
                        text          = statusLabel,
                        fontSize      = sp(14f),
                        fontFamily    = RobotoFontFamily,
                        fontWeight    = FontWeight.SemiBold,
                        color         = Color.White,
                        letterSpacing = sp(0.35f)
                    )
                }
            }
        }
    }
}

// ── Individual step row ───────────────────────────────────────────────────────

@Composable
private fun SyncStep(
    label:   String,
    status:  StepStatus,
    scale:   Float,
    iconRes: Int?
) {
    fun dp(v: Float): Dp = (v * scale).dp
    fun sp(v: Float)     = (v * scale).sp

    val alpha  = if (status == StepStatus.PENDING) 0.5f else 1f
    val rotate by if (status == StepStatus.ACTIVE) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            initialValue   = 0f,
            targetValue    = 360f,
            animationSpec  = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label          = "spin"
        )
    } else {
        rememberSaveable { mutableFloatStateOf(0f) }
    }

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dp(16f)),
        modifier = Modifier.alpha(alpha)
    ) {
        // Icon / indicator
        Box(
            modifier          = Modifier.size(dp(32f)),
            contentAlignment  = Alignment.Center
        ) {
            when {
                status == StepStatus.DONE -> {
                    // Green checkmark SVG
                    LocalNetworkSvgAsset(
                        rawResId           = R.raw.localnetwork_overlay_border,
                        contentDescription = "Done",
                        modifier           = Modifier.size(dp(32f))
                    )
                }
                status == StepStatus.ACTIVE && iconRes != null -> {
                    // Spinning activity icon
                    LocalNetworkSvgAsset(
                        rawResId           = iconRes,
                        contentDescription = "Active",
                        modifier           = Modifier
                            .size(dp(12f))
                            .rotate(rotate)
                    )
                }
                status == StepStatus.PENDING -> {
                    // Empty circle outline
                    Box(
                        modifier = Modifier
                            .size(dp(32f))
                            .border(dp(2f), Color.White.copy(alpha = 0.4f), CircleShape)
                    )
                }
                else -> {
                    Box(modifier = Modifier.size(dp(32f)))
                }
            }
        }

        Text(
            text       = label,
            fontSize   = sp(15f),
            fontFamily = RobotoFontFamily,
            color      = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── SVG helper ────────────────────────────────────────────────────────────────

@Composable
private fun LocalNetworkSvgAsset(
    rawResId:          Int,
    contentDescription: String?,
    modifier:          Modifier      = Modifier,
    contentScale:      ContentScale  = ContentScale.Fit,
    colorFilter:       ColorFilter?  = null
) {
    val context = LocalContext.current
    val model   = remember(rawResId, context) {
        ImageRequest.Builder(context)
            .data("android.resource://${context.packageName}/$rawResId")
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model              = model,
        contentDescription = contentDescription,
        contentScale       = contentScale,
        colorFilter        = colorFilter,
        modifier           = modifier
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun LocalNetworkScreenPreview() {
    ClipSyncTheme {
        LocalNetworkScreen()
    }
}
