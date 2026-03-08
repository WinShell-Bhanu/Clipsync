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


/**
 * A [Modifier] extension that draws a soft, canvas-based drop-shadow behind any
 * composable it is applied to. Unlike the built-in `Modifier.shadow`, this implementation
 * uses [drawBehind] + [drawIntoCanvas] + [Paint.setShadowLayer] and therefore works on
 * **all API levels** without requiring the elevation/shadow infrastructure introduced
 * later in the Modifier system.
 *
 * The shadow is rendered as a rounded rectangle that matches the composable's own
 * layout bounds. [Paint.setShadowLayer] handles both the blur spread and the pixel
 * offset in a single call; the paint's own fill colour is set to transparent so only
 * the shadow layer is visible — the composable's real background shows through unchanged.
 *
 * **Limitations** – [setShadowLayer] is only effective when hardware acceleration is
 * enabled (which is the default for View-backed Compose windows). On software-rendered
 * canvases the call is silently ignored by the framework.
 *
 * @param offsetX      Horizontal shadow offset in dp; positive shifts the shadow right.
 * @param offsetY      Vertical shadow offset in dp; positive shifts the shadow downward.
 * @param blurRadius   Shadow blur radius in dp; larger values produce a softer, wider shadow.
 * @param color        Shadow colour including the desired alpha for transparency control.
 * @param cornerRadius Corner radius of the rounded-rectangle shadow shape in dp; should
 *                     match the corner radius of the composable's own background shape.
 */
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

/**
 * QRScanScreen is the device-pairing screen where the user scans the QR code displayed
 * by the Mac-side ClipSync application to establish a Firestore-backed sync connection.
 *
 * **Screen layout (top → bottom):**
 *  1. **Header card** ("Pair With your Mac") – a rounded gradient card (blue→purple at
 *     30 % opacity) pinned near the top of the screen. It slides down from 100 px above
 *     while fading in over 600 ms. Once the card is visible a nested [AnimatedVisibility]
 *     fades in the title and instruction text with a slight additional delay.
 *  2. **QR scanner card** – a square frosted-glass card (35 % white alpha) with a soft
 *     [customDropShadow] that springs up from 200 px below its resting position. When the
 *     camera is inactive the card acts as an empty visual placeholder; when active it hosts
 *     [CameraQRScanner], which fills the card bounds with the live camera preview and QR
 *     detection overlay.
 *  3. **"Scan QR" pill button** – a glass-styled pill at the bottom of the screen that
 *     scales in from 80 % with a medium-bouncy spring. Tapping it sets `isCameraActive`
 *     to `true`, causing [CameraQRScanner] to be composed inside the scanner card.
 *  4. **Loading overlay** – a full-screen [Box] containing a Lottie animation
 *     (`Loading.lottie`) that is displayed while the scanned QR data is processed. While
 *     visible, all user input is blocked via a disabled transparent clickable interceptor.
 *
 * **Staggered entrance animation** – four boolean flags (`showTopCard`, `showContent`,
 * `showQR`, `showButton`) are flipped to `true` in sequence with 100–200 ms inter-step
 * delays inside a single [LaunchedEffect], producing a cascading reveal effect that draws
 * the user's attention through the screen hierarchy naturally.
 *
 * **QR scan → loading flow:**
 *  1. The user taps "Scan QR" → `isCameraActive = true` → [CameraQRScanner] is composed.
 *  2. A QR code is detected → `onQRCodeScanned` fires → `isCameraActive = false`,
 *     `scannedData` is saved, `isLoading = true`.
 *  3. The loading [LaunchedEffect] detects `isLoading == true`, plays the Lottie animation
 *     from the start to completion, then invokes [onQRScanned] with the raw QR string.
 *     The single-cycle animation play gives the pairing Firestore call time to resolve
 *     before the caller navigates away.
 *
 * **Responsive scaling** – all dimension constants are multiplied by independent
 * `widthScale` / `heightScale` factors derived from the 360×800 dp design reference, then
 * clamped to `(min, max)` ranges so every element stays usable across compact phones and
 * larger form factors.
 *
 * @param initialCameraActive When `true` the camera starts scanning immediately on
 *                            composition without requiring the user to tap "Scan QR".
 *                            Used when navigating from a Re-pair flow with `startCamera=true`.
 * @param onQRScanned         Invoked with the raw QR-code string once a code is scanned
 *                            and the loading animation has completed. The caller is
 *                            responsible for parsing the data and triggering navigation.
 */
