package com.bunty.clipsync

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * LandingScreen is the first screen shown to users who have NOT yet paired a Mac.
 *
 * It animates in three layers sequentially:
 * 1. [ClipSyncTitle]   – the large "ClipSync" heading
 * 2. [SubtitleSection] – the gradient tagline beneath the title
 * 3. [GlassmorphismCard] – the bottom card with the logo, feature highlights, and "Get Started" button
 *
 * On first launch it also auto-detects the user's region via [LocationHelper] and
 * stores it in [DeviceManager] so the correct Firestore region is used later.
 *
 * @param onGetStartedClick Called after the exit animation finishes when the user taps "Get Started".
 */
@Composable
fun LandingScreen(
    onGetStartedClick: () -> Unit = {}
) {

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    // Scale factors relative to the 412×915 design reference — keeps layout proportional on all devices
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight.value / 915f
    val scale = min(widthScale, heightScale)

    // isExiting drives the fade+scale-out transition before navigating away
    var isExiting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // buttonScale is animated on tap to give a satisfying "press" feel
    val buttonScale = remember { Animatable(1f) }

    // Visibility flags for the staggered entrance animations
    var showTitle by remember { mutableStateOf(false) }
    var showSubtitle by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // Auto-detect country code and map it to a Firestore region (US or IN)
        // only if a region hasn't been set before
        if (!DeviceManager.isRegionSet(context)) {
            val countryCode = LocationHelper.detectCountryCode() ?: "IN"

            // Countries served by the US Firestore region
            val euCountries = setOf("ES", "FR", "DE", "IT", "UK", "GB", "NL", "BE", "SE", "NO", "DK", "FI", "IE", "PT", "GR", "AT", "CH", "PL", "CZ", "HU", "RO")

            if (countryCode in listOf("US", "CA", "MX") || countryCode in euCountries) {
                DeviceManager.setTargetRegion(context, "US")
            } else {
                DeviceManager.setTargetRegion(context, "IN")
            }
        }

        // Staggered entrance: title → subtitle → bottom card
        delay(100)
        showTitle = true
        delay(200)
        showSubtitle = true
        delay(200)
        showCard = true
    }

    // Wrap everything in an AnimatedVisibility so the whole screen can fade+scale out
    // before the navigation callback fires
    androidx.compose.animation.AnimatedVisibility(
        visible = !isExiting,
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(300)) +
                androidx.compose.animation.scaleOut(targetScale = 0.9f, animationSpec = tween(300))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Layer 1: large "ClipSync" title slides in from the top
            androidx.compose.animation.AnimatedVisibility(
                visible = showTitle,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { -100 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                ClipSyncTitle()
            }

            // Layer 2: gradient subtitle slides in from the left
            androidx.compose.animation.AnimatedVisibility(
                visible = showSubtitle,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInHorizontally(initialOffsetX = { -100 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                SubtitleSection()
            }

            // Layer 3: glassmorphism card slides up from the bottom
            androidx.compose.animation.AnimatedVisibility(
                visible = showCard,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { 200 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                GlassmorphismCard(
                    buttonScale = buttonScale.value,
                    onGetStartedClick = {
                        scope.launch {
                            // Animate button press: squish down then spring back up
                            buttonScale.animateTo(0.8f, animationSpec = tween(100))
                            buttonScale.animateTo(
                                1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )

                            // Short pause, then trigger exit animation before navigating
                            delay(100)
                            isExiting = true

                            delay(300)
                            onGetStartedClick()
                        }
                    }
                )
            }
        }
    }
}

/**
 * Renders the "ClipSync" heading at the top of the landing screen.
 *
 * Uses a two-layer technique for a depth effect:
 * - A blurred (or low-alpha on older APIs) shadow copy behind the main text.
 * - A solid white foreground copy on top.
 *
 * Font size and top padding are responsive to the current screen height.
 */
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
        // Shadow / blur layer — creates an embossed depth effect behind the title
        Text(
            text = "ClipSync",
            fontSize = titleFontSize,
            fontFamily = FontFamily(Font(R.font.roboto_bold)),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.03f * 64).sp,
            color = Color.Black.copy(alpha = 0.25f),
            style = TextStyle.Default,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .offset(y = (12 * heightScale).dp)
                .graphicsLayer {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        // API 31+: use a real RenderEffect Gaussian blur for the shadow
                        renderEffect = RenderEffect
                            .createBlurEffect(
                                25f, 25f,
                                Shader.TileMode.DECAL
                            )
                            .asComposeRenderEffect()
                    } else {
                        // Fallback for older devices: just reduce the alpha
                        alpha = 0.1f
                    }
                }
        )

        // Foreground text — solid white, rendered above the blurred shadow layer
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

