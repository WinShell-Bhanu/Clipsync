package com.bunty.clipsync

import kotlinx.coroutines.delay
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.airbnb.lottie.compose.*
import kotlin.math.min
import androidx.compose.runtime.saveable.rememberSaveable


private const val DesignWidth = 412f
private const val DesignHeight = 915f

@Composable
fun SyncModeScreen(
    modifier: Modifier = Modifier,
    onModeSelected: (String) -> Unit = {}
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        SyncModeContent(
            modifier = Modifier.fillMaxSize(),
            onModeSelected = onModeSelected
        )
    }
}

@Composable
private fun SyncModeContent(
    modifier: Modifier = Modifier,
    onModeSelected: (String) -> Unit = {}
) {
    var pendingMode by rememberSaveable { mutableStateOf<String?>(null) }
    var showConfirmSheet by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val scale = min(
            maxWidth.value / DesignWidth,
            maxHeight.value / DesignHeight
        )
        
        fun dp(value: Float): Dp = (value * scale).dp
        fun sp(value: Float) = (value * scale).sp

        Box(
            modifier = Modifier
                .width(dp(DesignWidth))
                .height(dp(DesignHeight))
                .align(Alignment.TopCenter)
        ) {
            
            // Top Title Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dp(252f)) // 104 + 148 = 252
            ) {
                // Gradient Background behind title
                Box(
                    modifier = Modifier
                        .offset(x = dp(-36f), y = dp(104f))
                        .width(dp(420f))
                        .height(dp(148f))
                        .clip(RoundedCornerShape(dp(32f)))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x4D5E99EC),
                                    Color(0x4D9B5ABE)
                                )
                            )
                        )
                )

                Text(
                    text = "Select your Sync Mode",
                    fontSize = sp(47f),
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Start,
                    lineHeight = sp(52f),
                    letterSpacing = (-0.03).em,
                    modifier = Modifier
                        .offset(x = dp(13f), y = dp(120f))
                        .width(dp(386f)) // Constrain to screen width minus left/right margins (412 - 26 = 386)
                )
            }

            // Pager Section
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })
            // Wiggle Animation State
            val wiggleOffset = remember { Animatable(0f) }
            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage == 0) {
                    delay(3000)
                    // Wiggle up
                    wiggleOffset.animateTo(-25f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                    // Settle back down
                    wiggleOffset.animateTo(0f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
                }
            }

            androidx.compose.foundation.pager.VerticalPager(
                state = pagerState,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = androidx.compose.foundation.pager.PagerSnapDistance.atMost(1),
                    snapAnimationSpec = spring(
                        dampingRatio = 0.75f,
                        stiffness = 100f
                    )
                ),
                modifier = Modifier
                    .width(dp(368f))
                    .wrapContentHeight()
                    .align(Alignment.TopCenter)
                    .offset(y = dp(322f) + dp(wiggleOffset.value))
            ) { page ->
                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .graphicsLayer {
                            if (pageOffset > 0f) {
                                // Swiping up: Current active page fades out and moves up naturally
                                alpha = 1f - pageOffset
                            } else {
                                // Swiping up: Next page comes from behind (stacked effect)
                                val pagerHeightPx = 480f * density * scale
                                val peekOffsetPx = 44.5f * density * scale
                                
                                // Cancel default layout offset and add peek down
                                translationY = (pageOffset * pagerHeightPx) - (pageOffset * peekOffsetPx)
                                
                                val scaleFactor = 1f + (pageOffset * 0.103f) // scale down to ~0.897
                                scaleX = scaleFactor
                                scaleY = scaleFactor
                                
                                alpha = 1f + (pageOffset * 0.4f) // alpha drops to 0.6
                            }
                        }
                ) {
                    if (page == 0) {
                        HybridSyncCard(
                            scale = scale,
                            onSelect = {
                                pendingMode = "hybrid"
                                showConfirmSheet = true
                            }
                        )
                    } else {
                        LocalSyncCard(
                            scale = scale,
                            onSelect = {
                                pendingMode = "local"
                                showConfirmSheet = true
                            }
                        )
                    }
                }
            }

            // Swipe Arrow Lottie Indicator
            val arrowLottieComposition by rememberLottieComposition(LottieCompositionSpec.Asset("arrow.lottie"))
            val arrowLottieProgress by animateLottieCompositionAsState(
                composition = arrowLottieComposition,
                iterations = LottieConstants.IterateForever
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = pagerState.currentPage == 0,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = dp(-30f)),
                enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
            ) {
                LottieAnimation(
                    composition = arrowLottieComposition,
                    progress = { arrowLottieProgress },
                    modifier = Modifier
                        .size(dp(72f))
                        .graphicsLayer { rotationZ = -90f }
                )
            }
        }
    }

    // Reconfirmation bottom sheet
    if (showConfirmSheet && pendingMode != null) {
        SyncModeConfirmSheet(
            mode = pendingMode!!,
            onConfirm = {
                showConfirmSheet = false
                onModeSelected(pendingMode!!)
            },
            onDismiss = {
                showConfirmSheet = false
                pendingMode = null
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Reconfirmation bottom sheet
// ---------------------------------------------------------------------------

@Composable
private fun SyncModeConfirmSheet(
    mode: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isHybrid = mode == "hybrid"
    val modeTitle = if (isHybrid) "Hybrid Sync" else "Local Sync"
    val modeDescription = if (isHybrid)
        "Sync locally when nearby. Switch to cloud sync automatically when you're far away."
    else
        "Sync only over local Wi-Fi & Bluetooth. No cloud, fully private."

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Dim scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss
                    )
            )

            // Sheet card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .shadow(
                        elevation = 32.dp,
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                        ambientColor = Color.Black.copy(alpha = 0.2f),
                        spotColor = Color.Black.copy(alpha = 0.2f)
                    )
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.92f),
                                Color.White.copy(alpha = 0.80f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.9f),
                                Color.White.copy(alpha = 0.2f)
                            )
                        ),
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 32.dp)
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Use $modeTitle?",
                        fontSize = 26.sp,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        color = Color.Black
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = modeDescription,
                        fontSize = 15.sp,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF454654),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        modifier = Modifier.width(280.dp)
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Confirm button
                    Box(
                        modifier = Modifier
                            .width(280.dp)
                            .height(54.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(27.dp),
                                ambientColor = Color(0xFF4A6CF7).copy(alpha = 0.3f),
                                spotColor = Color(0xFF4A6CF7).copy(alpha = 0.3f)
                            )
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF4A6CF7),
                                        Color(0xFF7B5EA7)
                                    )
                                ),
                                shape = RoundedCornerShape(27.dp)
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onConfirm
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Confirm — $modeTitle",
                            fontSize = 16.sp,
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            letterSpacing = (-0.3).sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Cancel / Change
                    Box(
                        modifier = Modifier
                            .width(280.dp)
                            .height(48.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onDismiss
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Change Mode",
                            fontSize = 15.sp,
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Normal,
                            color = Color.Black.copy(alpha = 0.55f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun HybridSyncCard(scale: Float, onSelect: () -> Unit = {}) {
    fun dp(value: Float): Dp = (value * scale).dp
    fun sp(value: Float) = (value * scale).sp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .shadow(
                elevation = dp(25f),
                shape = RoundedCornerShape(dp(32f)),
                ambientColor = Color.Black.copy(alpha = 0.13f),
                spotColor = Color.Black.copy(alpha = 0.13f)
            )
            .clip(RoundedCornerShape(dp(32f)))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.6f)
                    )
                )
            )
            .border(
                width = dp(1.5f),
                color = Color.White.copy(alpha = 0.5f),
                shape = RoundedCornerShape(dp(32f))
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = dp(26f), bottom = dp(44f))
        ) {

            // Wifi Icon Box
            Box(
                modifier = Modifier
                    .size(dp(87f))
                    .shadow(
                        elevation = dp(2f),
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.05f)
                    )
                    .background(Color(0xFFEFF6FF), CircleShape)
                    .border(dp(1f), Color(0xFFDBEAFE), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                SyncModeSvgAsset(
                    rawResId = R.raw.syncmode_wifilock,
                    contentDescription = "Wifi Lock",
                    modifier = Modifier.size(dp(60f))
                )
            }

            Spacer(modifier = Modifier.height(dp(19f)))

            // Hybrid Sync Title
            Text(
                text = "Hybrid Sync",
                fontSize = sp(40f),
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Medium,
                color = Color.Black,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.27f),
                        offset = Offset(0f, 4f),
                        blurRadius = 25f
                    )
                )
            )

            Spacer(modifier = Modifier.height(dp(23f)))

            // Description Subtitle
            Text(
                text = "Sync locally when nearby.\nSync via Cloud when far away\nfor seamless productivity",
                fontSize = sp(18f),
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF454654),
                textAlign = TextAlign.Center,
                lineHeight = sp(24f),
                modifier = Modifier.width(dp(280f))
            )

            Spacer(modifier = Modifier.height(dp(39f)))

            // Divider line
            HorizontalDivider(
                color = Color.Black.copy(alpha = 0.1f),
                thickness = dp(1.5f),
                modifier = Modifier.width(dp(312f))
            )

            Spacer(modifier = Modifier.height(dp(15f)))

            // Chips Row 1
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                FeatureChip(label = "Anywhere", scale = scale)
                Spacer(modifier = Modifier.width(dp(16f)))
                FeatureChip(label = "Auto-Switch", scale = scale, fontSizeSp = 15f)
            }

            Spacer(modifier = Modifier.height(dp(16f)))

            // Chips Row 2
            FeatureChip(label = "Encrypted", scale = scale)

            Spacer(modifier = Modifier.height(dp(24f)))

            // Select button
            SyncModeSelectButton(label = "Select Hybrid Sync", scale = scale, onClick = onSelect)

            Spacer(modifier = Modifier.height(dp(20f)))
        }
    }
}

