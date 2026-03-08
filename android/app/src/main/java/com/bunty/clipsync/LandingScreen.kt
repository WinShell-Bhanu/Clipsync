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
 * LandingScreen is the entry point shown to users who have not yet paired their Android
 * device with a Mac instance of ClipSync. It is displayed when no active pairing record
 * exists in local storage.
 *
 * The screen is composed of three independently gated [AnimatedVisibility] layers that
 * reveal themselves in a deliberate staggered sequence the moment the composable enters
 * the composition:
 *  1. [ClipSyncTitle]     – the large "ClipSync" wordmark fades in and slides down from
 *                           100 px above its resting position.
 *  2. [SubtitleSection]   – the gradient "ReImagined the Apple Way" tagline fades in and
 *                           slides in from 100 px to the left, building visual hierarchy
 *                           directly beneath the title.
 *  3. [GlassmorphismCard] – the frosted-glass bottom card springs up from 200 px below;
 *                           it houses the animated SVG app logo, two feature-highlight
 *                           columns, and the primary "Get Started" CTA button.
 *
 * On the very first app launch the composable also performs silent IP-based region
 * detection via [LocationHelper.detectCountryCode]. The returned ISO 3166-1 alpha-2
 * country code is mapped to either the `"US"` or `"IN"` Firestore data region and
 * persisted through [DeviceManager] so all subsequent cloud writes are routed to the
 * geographically nearest Firestore cluster. The detection is skipped when a region
 * value has already been saved.
 *
 * Tapping "Get Started" triggers a two-phase animated exit sequence:
 *  - Phase 1: the button squishes to 80 % of its size in 100 ms, then springs back to
 *    full scale with a medium-bouncy spring for satisfying tactile feedback.
 *  - Phase 2: after a brief pause the entire screen simultaneously fades out and scales
 *    down to 0.9×; once the animation completes, [onGetStartedClick] is invoked.
 *
 * @param onGetStartedClick Invoked after the screen-exit animation has fully completed.
 *                          The caller is responsible for navigating to the next screen,
 *                          which is typically the permission-onboarding flow.
 */