/**
 * Renders the gradient tagline "ReImagined the Apple Way" beneath the title.
 *
 * The text uses a linear gradient brush from a teal-blue to a deep purple,
 * giving it the "Apple aesthetic" look that matches the app's brand.
 *
 * Font size and top padding are responsive to the current screen height.
 */
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
            // Gradient brush: teal-blue → deep purple
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

/**
 * The main bottom card on the landing screen.
 *
 * Contains three visual layers stacked inside a Box:
 * 1. A rounded glassmorphism card background (gradient fill + clip).
 * 2. A [feature highlights card][featureCard] showing "No Sign-up Required" and "Your clipboard stays private".
 * 3. The animated app logo loaded from the assets SVG.
 * 4. The [GetStartedButton] positioned below the feature card.
 *
 * All dimensions scale proportionally to the current device screen size.
 *
 * @param buttonScale       Current scale value of the button (driven by press animation).
 * @param onGetStartedClick Callback fired when the user taps the "Get Started" button.
 */
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

    // Card position and size
    val cardTopPadding = (338 * heightScale).dp
    val cardHeight = (screenHeight.value * 0.63f).dp
    val cornerRadius = (28 * scale).coerceIn(20f, 28f).dp

    // App logo size and vertical offset inside the card
    val logoWidth = (201 * scale).coerceIn(140f, 201f).dp
    val logoHeight = (190 * scale).coerceIn(130f, 190f).dp
    val logoOffsetY = (27 * heightScale).dp

    // Feature highlight card (the pill-shaped inner card with two columns)
    val featureCardWidth = (screenWidth.value * 0.85f).dp
    val featureCardHeight = (104 * scale).coerceIn(5f, 104f).dp
    val featureCardOffsetY = (logoHeight.value + logoOffsetY.value + 50 * heightScale).dp

    // "Get Started" button position
    val buttonOffsetY = (featureCardOffsetY.value + featureCardHeight.value + 60 * heightScale).dp

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
                        // Horizontal gradient from indigo-blue to soft purple (both at 30% opacity)
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

                // ── Feature highlights card ───────────────────────────────────
                // A semi-transparent white inner card with two columns:
                // Left:  key icon + "No Sign up Required"
                // Right: shield icon + "Your clipboard stays private"
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

                        // Left feature column: no sign-up
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

                        // Right feature column: privacy
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

                // ── Animated logo ─────────────────────────────────────────────
                // The SVG logo fades and scales in from zero using two separate Animatables
                // so scale and alpha can be independently controlled if needed.
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

                // Loaded from assets as an SVG via Coil + SvgDecoder
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

                // ── Get Started button ────────────────────────────────────────
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

/**
 * The primary CTA button on the landing screen.
 *
 * Styled as a semi-transparent pill with a white border (glassmorphism).
 * The button size, font, and corner radius all scale with screen density.
 *
 * @param modifier  Optional [Modifier] for positioning (e.g. used inside a [Box] with [Alignment]).
 * @param scale     Current scale value used by the press animation in [LandingScreen].
 * @param onClick   Called when the user taps the button.
 */
@Composable
fun GetStartedButton(
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    onClick: () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val sizeScale = min(screenWidth.value / 412f, screenHeight.value / 915f)

    // All dimensions clamp to a min/max range so the button stays usable on very small or large screens
    val buttonWidth = (180 * sizeScale).coerceIn(160f, 180f).dp
    val buttonHeight = (59 * sizeScale).coerceIn(48f, 59f).dp
    val fontSize = (26 * sizeScale).coerceIn(20f, 26f).sp
    val cornerRadius = (32 * sizeScale).coerceIn(24f, 32f).dp

    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = buttonWidth, height = buttonHeight)
            // Apply the press scale animation driven by the parent composable
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, Color.White),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.2f)  // frosted glass look
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "Get Started",
            color = Color(0xFF1061AC),
            fontSize = fontSize,
            fontFamily = FontFamily(Font(R.font.roboto_medium)),
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.03f * 22).sp
        )
    }
}
