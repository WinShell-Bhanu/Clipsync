package com.bunty.clipsync

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.withFrameNanos

/**
 * A full-screen animated background that produces a flowing "mesh gradient" or "aurora" effect
 * by drawing three large, overlapping, blurred circles whose positions oscillate continuously
 * using sine and cosine functions driven by a shared time variable.
 *
 * Every screen in the ClipSync app is rendered as [content] on top of this composable so that
 * the living background persists uninterrupted across all navigation transitions.
 *
 * Visual construction:
 *  - A solid [baseColor] fills the root [Box], acting as the visible canvas colour both behind
 *    the blurred circles on API 31+ and as the fallback on older devices.
 *  - Three filled circles — soft periwinkle blue, deep indigo, and semi-transparent pale aqua —
 *    are drawn on a [Canvas] layer. Each has a distinct radius and an independent oscillation
 *    formula derived from [time], so they never move in perfect unison.
 *  - On Android 12+ (API 31) a hardware-accelerated Gaussian blur (`RenderEffect`) is applied to
 *    the entire canvas at 80 dp, dissolving the hard circle edges and blending their colours
 *    where they overlap to produce the characteristic soft gradient look. On older API levels
 *    the canvas opacity is reduced slightly as a graceful fallback.
 *
 * Animation control:
 *  - [isPaused]: freezes the [time] counter so the background stops moving entirely. Used to
 *    conserve CPU/GPU on screens that do not benefit from a live animated background.
 *  - [onPulse]: causes the effective speed to jump to 4× normal for a dramatic burst (e.g.
 *    immediately after the user initiates a QR scan). Transitions in and out of pulse speed are
 *    smoothed over 1 000 ms with a linear interpolation so the change never feels jarring.
 *
 * @param modifier   Modifier applied to the outermost [Box]; typically [Modifier.fillMaxSize].
 * @param onPulse    Pass `true` briefly to accelerate the animation to 4× speed on user action.
 * @param isPaused   Pass `true` to freeze the animation entirely and conserve battery/CPU.
 * @param content    The composable subtree to render layered on top of the animated background.
 */
@Composable
fun MeshBackground(
    modifier: Modifier = Modifier,
    onPulse: Boolean = false,
    isPaused: Boolean = false,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }

    // Palette used for the three animated circles and the solid base layer behind them.
    val color1 = Color(0xFF91ACFD)                      // soft periwinkle blue
    val color2 = Color(0xFF607DFE)                      // deep indigo
    val color3 = Color(0xFFDAFFFD).copy(alpha = 0.61f)  // pale aqua, semi-transparent accent
    val baseColor = Color(0xFFB1C2F6)                   // solid fill visible behind/under the blur

    // Monotonically increasing time value fed into the sine/cosine position functions.
    // Incremented each vsync frame by an amount proportional to the current speed multiplier.
    var time by remember { mutableFloatStateOf(0f) }

    // Resolve the desired animation speed from the current mode:
    //   isPaused  → 0.0  (counter frozen, animation stationary)
    //   onPulse   → 4.0  (four times normal speed for a dramatic burst)
    //   default   → 1.0  (steady background motion)
    val targetSpeed = when {
        isPaused -> 0f
        onPulse -> 4f
        else -> 1f
    }

    // Animates transitions between speed levels over 1 000 ms with a linear easing curve,
    // preventing jarring jumps when the caller toggles isPaused or onPulse.
    val speed by animateFloatAsState(
        targetValue = targetSpeed,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "speed"
    )

    // Frame loop: advances `time` on every display vsync by an amount proportional to `speed`.
    // The threshold guard (speed > 0.01f) skips the state write when the animation is effectively
    // paused, avoiding unnecessary recompositions and Canvas redraws when nothing is changing.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                if (speed > 0.01f) {
                    time += 0.008f * speed
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor) // solid base colour visible behind the blurred circle layer
    ) {

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12+: apply a large Gaussian blur to the whole canvas layer so
                        // hard circle edges dissolve and the colours merge into each other.
                        // MIRROR tile mode prevents dark fringing artefacts at the screen edges.
                        renderEffect = RenderEffect
                                .createBlurEffect(
                                    80f, 80f,
                                    Shader.TileMode.MIRROR
                                )
                                .asComposeRenderEffect()
                    } else {
                        // Pre-Android 12 fallback: RenderEffect is unavailable, so lower the
                        // canvas layer opacity slightly to soften the circle edges by blending
                        // them partially with the solid base colour underneath.
                        alpha = 0.9f
                    }
                }
        ) {
            // Circle 1 — periwinkle blue, radius equal to screen width.
            // Drifts left/right via cosine and up/down via sine, anchored near the top-left quadrant.
            drawCircle(
                color = color1,
                radius = screenWidth * 1.0f,
                center = Offset(
                    x = screenWidth * 0.2f + (cos(time) * screenWidth * 0.3f),
                    y = screenHeight * 0.3f + (sin(time) * screenHeight * 0.2f)
                )
            )

            // Circle 2 — deep indigo, slightly larger than circle 1.
            // A negative time multiplier on the horizontal axis makes it counter-phase to circle 1,
            // creating organic, non-repetitive motion between the two large colour masses.
            drawCircle(
                color = color2,
                radius = screenWidth * 1.1f,
                center = Offset(
                    x = screenWidth * 0.8f + (cos(time * -0.8f) * screenWidth * 0.3f),
                    y = screenHeight * 0.7f + (sin(time * 0.5f) * screenHeight * 0.2f)
                )
            )

            // Circle 3 — semi-transparent pale aqua, half the screen width.
            // A faster oscillation frequency (1.2×) and smaller radius give it the appearance
            // of a bright highlight drifting over the two larger colour masses below it.
            drawCircle(
                color = color3,
                radius = screenWidth * 0.5f,
                center = Offset(
                    x = screenWidth * 0.5f + (sin(time * 1.2f) * screenWidth * 0.2f),
                    y = screenHeight * 0.5f + (cos(time) * screenHeight * 0.2f)
                )
            )
        }

        // Composites the caller's screen content on top of the blurred canvas layer.
        // Because it sits outside the Canvas scope it is unaffected by the blur RenderEffect.
        content()
    }
}
