package com.bunty.clipsync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import com.airbnb.lottie.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

// --- Custom Shadow Utility ---
// Draws a high-performance blurred drop shadow using Android framework Paint
fun Modifier.customDropShadow(
    offsetX: Dp = 0.dp,
    offsetY: Dp = 4.dp,
    blurRadius: Dp = 50.dp,
    color: Color = Color.Black.copy(alpha = 0.25f),
    cornerRadius: Dp = 32.dp
) = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            this.color = Color.Transparent
            asFrameworkPaint().apply {
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(
                    blurRadius.toPx(),
                    offsetX.toPx(),
                    offsetY.toPx(),
                    color.toArgb()
                )
            }
        }
        canvas.drawRoundRect(
            0f,
            0f,
            size.width,
            size.height,
            cornerRadius.toPx(),
            cornerRadius.toPx(),
            paint
        )
    }
}

@Composable
fun QRScanScreen(
    initialCameraActive: Boolean = false,
    onQRScanned: (String) -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp
    
    // Reference design: 360x800
    val widthScale = screenWidthDp.value / 360f
    val heightScale = screenHeightDp.value / 800f
    val scale = min(widthScale, heightScale)

    val backgroundColor = Color(0xFFB1C2F6)
    val gradientStartColor = Color(0x4D5E99EC)
    val gradientEndColor = Color(0x4D9B5ABE)

    val robotoFontFamily = FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal),
        Font(R.font.roboto_medium, FontWeight.Medium),
        Font(R.font.roboto_bold, FontWeight.Bold),
        Font(R.font.roboto_black, FontWeight.Black)
    )

    // --- State & Animations ---
    var isCameraActive by remember { mutableStateOf(initialCameraActive) }
    var isLoading by remember { mutableStateOf(false) }
    var scannedData by remember { mutableStateOf("") }

    // Preload Lottie Animation
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("Loading.lottie"))
    val lottieAnimatable = rememberLottieAnimatable()

    // Staggered Entry States
    var showTopCard by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    var showQR by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    // Trigger Entry Animation
    LaunchedEffect(Unit) {
        delay(100)
        showTopCard = true
        delay(200)
        showContent = true
        delay(200)
        showQR = true
        delay(200)
        showButton = true
    }

    // Handle animation and transition
    LaunchedEffect(isLoading) {
        if (isLoading) {
            // Play animation once from start to finish
            lottieAnimatable.animate(
                composition = composition,
                initialProgress = 0f
            )
            // Animation finished, trigger callback
            onQRScanned(scannedData)
        }
    }

    // MeshBackground removed (hoisted to MainActivity)
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Responsive dimensions
        val topCardOffsetY = (90 * heightScale).dp
        val topCardHeight = (240 * heightScale).dp
        val cornerRadius = (32 * scale).coerceIn(20f, 32f).dp
        
        val titleFontSize = (58 * scale).coerceIn(36f, 58f).sp
        val titleLineHeight = (54 * scale).coerceIn(34f, 54f).sp
        val subtitleFontSize = (24 * scale).coerceIn(16f, 24f).sp
        val subtitleLineHeight = (28 * scale).coerceIn(18f, 28f).sp
        val contentPadding = (40 * scale).dp
        
        val qrCardSize = (min(screenWidthDp.value * 0.9f, 352f)).dp
        val qrCardOffsetY = (370 * heightScale).dp
        
        val buttonWidth = (161 * scale).coerceIn(130f, 161f).dp
        val buttonHeight = (59 * scale).coerceIn(48f, 59f).dp
        val buttonFontSize = (26 * scale).coerceIn(18f, 26f).sp
        val buttonBottomOffset = (-40).dp

        // Top gradient card
        androidx.compose.animation.AnimatedVisibility(
                visible = showTopCard,
                enter = androidx.compose.animation.fadeIn(tween(600)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { -100 }, animationSpec = tween(600))
            ) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = (-30).dp,
                            y = topCardOffsetY
                        )
                        .width(screenWidthDp + 60.dp)
                        .height(topCardHeight)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    gradientStartColor,
                                    gradientEndColor
                                )
                            )
                        )
                ) {
                    // --- Camera View Container ---
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showContent,
                        enter = androidx.compose.animation.fadeIn(tween(800))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = contentPadding, vertical = (10 * heightScale).dp)
                        ) {
                            Text(
                                text = "Pair With\nyour Mac",
                                fontFamily = robotoFontFamily,
                                fontWeight = FontWeight.Black,
                                fontSize = titleFontSize,
                                letterSpacing = (-0.03).em,
                                lineHeight = titleLineHeight,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            Text(
                                text = "Open ClipSync on your Mac and scan the QR code to connect instantly.",
                                fontFamily = robotoFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = subtitleFontSize,
                                letterSpacing = (-0.03).em,
                                lineHeight = subtitleLineHeight,
                                style = TextStyle(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF2B90E9),
                                            Color(0xFF6C45BA)
                                        )
                                    )
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = (10 * scale).dp)
                            )
                        }
                    }
                }
            }

            // QR Scanner Card
            androidx.compose.animation.AnimatedVisibility(
                visible = showQR,
                enter = androidx.compose.animation.fadeIn(tween(600)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { 200 }, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = qrCardOffsetY)
            ) {
                Box(
                    modifier = Modifier
                        .size(qrCardSize)
                        .customDropShadow(
                            offsetX = 0.dp,
                            offsetY = 4.dp,
                            blurRadius = 50.dp,
                            color = Color.Black.copy(alpha = 0.25f),
                            cornerRadius = cornerRadius
                        )
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(
                            color = Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(cornerRadius)
                        )
                ) {
                    // Camera preview shows ONLY when button is clicked
                    if (isCameraActive) {
                        CameraQRScanner(
                            onQRCodeScanned = { qrData ->
                                // Stop camera and start loading
                                isCameraActive = false
                                scannedData = qrData
                                isLoading = true
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(cornerRadius))
                        )
                    }
                }
            }

            // "Scan QR" Button - FIXED CLICKABLE
            androidx.compose.animation.AnimatedVisibility(
                visible = showButton,
                enter = androidx.compose.animation.fadeIn(tween(400)) +
                        androidx.compose.animation.scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = buttonBottomOffset)
            ) {
                Box(
                    modifier = Modifier
                        .width(buttonWidth)
                        .height(buttonHeight)
                        .background(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(cornerRadius)
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(cornerRadius)
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            // Activate camera when button is clicked
                            isCameraActive = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.qr_scan),
                            contentDescription = "QR Scanner",
                            modifier = Modifier.size((30 * scale).coerceIn(22f, 30f).dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width((8 * scale).dp))
                        Text(
                            text = "Scan QR",
                            fontFamily = robotoFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = buttonFontSize,
                            letterSpacing = (-0.03).em,
                            color = Color.Black
                        )
                    }
                }
            }

            // Loading Animation Overlay
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            enabled = false
                        ) {}, // Block clicks
                    contentAlignment = Alignment.Center
                ) {
                    LottieAnimation(
                        composition = composition,
                        progress = { lottieAnimatable.progress },
                        modifier = Modifier.size(200.dp)
                    )
                }
            }
        }
    }


