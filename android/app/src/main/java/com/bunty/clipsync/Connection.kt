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
 * ConnectionPage is shown immediately after a successful QR scan and Firestore pairing.
 *
 * It gives the user a visual confirmation that their device is now linked to a Mac, then
 * lets them continue to the permission setup screen.
 *
 * UI elements (all animated with staggered entrance):
 * 1. A typewriter-style title "You're Connected" that types itself character by character.
 * 2. A gradient subtitle showing the paired Mac's device name.
 * 3. A looping Lottie "Sync Data" animation in the center of the card.
 * 4. A circular continue button at the bottom to proceed to [PermissionPage].
 *
 * The whole screen fades + scales out before navigation so the transition feels smooth.
 *
 * @param onContinue Called when the user taps the continue button to go to [PermissionPage].
 * @param onUnpair   Called if the pairing needs to be cleared (e.g. error recovery).
 */
@Composable
fun ConnectionPage(
    onContinue: () -> Unit = {},
    onUnpair: () -> Unit = {}
) {
    val context = LocalContext.current

    // Paired Mac device name retrieved from SharedPreferences
    val pairedDeviceName = DeviceManager.getPairedMacDeviceName(context)

    val backgroundColor = Color(0xFFB1C2F6)
    val robotoFontFamily = FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal),
        Font(R.font.roboto_medium, FontWeight.Medium),
        Font(R.font.roboto_bold, FontWeight.Bold),
        Font(R.font.roboto_black, FontWeight.Black)
    )

    // Full title string – displayed character by character via the typewriter LaunchedEffect
    val fullText = "You're Connected"
    // displayedText grows from "" → fullText one character at a time
    var displayedText by remember { mutableStateOf("") }

    // isExiting drives the fade+scale-out transition before navigating
    var isExiting by remember { mutableStateOf(false) }
    // buttonScale is animated on tap to give the press a satisfying bouncy feel
    val buttonScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // Visibility flags for staggered entrance animations
    var showSubtitle by remember { mutableStateOf(false) }
    var isPlayingLottie by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        // Typewriter effect: reveal one character every 15 ms
        fullText.forEachIndexed { index, _ ->
            displayedText = fullText.substring(0, index + 1)
            delay(15)
        }
        delay(100)
        showSubtitle = true   // show the device name subtitle after the title finishes
        delay(100)
        isPlayingLottie = true // start the Lottie sync animation
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Wrap the content so the entire screen can fade+scale out before navigation
        AnimatedVisibility(
            visible = !isExiting,
            exit = fadeOut(animationSpec = tween(300)) +
                    scaleOut(targetScale = 0.9f, animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {

                // ── Main card ─────────────────────────────────────────────────
                // A semi-transparent rounded card centered on screen that holds
                // the title, subtitle, and Lottie animation.
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

                    // Typewriter title – characters are appended to displayedText in LaunchedEffect
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

                    // Subtitle: "You are now paired with <DeviceName>"
                    // Appears after the typewriter finishes, fades in with a slight scale spring
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
                            // Gradient text: cornflower blue → deep purple
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

                    // Lottie "Sync Data" animation – plays in a loop until the user continues
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

                // ── Continue button ───────────────────────────────────────────
                // A circular icon button at the bottom of the screen.
                // Tapping it plays a bounce animation, then triggers the exit transition
                // before calling onContinue.
                Button(
                    onClick = {
                        scope.launch {
                            // Press animation: squish down, then spring back up
                            buttonScale.animateTo(0.8f, animationSpec = tween(100))
                            buttonScale.animateTo(
                                1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                            )
                            delay(100)
                            isExiting = true   // trigger the screen exit animation
                            delay(300)
                            onContinue()       // navigate after the animation finishes
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
                        containerColor = Color.White.copy(alpha = 0.2f)  // frosted glass look
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
