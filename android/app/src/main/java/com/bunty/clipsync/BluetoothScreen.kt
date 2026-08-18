package com.bunty.clipsync

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
fun BluetoothScreen(
    onPermissionsGranted: () -> Unit = {}
) {
    ClipSyncTheme {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            BluetoothScreenContent(
                modifier = Modifier.fillMaxSize(),
                onPermissionsGranted = onPermissionsGranted
            )
        }
    }
}

@Composable
private fun BluetoothScreenContent(
    modifier: Modifier = Modifier,
    onPermissionsGranted: () -> Unit = {}
) {
    val context = LocalContext.current

    // Helper: check if all BLE permissions are already granted
    fun blePermissionsGranted(): Boolean {
        val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return perms.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }


    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }
        if (allGranted) {
            onPermissionsGranted()
        } else {
        }
    }

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
                    .height(dp(252f)) // Enough for title
            ) {
                // Gradient Background behind title
                Box(
                    modifier = Modifier
                        .offset(x = dp(-38f), y = dp(86f))
                        .width(dp(420f))
                        .height(dp(112f))
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
                    text = "Find your Mac",
                    fontSize = sp(48f),
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Start,
                    style = TextStyle(
                        letterSpacing = (-0.03).em,
                        lineHeight = sp(54f)
                    ),
                    modifier = Modifier
                        .offset(x = dp(13f), y = dp(114f))
                        .width(dp(386f))
                )
            }

            // Radar Section
            Box(
                modifier = Modifier
                    .offset(x = dp(51f), y = dp(226f))
                    .size(dp(310f)),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "radar")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                ) {
                    val center = Offset(size.width / 2, size.height / 2)
                    // Draw outer rings
                    drawCircle(
                        color = Color.White.copy(alpha = 0.15f),
                        radius = size.width / 2,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.25f),
                        radius = (245f / 310f) * (size.width / 2),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.4f),
                        radius = (176f / 310f) * (size.width / 2),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Core Shape
                val btLottieComposition by rememberLottieComposition(LottieCompositionSpec.Asset("Bluetooth.lottie"))
                val btLottieProgress by animateLottieCompositionAsState(
                    composition = btLottieComposition,
                    iterations = 1 // Play once
                )

                Box(
                    modifier = Modifier
                        .size(dp(122.5f))
                        .shadow(
                            elevation = dp(8f),
                            shape = CircleShape,
                            ambientColor = Color(0xFF204399).copy(alpha = 0.15f),
                            spotColor = Color(0xFF204399).copy(alpha = 0.15f)
                        )
                        .background(Color.White.copy(alpha = 0.1f), CircleShape)
                        .border(dp(1f), Color.White.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    LottieAnimation(
                        composition = btLottieComposition,
                        progress = { btLottieProgress },
                        modifier = Modifier.size(dp(64f))
                    )
                }
            }

            // Bottom Card
            Box(
                modifier = Modifier
                    .offset(x = dp(27f), y = dp(564f))
                    .width(dp(358f))
                    .height(dp(284f))
                    .shadow(
                        elevation = dp(25.3f),
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(modifier = Modifier.height(dp(20f)))

                    Text(
                        text = "Find your Mac Nearby",
                        fontSize = sp(32f),
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(
                            letterSpacing = sp(-0.96f),
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.27f),
                                offset = Offset(0f, 4f),
                                blurRadius = 42.3f
                            )
                        )
                    )

                    Spacer(modifier = Modifier.height(dp(10f)))

                    Text(
                        text = "ClipSync needs Bluetooth access to\ndetect and securely pair with your\nMac",
                        fontSize = sp(18f),
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF454654),
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            letterSpacing = sp(-0.54f),
                            lineHeight = sp(24f)
                        ),
                        modifier = Modifier.width(dp(278f))
                    )

                    Spacer(modifier = Modifier.height(dp(5f)))

                    // Required Chip
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .width(dp(88f))
                            .height(dp(20f))
                            .shadow(
                                elevation = dp(10f),
                                shape = RoundedCornerShape(dp(24f)),
                                ambientColor = Color.Black.copy(alpha = 0.06f),
                                spotColor = Color.Black.copy(alpha = 0.06f)
                            )
                            .background(Color(0xFFE8E7F2).copy(alpha = 0.45f), RoundedCornerShape(dp(24f)))
                    ) {
                        BluetoothSvgAsset(
                            rawResId = R.raw.bluetooth_error,
                            contentDescription = "Required",
                            modifier = Modifier.size(dp(16f))
                        )
                        Spacer(modifier = Modifier.width(dp(4f)))
                        Text(
                            text = "REQUIRED",
                            fontSize = sp(13f),
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFC51C1C),
                            style = TextStyle(
                                letterSpacing = sp(-0.39f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(dp(12f)))

                    // Divider
                    HorizontalDivider(
                        color = Color.Black.copy(alpha = 0.1f),
                        thickness = dp(1f),
                        modifier = Modifier.width(dp(300f))
                    )
                    
                    Spacer(modifier = Modifier.height(dp(12f)))
                    BluetoothActionButton(
                        label = "Enable and Continue",
                        scale = scale,
                        onClick = {
                            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_ADVERTISE
                                )
                            } else {
                                arrayOf(
                                    Manifest.permission.BLUETOOTH,
                                    Manifest.permission.BLUETOOTH_ADMIN,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            }
                            permissionLauncher.launch(permissions)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BluetoothActionButton(
    label: String,
    scale: Float,
    onClick: () -> Unit
) {
    fun dp(value: Float): Dp = (value * scale).dp
    fun sp(value: Float) = (value * scale).sp

    val cornerRadius = dp(28f) // Half of height 56 for pill shape
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = if (isPressed) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "buttonScale"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .width(dp(260f))
            .height(dp(56f))
            .shadow(
                elevation = dp(8f),
                shape = RoundedCornerShape(cornerRadius),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .background(
                color = Color(0xFFE8EBFA),
                shape = RoundedCornerShape(cornerRadius)
            )
            .border(
                width = dp(1.5f),
                color = Color.White,
                shape = RoundedCornerShape(cornerRadius)
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = sp(18f),
            style = TextStyle(
                letterSpacing = (-0.02).em
            ),
            color = Color(0xFF1A1C29)
        )
    }
}

@Composable
private fun BluetoothSvgAsset(
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
private fun BluetoothScreenPreview() {
    ClipSyncTheme {
        BluetoothScreen()
    }
}