@Composable
private fun LocalSyncCard(scale: Float, onSelect: () -> Unit = {}) {
    fun dp(value: Float): Dp = (value * scale).dp
    fun sp(value: Float) = (value * scale).sp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .shadow(
                elevation = dp(25f),
                shape = RoundedCornerShape(dp(32f)),
                ambientColor = Color.Black.copy(alpha = 0.13f),
                spotColor = Color.Black.copy(alpha = 0.13f)
            )
            .clip(RoundedCornerShape(dp(32f)))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.6f)
                    )
                )
            )
            .border(
                width = dp(2f),
                color = Color(0x776978FF), // rgba(105,120,255,0.47)
                shape = RoundedCornerShape(dp(32f))
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = dp(26f), bottom = dp(44f))
        ) {
            // Router Icon Box
            Box(
                modifier = Modifier
                    .size(dp(87f))
                    .shadow(
                        elevation = dp(2f),
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.05f)
                    )
                    .background(Color.White, CircleShape)
                    .border(dp(1f), Color(0xFFDBEAFE), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                SyncModeSvgAsset(
                    rawResId = R.raw.syncmode_router,
                    contentDescription = "Router",
                    modifier = Modifier.size(dp(60f))
                )
            }

            Spacer(modifier = Modifier.height(dp(19f)))

            // Title
            Text(
                text = "Local Sync",
                fontSize = sp(40f),
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Medium,
                color = Color.Black,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.27f),
                        offset = Offset(0f, 4f),
                        blurRadius = 25f
                    )
                )
            )

            Spacer(modifier = Modifier.height(dp(23f)))

            // Description Subtitle
            Text(
                text = "Sync only with nearby devices\nover BLE and Wi-Fi. No cloud,\nno internet required",
                fontSize = sp(18f),
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF454654),
                textAlign = TextAlign.Center,
                lineHeight = sp(24f),
                modifier = Modifier.width(dp(280f))
            )

            Spacer(modifier = Modifier.height(dp(39f)))

            // Divider line
            HorizontalDivider(
                color = Color.Black.copy(alpha = 0.1f),
                thickness = dp(1.5f),
                modifier = Modifier.width(dp(312f))
            )

            Spacer(modifier = Modifier.height(dp(15f)))

            // Chips Row 1
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                FeatureChip(label = "Nearby Only", scale = scale, widthDp = 130f)
                Spacer(modifier = Modifier.width(dp(16f)))
                FeatureChip(label = "Fully Offline", scale = scale, widthDp = 130f)
            }

            Spacer(modifier = Modifier.height(dp(16f)))

            // Chips Row 2
            FeatureChip(label = "100% Private", scale = scale, widthDp = 130f)

            Spacer(modifier = Modifier.height(dp(24f)))

            // Select button
            SyncModeSelectButton(label = "Select Local Sync", scale = scale, onClick = onSelect)

            Spacer(modifier = Modifier.height(dp(20f)))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared select button used inside each card
