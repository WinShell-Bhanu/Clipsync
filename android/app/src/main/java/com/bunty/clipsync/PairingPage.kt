package com.bunty.clipsync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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
import com.airbnb.lottie.compose.*
import kotlin.math.min

private const val DesignWidth = 412f
private const val DesignHeight = 915f

@Composable
fun PairingPage(
    onConnected: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    ClipSyncTheme {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            PairingPageContent(
                onConnected = onConnected,
                onCancel = onCancel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Suppress("UnusedBoxWithConstraintsScope")
@Composable
private fun PairingPageContent(
    onConnected: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Collect scanning state
    val scanState by BLEScanner.state.collectAsState()
    
    // Start scanning immediately when this page opens
    LaunchedEffect(Unit) {
        BLEScanner.reset()
        BLEScanner.startScan(context)
    }

    // Collect GATT connection state
    val connectionState by BLEConnector.state.collectAsState()

    // Navigate to QR scan as soon as GATT read succeeds
    LaunchedEffect(connectionState) {
        if (connectionState is BLEConnector.ConnectionState.Connected) {
            onConnected()
        }
    }

    val isConnecting = connectionState is BLEConnector.ConnectionState.Connecting
    val errorMessage = (connectionState as? BLEConnector.ConnectionState.Failed)?.reason
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
            // Top Focal Point
            val hsLottieComposition by rememberLottieComposition(LottieCompositionSpec.Asset("hotspot.lottie"))
            val hsLottieProgress by animateLottieCompositionAsState(
                composition = hsLottieComposition,
                iterations = LottieConstants.IterateForever // Loop infinitely
            )

            Box(
                modifier = Modifier
                    .offset(x = dp((DesignWidth - 130f) / 2f), y = dp(168.5f))
                    .size(dp(130f))
                    .shadow(
                        elevation = dp(2f),
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.05f),
                        spotColor = Color.Black.copy(alpha = 0.05f)
                    )
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.4f))
                    .border(dp(1f), Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LottieAnimation(
                    composition = hsLottieComposition,
                    progress = { hsLottieProgress },
                    modifier = Modifier.size(dp(96f))
                )
            }

            // Middle Title
            Text(
                text = "Finding your Mac...",
                fontSize = sp(42f),
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                letterSpacing = sp(-1.26f),
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.27f),
                        offset = Offset(0f, 4f),
                        blurRadius = 42.3f
                    )
                ),
                modifier = Modifier.offset(x = dp(52f), y = dp(310f))
            )

            // Middle Subtitle
            Text(
                text = "Almost there. Keep your devices close.",
                fontSize = sp(16f),
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF37373B),
                letterSpacing = sp(-0.48f),
                modifier = Modifier.offset(x = dp(74f), y = dp(369f))
            )

            // Cancel Button
            Box(
                modifier = Modifier
                    .offset(x = dp((DesignWidth - 88f) / 2f), y = dp(408f))
                    .size(width = dp(88f), height = dp(32f))
                    .shadow(
                        elevation = dp(10f),
                        shape = RoundedCornerShape(dp(24f)),
                        ambientColor = Color.Black.copy(alpha = 0.06f),
                        spotColor = Color.Black.copy(alpha = 0.06f)
                    )
                    .clip(RoundedCornerShape(dp(24f)))
                    .background(Color.White.copy(alpha = 0.45f))
                    .border(dp(1f), Color.White.copy(alpha = 0.37f), RoundedCornerShape(dp(24f)))
                    .clickable {
                        BLEConnector.reset()
                        BLEScanner.reset()
                        onCancel()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CANCEL",
                    fontSize = sp(16f),
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB91010),
                    letterSpacing = sp(0.6f),
                    textAlign = TextAlign.Center
                )
            }

            // Bottom Device Card
            if (scanState !is BLEScanner.ScanState.Found) {
                Box(
                    modifier = Modifier
                        .offset(x = dp(52f), y = dp(561f))
                        .size(width = dp(308f), height = dp(216f))
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
                            color = Color(0x776978FF),
                            shape = RoundedCornerShape(dp(32f))
                        )
                ) {
                    // Scanning state inside the card
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(dp(36f)),
                            color = Color(0xFF546CD9),
                            strokeWidth = dp(3f),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        Spacer(modifier = Modifier.height(dp(16f)))
                        Text(
                            text = if (scanState is BLEScanner.ScanState.Failed) {
                                (scanState as BLEScanner.ScanState.Failed).reason
                            } else "Looking for Mac...",
                            fontSize = sp(15f),
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = if (scanState is BLEScanner.ScanState.Failed) Color(0xFFB91010) else Color(0xFF546CD9)
                        )
                    }
                }
            } else {
                val foundDevices = (scanState as BLEScanner.ScanState.Found).devices
                
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .offset(y = dp(561f))
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = dp(52f)),
                    horizontalArrangement = Arrangement.spacedBy(dp(16f))
                ) {
                    items(foundDevices.size) { index ->
                        val foundDevice = foundDevices[index]
                        Box(
                            modifier = Modifier
                                .size(width = dp(308f), height = dp(216f))
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
                                    color = Color(0x776978FF),
                                    shape = RoundedCornerShape(dp(32f))
                                )
                        ) {
                            // Mac Icon Container
                            Box(
                                modifier = Modifier
                                    .offset(x = dp(24f), y = dp(16f))
                                    .size(dp(56f))
                                    .background(Color(0xFFCFD5F8), RoundedCornerShape(dp(12f))),
                                contentAlignment = Alignment.Center
                            ) {
                                PairingSvgAsset(
                                    rawResId = R.raw.macicon,
                                    contentDescription = "Mac Icon",
                                    modifier = Modifier.size(dp(40f))
                                )
                            }
        
                            // Device Info
                            Text(
                                text = foundDevice.name,
                                fontSize = sp(18f),
                                fontFamily = RobotoFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black,
                                letterSpacing = sp(-0.54f),
                                modifier = Modifier.offset(x = dp(24f), y = dp(81f))
                            )
                            Text(
                                text = "Nearby",
                                fontSize = sp(16f),
                                fontFamily = RobotoFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF889AE6),
                                letterSpacing = sp(-0.48f),
                                modifier = Modifier.offset(x = dp(24f), y = dp(102f))
                            )
        
                            // Signal Indicator Bars
                            Box(modifier = Modifier.offset(x = dp(244f), y = dp(16f))) {
                                // Left (Shortest)
                                Box(
                                    modifier = Modifier
                                        .offset(x = dp(0f), y = dp(24f))
                                        .size(width = dp(12f), height = dp(20f))
                                        .background(Color(0xFF2F4FCF), RoundedCornerShape(dp(32f)))
                                )
                                // Middle
                                Box(
                                    modifier = Modifier
                                        .offset(x = dp(16f), y = dp(12f))
                                        .size(width = dp(12f), height = dp(32f))
                                        .background(Color(0xFF2F4FCF), RoundedCornerShape(dp(32f)))
                                )
                                // Right (Tallest)
                                Box(
                                    modifier = Modifier
                                        .offset(x = dp(32f), y = dp(0f))
                                        .size(width = dp(12f), height = dp(44f))
                                        .background(Color(0xFF2F4FCF), RoundedCornerShape(dp(32f)))
                                )
                            }
        
                            // Connect Button
                            Box(
                                modifier = Modifier
                                    .offset(x = dp(40f), y = dp(143f))
                                    .size(width = dp(226f), height = dp(46f))
                                    .background(
                                        color = if (isConnecting) Color(0xFF8A9BE8) else Color(0xFF546CD9),
                                        shape = RoundedCornerShape(dp(14f))
                                    )
                                    .clickable(enabled = !isConnecting) {
                                        BLEConnector.reset()
                                        BLEScanner.stopScan()
                                        BLEConnector.connect(context, foundDevice.address)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                            if (isConnecting) {
                                // Spinner while connecting / reading GATT
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(dp(10f))
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(dp(20f)),
                                        color = Color.White,
                                        strokeWidth = dp(2f),
                                        strokeCap = StrokeCap.Round
                                    )
                                    Text(
                                        text = "Connecting...",
                                        fontSize = sp(15f),
                                        fontFamily = RobotoFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        letterSpacing = sp(-0.45f)
                                    )
                                }
                            } else {
                                Text(
                                    text = "CONNECT",
                                    fontSize = sp(16f),
                                    fontFamily = RobotoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    letterSpacing = sp(-0.48f)
                                )
                            }
                        }
        
                            // Timeout / error message below the card
                            if (errorMessage != null) {
                                Text(
                                    text = errorMessage,
                                    fontSize = sp(13f),
                                    fontFamily = RobotoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFB91010),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .offset(x = dp(20f), y = dp(196f))
                                        .width(dp(268f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingSvgAsset(
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
private fun PairingPagePreview() {
    ClipSyncTheme {
        PairingPage()
    }
}