@Composable
fun QRScanScreen(
    initialCameraActive: Boolean = false,
    onQRScanned: (String) -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    // Scale factors relative to the 360×800 design reference for this screen
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

    // The camera feed stays dormant until the user explicitly taps "Scan QR",
    // avoiding unnecessary camera permission prompts on first open.
    var isCameraActive by remember { mutableStateOf(initialCameraActive) }
    // Shown while the scanned QR payload is being processed (Firestore pairing call).
    var isLoading by remember { mutableStateOf(false) }
    // Holds the raw string extracted from the scanned QR code for forwarding to the caller.
    var scannedData by remember { mutableStateOf("") }

    // Lottie composition loaded from the bundled asset file; displayed during loading.
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("Loading.lottie"))
    val lottieAnimatable = rememberLottieAnimatable()

    // Boolean gates for each staggered entrance layer. They are sequentially set to
    // `true` in LaunchedEffect below to create the cascading reveal animation.
    var showTopCard by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    var showQR by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    // Stagger the four entrance layers with 100–200 ms delays so each element arrives
    // visually after the one above it, guiding the user's attention top-to-bottom.
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

    // When `isLoading` transitions to true, play the Lottie animation from the start.
    // The animation runs for one full cycle before `onQRScanned` fires, giving the
    // caller-side Firestore pairing call time to execute during the loading feedback.
    LaunchedEffect(isLoading) {
        if (isLoading) {
            lottieAnimatable.animate(
                composition = composition,
                initialProgress = 0f
            )
            onQRScanned(scannedData)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // ── Pre-calculated dimensions ─────────────────────────────────────────────────
        val topCardOffsetY = (90 * heightScale).dp
        val topCardHeight = (240 * heightScale).dp
        val cornerRadius = (32 * scale).coerceIn(20f, 32f).dp

        // Font metrics for the header card title and instruction subtitle.
        val titleFontSize = (58 * scale).coerceIn(36f, 58f).sp
        val titleLineHeight = (54 * scale).coerceIn(34f, 54f).sp
        val subtitleFontSize = (24 * scale).coerceIn(16f, 24f).sp
        val subtitleLineHeight = (28 * scale).coerceIn(18f, 28f).sp
        val contentPadding = (40 * scale).dp

        // The QR scanner card is square and capped at 90 % of the screen width
        // or 352 dp, whichever is smaller, so it never overflows narrow screens.
        val qrCardSize = (min(screenWidthDp.value * 0.9f, 352f)).dp
        val qrCardOffsetY = (370 * heightScale).dp

        // "Scan QR" pill button dimensions and bottom offset.
        val buttonWidth = (161 * scale).coerceIn(130f, 161f).dp
        val buttonHeight = (59 * scale).coerceIn(48f, 59f).dp
        val buttonFontSize = (26 * scale).coerceIn(18f, 26f).sp
        val buttonBottomOffset = (-40).dp

        // ── HEADER CARD (slides down from above) ─────────────────────────────────────
        // The card bleeds 30 dp off both horizontal edges so its rounded corners are
        // hidden, giving the appearance of a flat banner at the top of the viewport.
        // A nested AnimatedVisibility fades in the text body with a second delay so the
        // card structure appears before the words materialise inside it.
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

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showContent,
                        enter = androidx.compose.animation.fadeIn(tween(800))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = contentPadding, vertical = (10 * heightScale).dp)
                        ) {
                            // Two-line bold title — the primary hierarchy anchor for the screen.
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

                            // Instruction subtitle rendered with a blue→purple gradient brush
                            // to visually distinguish it from the solid-white title above.
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

        // ── QR SCANNER CARD (springs up from below) ───────────────────────────────────
        // Positioned in the centre of the screen below the header card. When the camera
        // is inactive the card is an empty frosted-glass placeholder. Once `isCameraActive`
        // is set to `true` by the button tap, CameraQRScanner fills the card and begins
        // decoding frames for a QR code.
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

                if (isCameraActive) {
                    // Camera active: CameraQRScanner fills the entire card and delivers
                    // decoded QR strings via `onQRCodeScanned`. On first decode the camera
                    // is stopped and the loading overlay is displayed.
                    CameraQRScanner(
                        onQRCodeScanned = { qrData ->
                            // Disable the scanner immediately to prevent duplicate callbacks,
                            // store the raw payload, and trigger the loading overlay.
                            isCameraActive = false
                            scannedData = qrData
                            isLoading = true
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(cornerRadius))
                    )
                }
                // Camera inactive: card renders as an empty frosted-glass placeholder,
                // visually indicating where the camera preview will appear.
            }
        }

        // ── SCAN QR BUTTON (scales in with a bounce) ──────────────────────────────────
        // Fixed to the bottom of the screen. Scales in from 80 % with a medium-bouncy
        // spring to give the button a lively, inviting entrance. Tapping sets
        // `isCameraActive = true` which causes CameraQRScanner to be composed above.
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
                        // Activating the camera here causes CameraQRScanner to be composed
                        // inside the scanner card on the next recomposition.
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

        // ── LOADING OVERLAY ───────────────────────────────────────────────────────────
        // Covers the full screen with a centred Lottie animation while the pairing
        // request is in flight. A disabled transparent clickable interceptor blocks all
        // accidental taps on the UI underneath during this window.
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        enabled = false   // Prevent any input from reaching the UI beneath the overlay.
                    ) {},
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