// ---------------------------------------------------------------------------

@Composable
private fun SyncModeSelectButton(
    label: String,
    scale: Float,
    onClick: () -> Unit
) {
    fun dp(value: Float): Dp = (value * scale).dp
    fun sp(value: Float) = (value * scale).sp

    Box(
        modifier = Modifier
            .width(dp(220f))
            .height(dp(54f))
            .background(
                color = Color(0xFFE2E7FF).copy(alpha = 0.5f), // Subtle blue tint
                shape = RoundedCornerShape(dp(27f))
            )
            .border(
                width = dp(1.5f),
                color = Color.White.copy(alpha = 0.8f),
                shape = RoundedCornerShape(dp(27f))
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = sp(18f),
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1F2937),
            letterSpacing = (-0.03).em
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun FeatureChip(
    label: String,
    scale: Float,
    fontSizeSp: Float = 16f,
    widthDp: Float = 124f
) {
    fun dp(value: Float): Dp = (value * scale).dp
    fun sp(value: Float) = (value * scale).sp
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .width(dp(widthDp))
            .height(dp(32f))
            .shadow(
                elevation = dp(10f),
                shape = RoundedCornerShape(dp(24f)),
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.06f)
            )
            .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(dp(24f)))
            .border(dp(1f), Color.White.copy(alpha = 0.37f), RoundedCornerShape(dp(24f)))
            .padding(start = dp(6f))
    ) {
        SyncModeSvgAsset(
            rawResId = R.raw.syncmode_tick,
            contentDescription = "Tick",
            modifier = Modifier.size(dp(24f))
        )
        Spacer(modifier = Modifier.width(dp(6f)))
        Text(
            text = label,
            fontSize = sp(fontSizeSp),
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.Medium,
            color = Color.Black,
            maxLines = 1
        )
    }
}

@Composable
private fun SyncModeSvgAsset(
    rawResId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
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
        contentScale = contentScale,
        colorFilter = colorFilter,
        modifier = modifier
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun SyncModeScreenPreview() {
    ClipSyncTheme {
        SyncModeScreen()
    }
}