@Composable
fun LandingScreen(
    onGetStartedClick: () -> Unit = {}
) {

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    // Derive independent width and height scale factors against the 412×915 dp design
    // reference canvas. Taking the smaller of the two preserves the intended visual
    // proportions on devices with very tall or very wide display aspect ratios.
    val widthScale = screenWidth.value / 412f
    val heightScale = screenHeight.value / 915f
    val scale = min(widthScale, heightScale)

    // When flipped to true, the outer AnimatedVisibility plays the fade+shrink exit
    // spec on the entire screen, creating a seamless transition to the next route.
    var isExiting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Animatable whose current value is forwarded to GlassmorphismCard as `buttonScale`.
    // It is driven programmatically on tap to produce the squish-and-bounce press effect.
    val buttonScale = remember { Animatable(1f) }

    // Boolean gates for each of the three entrance layers. They are set to true in
    // sequence inside LaunchedEffect with deliberate inter-layer delays to create the
    // cascading stagger reveal.
    var showTitle by remember { mutableStateOf(false) }
    var showSubtitle by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // One-time IP geolocation to select the nearest Firestore data region.
        // DeviceManager persists the result in SharedPreferences, so this network
        // call only happens on the very first launch. An unresolvable country code
        // falls back to "IN" (India region).
        if (!DeviceManager.isRegionSet(context)) {
            val countryCode = LocationHelper.detectCountryCode() ?: "IN"

            // Countries whose traffic should be routed to the US-based Firestore region,
            // covering North America and the major EU member states.
            val euCountries = setOf("ES", "FR", "DE", "IT", "UK", "GB", "NL", "BE", "SE", "NO", "DK", "FI", "IE", "PT", "GR", "AT", "CH", "PL", "CZ", "HU", "RO")

            if (countryCode in listOf("US", "CA", "MX") || countryCode in euCountries) {
                DeviceManager.setTargetRegion(context, "US")
            } else {
                DeviceManager.setTargetRegion(context, "IN")
            }
        }

        // Stagger the three entrance layers with 200 ms gaps so each element arrives
        // with a perceptible delay, producing a polished top-down cascading reveal.
        delay(100)
        showTitle = true
        delay(200)
        showSubtitle = true
        delay(200)
        showCard = true
    }

    // The outermost AnimatedVisibility wraps the entire screen so that toggling
    // `isExiting` to true simultaneously fades and shrinks the whole UI before
    // the navigation callback is invoked, keeping the transition smooth.
    androidx.compose.animation.AnimatedVisibility(
        visible = !isExiting,
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(300)) +
                androidx.compose.animation.scaleOut(targetScale = 0.9f, animationSpec = tween(300))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Layer 1 – wordmark: fades in over 800 ms while sliding down from 100 px above.
            androidx.compose.animation.AnimatedVisibility(
                visible = showTitle,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { -100 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                ClipSyncTitle()
            }

            // Layer 2 – tagline: fades in over 800 ms while sliding in from 100 px to the left.
            androidx.compose.animation.AnimatedVisibility(
                visible = showSubtitle,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInHorizontally(initialOffsetX = { -100 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                SubtitleSection()
            }

            // Layer 3 – bottom card: fades in over 800 ms while sliding up from 200 px below.
            androidx.compose.animation.AnimatedVisibility(
                visible = showCard,
                enter = androidx.compose.animation.fadeIn(tween(800)) +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { 200 }, animationSpec = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            ) {
                GlassmorphismCard(
                    buttonScale = buttonScale.value,
                    onGetStartedClick = {
                        scope.launch {
                            // Phase 1: compress the button to 80 % in 100 ms, then spring
                            // it back to full scale with a medium-bouncy feel.
                            buttonScale.animateTo(0.8f, animationSpec = tween(100))
                            buttonScale.animateTo(
                                1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )

                            // Phase 2: brief pause so the bounce completes visually, then
                            // flip `isExiting` to trigger the full-screen fade+shrink exit.
                            delay(100)
                            isExiting = true

                            // Wait for the 300 ms exit animation to finish before navigating.
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
 * Renders the large "ClipSync" wordmark at the top of the landing screen.
 *
 * A two-layer painting technique is used to create a convincing sense of depth and
 * dimensionality without a real drop-shadow drawable:
 *
 *  - **Shadow layer** (behind): the same "ClipSync" text is drawn in black at 25 %
 *    opacity, shifted 12 px downward. On API 31+ (Android 12) a real hardware-accelerated
 *    [RenderEffect] Gaussian blur (σ = 25) is applied to this layer, making the shadow
 *    soft and diffused. On older devices, where [RenderEffect] is unavailable, the alpha
 *    is simply reduced to 10 % to hint at the shadow without blurring.
 *
 *  - **Foreground layer** (in front): the same text in solid white, drawn at the natural
 *    position, sits on top of the blurred shadow and reads as a clean, embossed headline.
 *
 * Both the font size and the top padding are calculated as a proportion of the device's
 * physical screen height relative to the 915 dp reference, so the title scales gracefully
 * across compact and large-screen form factors without hard-coded breakpoints.
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
        // Shadow layer: rendered below and slightly offset from the foreground text.
        // On API 31+ a hardware-accelerated Gaussian blur softens its edges; on older
        // devices the alpha is simply lowered to preserve a subtle shadow hint.
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
                        // API 31+: apply a hardware-accelerated RenderEffect Gaussian blur
                        // with σ = 25 and DECAL tile mode to create a realistic soft shadow.
                        renderEffect = RenderEffect
                            .createBlurEffect(
                                25f, 25f,
                                Shader.TileMode.DECAL
                            )
                            .asComposeRenderEffect()
                    } else {
                        // Pre-API 31 fallback: reduce alpha to 10 % to approximate the
                        // depth effect without hardware-accelerated blur support.
                        alpha = 0.1f
                    }
                }
        )

        // Foreground layer: solid white text rendered at the natural (non-offset) position,
        // sitting visually on top of the blurred shadow to complete the embossed effect.
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
 * Renders the gradient tagline "ReImagined the Apple Way" directly beneath the
 * [ClipSyncTitle] wordmark on the landing screen.
 *
 * Rather than a flat colour, the text is painted with a [Brush.linearGradient] running
 * diagonally from `Offset.Zero` (top-left) to `Offset.Infinite` (bottom-right). The
 * two colour stops transition from a teal-blue (`#4A889D`) to a deep indigo-purple
 * (`#500CFF`), evoking the clean, premium aesthetic that the brand name references.
 *
 * Both the font size (clamped between 18 sp and 28 sp) and the top padding are derived
 * from the device's physical screen height relative to the 915 dp reference height, so
 * the tagline stays proportionally positioned beneath the title on all screen sizes.
 * `TextOverflow.Visible` ensures the text is never clipped even if the gradient brush
 * extends slightly beyond the measured text bounds.
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
            // Diagonal gradient from teal-blue (#4A889D) to deep indigo-purple (#500CFF),
            // giving the tagline its distinctive premium, Apple-inspired colour treatment.
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
 * GlassmorphismCard is the dominant visual element on the landing screen — a large,
 * rounded frosted-glass card that occupies the bottom 63 % of the screen height.
 *
 * The card is built from four visually distinct layers stacked inside a [Box]:
 *
 *  1. **Card background** – a horizontally swept linear gradient from indigo-blue
 *     (`#6F7EF0`) to soft lavender-purple (`#8568A6`), both at 30 % opacity, clipped
 *     to a rounded rectangle. The near-transparent fill creates the glassmorphism look.
 *
 *  2. **Animated app logo** – the SVG logo stored in `assets/Logo.svg` is loaded
 *     by Coil with [SvgDecoder] and simultaneously scales in from 0 → 1 and fades
 *     from 0 → 1 via two independent [Animatable] instances over 800 ms. Using
 *     separate animatables lets the scale and alpha timings be tuned independently
 *     in the future without coupling them to a single [AnimatedVisibility].
 *
 *  3. **Feature highlights inner card** – a smaller pill-shaped card (50 % white
 *     alpha, no elevation) positioned below the logo. It contains two equal-weight
 *     columns in a [Row]:
 *      - Left column: a key icon + "No Sign up Required" label.
 *      - Right column: a shield icon + "Your clipboard stays private" label.
 *
 *  4. **[GetStartedButton]** – the primary CTA, positioned below the feature card.
 *     Its rendered scale is driven by [buttonScale], which is animated externally
 *     by [LandingScreen] to produce the press feedback effect.
 *
 * All metric values (card height, logo size, feature card size, button offset, font
 * sizes, corner radii, icon sizes) are calculated proportionally against the 412×915 dp
 * design reference and clamped to a minimum so no element becomes unusably small on
 * compact displays.
 *
 * @param buttonScale       The current scale factor for the "Get Started" button,
 *                          driven by the press animation in [LandingScreen].
 * @param onGetStartedClick Forwarded directly to [GetStartedButton]; invoked when the
 *                          user taps the primary CTA.
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

    // Outer card geometry: starts 338 dp from the top and fills 63 % of screen height.
    val cardTopPadding = (338 * heightScale).dp
    val cardHeight = (screenHeight.value * 0.63f).dp
    val cornerRadius = (28 * scale).coerceIn(20f, 28f).dp

    // Logo dimensions and its vertical offset from the top of the card interior.
    val logoWidth = (201 * scale).coerceIn(140f, 201f).dp
    val logoHeight = (190 * scale).coerceIn(130f, 190f).dp
    val logoOffsetY = (27 * heightScale).dp

    // Feature highlights card: full-width (85 % of screen), positioned directly below the logo.
    val featureCardWidth = (screenWidth.value * 0.85f).dp
    val featureCardHeight = (104 * scale).coerceIn(5f, 104f).dp
    val featureCardOffsetY = (logoHeight.value + logoOffsetY.value + 50 * heightScale).dp

    // "Get Started" button: offset derived from the bottom edge of the feature card.
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
                        // Horizontal linear gradient swept left-to-right across the full card
                        // width, blending indigo-blue into lavender-purple at 30 % opacity to
                        // achieve the frosted-glass translucency that defines the card's look.
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

                // ── Feature highlights card ───────────────────────────────────────────────
                // A pill-shaped inner card (50 % white alpha, zero elevation) housing two
                // equal columns that communicate the app's core value propositions:
                //   Left column  – key icon + "No Sign up Required"
                //   Right column – shield icon + "Your clipboard stays private"
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

                        // Left column: key icon communicates zero-account, privacy-first onboarding.
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

                        // Right column: shield icon reinforces that clipboard data is end-to-end
                        // private and never logged or stored on a third-party server.
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

                // ── Animated app logo ─────────────────────────────────────────────────────
                // Two separate Animatables control the scale and alpha independently. Both
                // animate from 0 → 1 over 800 ms so the logo grows and fades in together.
                // Keeping them separate allows future fine-tuning of timing or easing for
                // each property without entangling them in a single AnimatedVisibility.
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

                // The SVG logo is decoded at runtime by Coil's SvgDecoder from the bundled
                // asset file. Using AsyncImage + SvgDecoder ensures the vector renders
                // crisply at any density without needing separate raster drawables.
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

                // ── Primary CTA button ────────────────────────────────────────────────────
                // Positioned directly below the feature card. The `scale` parameter forwards
                // the animated press value from LandingScreen for the squish feedback.
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
 * GetStartedButton is the primary call-to-action pill button displayed inside
 * [GlassmorphismCard] at the bottom of the landing screen.
 *
 * **Visual design** – the button is styled as a semi-transparent frosted-glass pill:
 * a 20 %-opaque white fill with a solid 1 dp white border and zero elevation, so it
 * blends naturally into the translucent card behind it while still reading as a distinct
 * tappable element. The "Get Started" label is rendered in a deep blue (`#1061AC`) with
 * tight negative letter-spacing to match the rest of the screen's typography.
 *
 * **Responsive sizing** – all metric values (width, height, font size, corner radius) are
 * calculated by multiplying design-canvas constants by the unified [sizeScale] factor and
 * then clamping to a `(min, max)` range. This ensures the button remains comfortably
 * tappable on small phones and never becomes oversized on large tablets.
 *
 * **Press animation** – the [scale] parameter is forwarded directly to the [Button]'s
 * `graphicsLayer` transform. Its value is driven externally by the [Animatable] in
 * [LandingScreen], producing a squish-to-80%-then-bounce-back effect on tap without any
 * additional state or coroutine inside this composable.
 *
 * @param modifier  Optional [Modifier] used by the caller to position the button within
 *                  a [Box] (e.g. `Modifier.align(Alignment.TopCenter).offset(y = …)`).
 * @param scale     The current animated scale factor (0 < scale ≤ 1) applied uniformly
 *                  to both `scaleX` and `scaleY` for the press feedback effect.
 * @param onClick   Invoked when the user taps the button; the caller is responsible for
 *                  initiating the exit animation and subsequent navigation.
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

    // Each dimension is scaled from the design-canvas baseline and clamped so the
    // button stays well within usable bounds on every supported device size.
    val buttonWidth = (180 * sizeScale).coerceIn(160f, 180f).dp
    val buttonHeight = (59 * sizeScale).coerceIn(48f, 59f).dp
    val fontSize = (26 * sizeScale).coerceIn(20f, 26f).sp
    val cornerRadius = (32 * sizeScale).coerceIn(24f, 32f).dp

    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = buttonWidth, height = buttonHeight)
            // Forward the externally driven scale value to graphicsLayer so the press
            // animation is applied uniformly to both axes without recomposing the button.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, Color.White),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.2f)  // 20 % white fill for the frosted-glass look
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
