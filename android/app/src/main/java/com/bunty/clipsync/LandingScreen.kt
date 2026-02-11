package com.bunty.clipsync


import android.os.Build
import android.util.Log
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.min

val BackgroundColor = Color(0xFFB1C2F6)

@Composable
fun LandingScreen(
    onGetStartedClick: () -> Unit = {}
) {
    // --- Responsiveness ---
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    // Reference Design: 412x915 dp
    // Scales all UI elements proportionally to screen size
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight.value / 915f
    val scale = min(widthScale, heightScale)

    var isPulsing by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val buttonScale = remember { Animatable(1f) }

    // Staggered Entry States
    var showTitle by remember { mutableStateOf(false) }
    var showSubtitle by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        showTitle = true
        delay(200)
        showSubtitle = true
        delay(200)
        showCard = true
    }

    // --- Region Auto-Detection ---
    // Checks IP location on first launch to route EU users to US servers (Lower Latency)
    // Fallback: India (IN) for rest of world
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!DeviceManager.isRegionSet(context)) {
            val countryCode = LocationHelper.detectCountryCode() ?: "IN" // Default to IN if null
            
            // European Countries (Better latency to US than IN)
            val euCountries = setOf("ES", "FR", "DE", "IT", "UK", "GB", "NL", "BE", "SE", "NO", "DK", "FI", "IE", "PT", "GR", "AT", "CH", "PL", "CZ", "HU", "RO")
            
            if (countryCode in listOf("US", "CA", "MX")) {
                DeviceManager.setTargetRegion(context, "US")
            } else {
                DeviceManager.setTargetRegion(context, "IN")
            }
        }

        delay(100)
        showTitle = true
        delay(200)
        showSubtitle = true
        delay(200)
        showCard = true
    }

    // MeshBackground removed (hoisted to MainActivity)
    androidx.compose.animation.AnimatedVisibility(
        visible = !isExiting,
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(300)) +
                androidx.compose.animation.scaleOut(targetScale = 0.9f, animationSpec = tween(300))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Title: Slide Down + Fade In
            androidx.compose.animation.AnimatedVisibility(
                visible = showTitle,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { -100 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                ClipSyncTitle()
            }

            // Subtitle: Slide In from Left + Fade In
            androidx.compose.animation.AnimatedVisibility(
                visible = showSubtitle,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInHorizontally(initialOffsetX = { -100 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                SubtitleSection()
            }

            // Card: Slide Up + Fade In
            androidx.compose.animation.AnimatedVisibility(
                visible = showCard,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { 200 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                GlassmorphismCard(
                    buttonScale = buttonScale.value,
                    onGetStartedClick = {
                        scope.launch {
                            // 1. Bounce Effect
                            isPulsing = true // Local pulsing for button only if needed, or remove
                            buttonScale.animateTo(0.8f, animationSpec = tween(100))
                            buttonScale.animateTo(
                                1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )

                            // 2. Trigger Exit
                            delay(100)
                            isExiting = true
                            isPulsing = false

                            // 3. Wait for exit anim then navigate
                            delay(300)
                            onGetStartedClick()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ClipSyncTitle() {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val heightScale = screenHeight.value / 915f
    val titleFontSize = (64 * heightScale).coerceIn(42f, 64f).sp
    val topPadding = (122 * heightScale).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        contentAlignment = Alignment.Center
    ) {
        // Blur / Glow Layer (Behind)
        Text(
            text = "ClipSync",
            fontSize = titleFontSize,
            fontFamily = FontFamily(Font(R.font.roboto_bold)),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.03f * 64).sp,
            color = Color.Black.copy(alpha = 0.25f), // Subtle dark blur
            style = TextStyle.Default,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .offset(y = (12 * heightScale).dp) // Slight offset for float
                .graphicsLayer {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        renderEffect = RenderEffect
                            .createBlurEffect(
                                25f, 25f, // High blur radius for soft float
                                Shader.TileMode.DECAL
                            )
                            .asComposeRenderEffect()
                    } else {
                        alpha = 0.1f // Fallback
                    }
                }
        )

        // Main Text (Front)
        Text(
            text = "ClipSync",
            fontSize = titleFontSize,
            fontFamily = FontFamily(Font(R.font.roboto_bold)),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.03f * 64).sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
fun SubtitleSection() {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val heightScale = screenHeight.value / 915f
    val subtitleFontSize = (28 * heightScale).coerceIn(18f, 28f).sp
    val topPadding = (199 * heightScale).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "ReImagined the Apple Way",
            fontSize = subtitleFontSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.03f * 28).sp,
            fontFamily = FontFamily(Font(R.font.roboto_medium)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            textAlign = TextAlign.Center,
            style = TextStyle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF4A889D),
                        Color(0xFF500CFF)
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            ),
            maxLines = 1,
            overflow = TextOverflow.Visible
        )
    }
}

@Composable
fun GlassmorphismCard(
    buttonScale: Float = 1f,
    onGetStartedClick: () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight.value / 915f
    val scale = min(widthScale, heightScale)

    // Responsive dimensions
    val cardTopPadding = (338 * heightScale).dp
    val cardWidth = screenWidth // Full width of the screen 
    val cardHeight = (screenHeight.value * 0.63f).dp // Proportional height
    val cornerRadius = (28 * scale).coerceIn(20f, 28f).dp

    // Logo dimensions
    val logoWidth = (201 * scale).coerceIn(140f, 201f).dp
    val logoHeight = (190 * scale).coerceIn(130f, 190f).dp
    val logoOffsetY = (27 * heightScale).dp

    // Feature card dimensions
    val featureCardWidth = (screenWidth.value * 0.85f).dp
    val featureCardHeight = (104 * scale).coerceIn(5f, 104f).dp
    val featureCardOffsetY = (logoHeight.value + logoOffsetY.value + 50 * heightScale).dp

    // Button position
    val buttonOffsetY = (featureCardOffsetY.value + featureCardHeight.value + 60 * heightScale).dp

    // Font sizes
    val featureFontSize = (16 * scale).coerceIn(12f, 16f).sp
    val iconSize = (30 * scale).coerceIn(22f, 30f).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = cardTopPadding),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight),
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF6F7EF0).copy(alpha = 0.3f),
                                Color(0xFF8568A6).copy(alpha = 0.3f)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        ),
                        shape = RoundedCornerShape(cornerRadius)
                    )
                    .clip(RoundedCornerShape(cornerRadius))
            ) {
                // Feature card
                Card(
                    modifier = Modifier
                        .width(featureCardWidth)
                        .height(featureCardHeight)
                        .align(Alignment.TopCenter)
                        .offset(y = featureCardOffsetY),
                    shape = RoundedCornerShape(cornerRadius),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = (10 * scale).dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Column
                        Column(
                            modifier = Modifier
                                .weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_key),
                                contentDescription = "Key",
                                modifier = Modifier.size(iconSize),
                                colorFilter = ColorFilter.tint(Color.Black)
                            )

                            Spacer(modifier = Modifier.height((6 * scale).dp))
                            Text(
                                text = "No Sign up Required",
                                color = Color.Black,
                                fontSize = featureFontSize,
                                fontFamily = FontFamily(Font(R.font.roboto_regular)),
                                fontWeight = FontWeight.Normal,
                                letterSpacing = (-0.03f * 16).sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.width((16 * scale).dp))

                        // Right Column
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_shield),
                                contentDescription = "Shield",
                                modifier = Modifier.size(iconSize),
                                colorFilter = ColorFilter.tint(Color.Black)
                            )

                            Spacer(modifier = Modifier.height((6 * scale).dp))
                            Text(
                                text = "Your clipboard stays private",
                                color = Color.Black,
                                fontSize = featureFontSize,
                                fontFamily = FontFamily(Font(R.font.roboto_regular)),
                                fontWeight = FontWeight.Normal,
                                letterSpacing = (-0.03f * 16).sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Animated SVG Logo
                val logoScaleAnim = remember { Animatable(0f) }
                val logoAlpha = remember { Animatable(0f) }

                LaunchedEffect(Unit) {
                    logoScaleAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 800)
                    )
                }

                LaunchedEffect(Unit) {
                    logoAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 800)
                    )
                }

                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("file:///android_asset/Logo.svg")
                        .decoderFactory(SvgDecoder.Factory())
                        .build(),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = logoOffsetY)
                        .size(width = logoWidth, height = logoHeight)
                        .graphicsLayer {
                            scaleX = logoScaleAnim.value
                            scaleY = logoScaleAnim.value
                            alpha = logoAlpha.value
                        }
                )

                // Get Started Button
                GetStartedButton(
                    scale = buttonScale,
                    onClick = onGetStartedClick,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = buttonOffsetY)
                )
            }
        }
    }
}

@Composable
fun GetStartedButton(
    scale: Float = 1f,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val sizeScale = min(screenWidth.value / 412f, screenHeight.value / 915f)

    val buttonWidth = (180 * sizeScale).coerceIn(160f, 180f).dp
    val buttonHeight = (59 * sizeScale).coerceIn(48f, 59f).dp
    val fontSize = (26 * sizeScale).coerceIn(20f, 26f).sp
    val cornerRadius = (32 * sizeScale).coerceIn(24f, 32f).dp

    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = buttonWidth, height = buttonHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, Color.White),
        contentPadding = PaddingValues(horizontal = 8.dp), // Reduce padding to fit text
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.2f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "Get Started",
            color = Color(0xFF1061AC),
            fontSize = fontSize,
            fontFamily = FontFamily(Font(R.font.roboto_medium)),
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.03f * 22).sp,
            }
        }
    }
}
