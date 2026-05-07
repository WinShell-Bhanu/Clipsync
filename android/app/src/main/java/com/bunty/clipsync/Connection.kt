package com.bunty.clipsync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import com.airbnb.lottie.compose.*

/**
 * Confirmation screen displayed immediately after a successful QR-scan pairing with a Mac.
 *
 * The screen serves as a celebratory handshake moment — it confirms the connection visually
 * and lets the user advance to [PermissionPage] to grant the required runtime permissions.
 *
 * Animation sequence (staggered entrance):
 *  1. The heading "You're Connected" types itself out character by character (typewriter effect).
 *  2. A gradient subtitle naming the paired Mac device fades and spring-scales into view.
 *  3. A looping Lottie "Sync Data" animation fades in at the center of the card.
 *  4. A circular continue button is visible at the bottom throughout.
 *
 * On tap, the continue button plays a press-bounce animation, then the entire screen
 * fades and scales out before [onContinue] is invoked, producing a smooth exit transition.
 *
 * The FCM token is registered in the background after the screen appears so the device
 * can receive push notifications from the paired Mac going forward.
 *
 * @param onContinue Callback invoked after the exit animation completes; should navigate
 *                   the user to [PermissionPage].
 * @param onUnpair   Callback for clearing the current pairing — reserved for error-recovery
 *                   flows and not currently wired to any UI element on this screen.
 */
