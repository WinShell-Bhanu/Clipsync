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
 * MeshBackground renders an animated gradient mesh that fills the entire screen.
 * It is used as the root container for all screens in the app — every screen is
 * composited on top of this background.
 *
 * The animation is made of three large overlapping circles whose positions oscillate
 * over time using sine/cosine functions, creating a flowing "aurora" or "mesh gradient" effect.
 * A Gaussian blur (API 31+) is applied to smooth the circle edges, producing soft colour blending.
 *
 * @param modifier   Optional modifier forwarded to the root [Box].
 * @param onPulse    When `true`, the animation temporarily speeds up to 4× (e.g. after a QR scan).
 *                   Automatically reverts to normal speed after the caller sets it back to `false`.
 * @param isPaused   When `true`, animation is frozen at its current frame (saves battery when
 *                   the user is not on the landing screen or when the app is backgrounded).
 * @param content    The composable content to render on top of the background.
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

    // The three animated gradient colours
    val color1 = Color(0xFF91ACFD)   // soft periwinkle blue
    val color2 = Color(0xFF607DFE)   // deeper indigo
    val color3 = Color(0xFFDAFFFD).copy(alpha = 0.61f)  // pale aqua with partial transparency
    val baseColor = Color(0xFFB1C2F6) // solid fill behind the blurred circles

    // `time` increments each frame, driving the oscillation of all three circles
    var time by remember { mutableFloatStateOf(0f) }

    // Smoothly interpolate the animation speed based on the current state:
    //   isPaused → 0 (frozen), onPulse → 4× (fast), normal → 1×
    val targetSpeed = when {
        isPaused -> 0f
        onPulse -> 4f
        else -> 1f
    }

    val speed by animateFloatAsState(
        targetValue = targetSpeed,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "speed"
    )

    // Frame loop: increments `time` every frame proportional to the current speed.
    // Skips the increment when speed is effectively zero to avoid unnecessary recompositions.
    LaunchedEffect(Unit) {
        val startTime = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameTime ->
                if (speed > 0.01f) {
                    time += 0.008f * speed
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor) // solid base colour visible before / behind the blur
    ) {

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+: blur the entire canvas layer so circle edges blend into each other
                        renderEffect = RenderEffect
                                .createBlurEffect(
                                    80f, 80f,
                                    Shader.TileMode.MIRROR
                                )
                                .asComposeRenderEffect()
                    } else {
                        // Older devices: reduce opacity slightly instead of blurring
                        alpha = 0.9f
                    }
                }
        ) {
            // Circle 1: oscillates horizontally and vertically using cos/sin on `time`
            drawCircle(
                color = color1,
                radius = screenWidth * 1.0f,
                center = Offset(
                    x = screenWidth * 0.2f + (cos(time) * screenWidth * 0.3f),
                    y = screenHeight * 0.3f + (sin(time) * screenHeight * 0.2f)
                )
            )

            // Circle 2: moves in the opposite horizontal phase (negative multiplier)
            // for a counter-rotating feel
            drawCircle(
                color = color2,
                radius = screenWidth * 1.1f,
                center = Offset(
                    x = screenWidth * 0.8f + (cos(time * -0.8f) * screenWidth * 0.3f),
                    y = screenHeight * 0.7f + (sin(time * 0.5f) * screenHeight * 0.2f)
                )
            )

            // Circle 3: smaller, faster cycle (1.2× multiplier) – adds a subtle accent highlight
            drawCircle(
                color = color3,
                radius = screenWidth * 0.5f,
                center = Offset(
                    x = screenWidth * 0.5f + (sin(time * 1.2f) * screenWidth * 0.2f),
                    y = screenHeight * 0.5f + (cos(time) * screenHeight * 0.2f)
                )
            )
        }

        // Render the actual screen content on top of the background canvas
        content()
    }
}