@Composable
fun ConnectionPage(
    onContinue: () -> Unit = {},
    onUnpair: () -> Unit = {}
) {
    val context = LocalContext.current

    // Retrieve the display name of the Mac that was just paired, stored in SharedPreferences
    // by the QR-scan flow. Shown in the gradient subtitle below the title.
    val pairedDeviceName = DeviceManager.getPairedMacDeviceName(context)

    val backgroundColor = Color(0xFFB1C2F6)
    val robotoFontFamily = FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal),
        Font(R.font.roboto_medium, FontWeight.Medium),
        Font(R.font.roboto_bold, FontWeight.Bold),
        Font(R.font.roboto_black, FontWeight.Black)
    )

    // The complete heading string that will be typed out one character at a time.
    val fullText = "You're Connected"
    // Starts empty and grows one character every 15 ms inside the LaunchedEffect below.
    var displayedText by remember { mutableStateOf("") }

    // When true, triggers the combined fade + scale-out that plays before navigating away.
    var isExiting by remember { mutableStateOf(false) }
    // Drives the spring-bounce press animation on the continue button.
    val buttonScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // Gates for the staggered entrance of secondary UI elements.
    var showSubtitle by remember { mutableStateOf(false) }
    var isPlayingLottie by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        // Typewriter effect: reveal the heading one character at a time at 15 ms per character.
        fullText.forEachIndexed { index, _ ->
            displayedText = fullText.substring(0, index + 1)
            delay(15)
        }
        delay(100)
        showSubtitle = true   // fade-in the paired device name once the title is fully typed
        delay(100)
        isPlayingLottie = true // begin the Lottie sync loop after the subtitle appears

        // Register the FCM token in the background so push notifications work immediately.
        // M7 fix: use the composable's coroutineScope (tied to the screen lifecycle)
        // instead of GlobalScope to avoid leaking the coroutine if the user navigates away.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            FCMTokenManager.registerFCMToken(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // AnimatedVisibility wraps the entire screen content so the whole UI is dismissed with
        // a single coordinated fade + scale-out before the navigation callback fires.
        AnimatedVisibility(
            visible = !isExiting,
            exit = fadeOut(animationSpec = tween(300)) +
                    scaleOut(targetScale = 0.9f, animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {

                // ── Central card ──────────────────────────────────────────────────────────
                // A frosted-glass rounded rectangle that groups the title, device subtitle,
                // and the Lottie animation. Offset slightly upward to leave room for the
                // continue button below without overlap.
                Box(
                    modifier = Modifier
                        .width(370.dp)
                        .height(440.dp)
                        .align(Alignment.Center)
                        .offset(y = (-20).dp)
                        .background(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(32.dp)
                        )
                ) {

                    // Heading: grows one character at a time via the typewriter LaunchedEffect.
                    // Uses the heaviest Roboto weight for maximum visual impact.
                    Text(
                        text = displayedText,
                        fontFamily = robotoFontFamily,
                        fontWeight = FontWeight.Black,
                        fontSize = 32.sp,
                        letterSpacing = (-0.03).em,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 60.dp)
                    )

                    // Subtitle naming the paired Mac. Enters with a fade + low-bouncy spring scale
                    // to reinforce the feeling of something snapping into place. Rendered with a
                    // cornflower-blue-to-deep-purple linear gradient to distinguish it visually
                    // from the white heading above it.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSubtitle,
                        enter = androidx.compose.animation.fadeIn(tween(500)) +
                                androidx.compose.animation.scaleIn(initialScale = 0.9f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 110.dp)
                    ) {

                        Text(
                            text = "You are now paired with $pairedDeviceName",
                            fontFamily = robotoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 22.sp,
                            letterSpacing = (-0.03).em,
                            lineHeight = 26.sp,
                            textAlign = TextAlign.Center,
                            // Horizontal gradient: cornflower blue (#3F96E2) → deep purple (#6C45BA)
                            style = TextStyle(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF3F96E2),
                                        Color(0xFF6C45BA)
                                    )
                                )
                            ),
                            modifier = Modifier.width(331.dp)
                        )
                    }

                    // Lottie "Sync Data" animation that loops indefinitely until the user taps
                    // continue. Fades in after the subtitle to complete the staggered entrance.
                    // Provides a dynamic visual cue that the devices are actively synchronised.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isPlayingLottie,
                        enter = androidx.compose.animation.fadeIn(tween(500)),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 80.dp)
                    ) {
                        val composition by rememberLottieComposition(LottieCompositionSpec.Asset("Sync Data.lottie"))
                        val progress by animateLottieCompositionAsState(
                            composition = composition,
                            iterations = LottieConstants.IterateForever,
                            isPlaying = true
                        )

                        LottieAnimation(
                            composition = composition,
                            progress = { progress },
                            modifier = Modifier.size(250.dp)
                        )
                    }
                }

                // ── Continue button ───────────────────────────────────────────────────────
                // A circular frosted-glass button anchored at the bottom of the screen.
                // Tapping triggers a three-phase sequence:
                //   1. The button squishes to 80 % of its size (tactile press feedback).
                //   2. It springs back to full size with a medium-bouncy spring.
                //   3. isExiting flips to true, kicking off the full-screen fade + scale-out,
                //      after which onContinue() navigates the user to PermissionPage.
                Button(
                    onClick = {
                        scope.launch {
                            // Phase 1: squish down to give tactile press feedback.
                            buttonScale.animateTo(0.8f, animationSpec = tween(100))
                            // Phase 2: spring back up with a satisfying bounce.
                            buttonScale.animateTo(
                                1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                            )
                            delay(100)
                            isExiting = true   // phase 3: kick off the full-screen exit animation
                            delay(300)         // wait for the 300 ms tween to complete
                            onContinue()       // navigate to PermissionPage
                        }
                    },
                    modifier = Modifier
                        .size(70.dp)
                        .align(Alignment.BottomCenter)
                        .offset(y = (-40).dp)
                        .graphicsLayer {
                            scaleX = buttonScale.value
                            scaleY = buttonScale.value
                        }
                        .border(
                            width = 1.dp,
                            color = Color.White,
                            shape = CircleShape
                        ),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.2f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.resume),
                        contentDescription = "Resume/Continue",
                        modifier = Modifier.size(30.dp),
                        tint = Color.Black
                    )
                }
            }
        }
    }
}
