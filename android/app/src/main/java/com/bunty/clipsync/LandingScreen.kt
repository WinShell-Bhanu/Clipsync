package com.bunty.clipsync

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlin.math.min

private const val LandingDesignWidth = 412f
private const val LandingDesignHeight = 915f

private val LandingCardColor = Color.White.copy(alpha = 0.80f)
private val LandingGlassBorder = Color.White.copy(alpha = 0.40f)
private val LandingChipColor = Color(0xFF070707).copy(alpha = 0.10f)
private val LandingTextColor = Color(0xFF1A1B23)
private val LandingTitleShadow = Color.Black.copy(alpha = 0.27f)

@Composable
fun LandingScreen(
    onGetStartedClick: () -> Unit = {}
) {
    LandingScreenContent(
        modifier = Modifier.fillMaxSize(),
        onGetStartedClick = onGetStartedClick
    )
}

@Composable
fun LandingPage2(
    onGetStartedClick: () -> Unit = {}
) {
    LandingScreen(onGetStartedClick = onGetStartedClick)
}

@Composable
private fun LandingScreenContent(
    modifier: Modifier = Modifier,
    onGetStartedClick: () -> Unit = {}
) {
    val visibleIndex = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        visibleIndex.intValue = 1
        kotlinx.coroutines.delay(40)
        visibleIndex.intValue = 2
        kotlinx.coroutines.delay(40)
        visibleIndex.intValue = 3
    }

    BoxWithConstraints(modifier = modifier) {
        val scale = min(
            maxWidth.value / LandingDesignWidth,
            maxHeight.value / LandingDesignHeight
        )

        fun dp(value: Float): Dp = (value * scale).dp
        fun sp(value: Float) = (value * scale).sp

        Box(
            modifier = Modifier
                .width(dp(LandingDesignWidth))
                .height(dp(LandingDesignHeight))
                .align(Alignment.TopCenter)
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = visibleIndex.intValue >= 1,
                enter = androidx.compose.animation.fadeIn(tween(250)) + androidx.compose.animation.slideInVertically(initialOffsetY = { 30 }, animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
            ) {
            LandingTitle(
                modifier = Modifier.fillMaxSize()
            )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = visibleIndex.intValue >= 2,
                enter = androidx.compose.animation.fadeIn(tween(250)) + androidx.compose.animation.slideInVertically(initialOffsetY = { 30 }, animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
            ) {
            LandingHeroVisual(
                modifier = Modifier.offset(x = dp(35f), y = dp(256f)),
                width = dp(342f),
                height = dp(308f)
            )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = visibleIndex.intValue >= 3,
                enter = androidx.compose.animation.fadeIn(tween(250)) + androidx.compose.animation.slideInVertically(initialOffsetY = { 30 }, animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
            ) {
            LandingFeatureCard(
                modifier = Modifier
                    .offset(x = dp(27f), y = dp(570f))
                    .size(width = dp(356f), height = dp(320f)),
                scale = scale,
                onClick = onGetStartedClick
            )
            }
        }
    }
}

@Composable
private fun LandingTitle(
    modifier: Modifier
) {
    Box(modifier = modifier) {
        LandingClipSyncTitle()
        LandingSubtitleSection()
    }
}

private val RobotoVariableFamily = FontFamily(Font(R.font.roboto_variable))

@Composable
private fun LandingClipSyncTitle() {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val heightScale = screenHeight.value / LandingDesignHeight
    val titleFontSize = (64 * heightScale).coerceIn(42f, 64f).sp
    val topPadding = (104 * heightScale).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "ClipSync",
            fontSize = titleFontSize,
            fontFamily = RobotoVariableFamily,
            fontWeight = FontWeight(910),
            letterSpacing = (-0.03f * 64).sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun LandingSubtitleSection() {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val heightScale = screenHeight.value / LandingDesignHeight
    val subtitleFontSize = (28 * heightScale).coerceIn(18f, 28f).sp
    val topPadding = (181 * heightScale).dp

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
            fontFamily = RobotoFontFamily,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
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
private fun LandingHeroVisual(
    modifier: Modifier,
    width: Dp,
    height: Dp
) {
    val heroScale = width.value / 342f

    Box(
        modifier = modifier.size(width = width, height = height),
        contentAlignment = Alignment.Center
    ) {
        LandingSvgAsset(
            rawResId = R.raw.landing_logo,
            contentDescription = "Landing Logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width = (286f * heroScale).dp, height = (258f * heroScale).dp)
        )
    }
}

@Composable
private fun LandingFeatureCard(
    modifier: Modifier,
    scale: Float,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape((32f * scale).dp)

    Box(
        modifier = modifier
            .shadow(
                elevation = (18f * scale).dp,
                shape = shape,
                ambientColor = Color(0xFF204399).copy(alpha = 0.16f),
                spotColor = Color(0xFF204399).copy(alpha = 0.20f)
            )
            .clip(shape)
            .background(LandingCardColor)
            .blur(0.dp)
            .border(width = (1f * scale).dp, color = LandingGlassBorder, shape = shape)
    ) {
        Text(
            text = "Copy text, OTPs, links, screenshots, and\ntransfer files to your Mac.",
            modifier = Modifier
                .offset(x = (21f * scale).dp, y = (21f * scale).dp)
                .width((314f * scale).dp),
            color = LandingTextColor,
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (16f * scale).sp,
            lineHeight = (26f * scale).sp,
            textAlign = TextAlign.Center
        )

        LandingFeatureChip(
            modifier = Modifier.offset(x = (16f * scale).dp, y = (93f * scale).dp),
            iconRes = R.raw.landing_bolt_boost,
            label = "Fast Transfer",
            iconOffsetY = (-2f * scale).dp,
            scale = scale
        )

        LandingFeatureChip(
            modifier = Modifier.offset(x = (188f * scale).dp, y = (93f * scale).dp),
            iconRes = R.raw.landing_encrypted,
            label = "Fully Encrypted",
            scale = scale
        )

        LandingFeatureChip(
            modifier = Modifier.offset(x = (102f * scale).dp, y = (149f * scale).dp),
            iconRes = R.raw.landing_account_circle_off,
            label = "No Sign-up",
            scale = scale
        )

        LandingGetStartedButton(
            modifier = Modifier.offset(x = (96f * scale).dp, y = (225f * scale).dp),
            scale = scale,
            onClick = onClick
        )
    }
}

@Composable
private fun LandingGetStartedButton(
    modifier: Modifier,
    scale: Float,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape((29f * scale).dp)
    Box(
        modifier = modifier
            .size(width = (164f * scale).dp, height = (58f * scale).dp)
            .shadow(
                elevation = (4f * scale).dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE2E7FF),
                        Color(0xFFC4CCFF)
                    )
                ),
                shape = shape
            )
            .border(
                width = (1f * scale).dp,
                color = Color.White.copy(alpha = 0.8f),
                shape = shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Get Started",
            color = Color(0xFF1A1A1A),
            fontSize = (26f * scale).sp,
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.02f * 26).sp
        )
    }
}

@Composable
private fun LandingFeatureChip(
    modifier: Modifier,
    iconRes: Int,
    label: String,
    scale: Float,
    iconOffsetY: Dp = 0.dp
) {
    Box(
        modifier = modifier
            .size(width = (152f * scale).dp, height = (44f * scale).dp)
            .clip(RoundedCornerShape((24f * scale).dp))
            .background(LandingChipColor),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            LandingSvgAsset(
                rawResId = iconRes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(Color(0xFF2E4FCF), BlendMode.SrcIn),
                modifier = Modifier
                    .offset(y = iconOffsetY)
                    .size((24f * scale).dp)
            )

            Spacer(modifier = Modifier.width((6f * scale).dp))

            Text(
                text = label,
                color = Color.Black,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = (15.5f * scale).sp,
                lineHeight = (20f * scale).sp,
                letterSpacing = (-0.48f * scale).sp,
                maxLines = 1
            )
        }
    }
}


@Composable
private fun LandingSvgAsset(
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
private fun LandingScreenPreview() {
    ClipSyncTheme {
        MeshBackground(
            modifier = Modifier.fillMaxSize(),
            isPaused = true
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent
            ) {
                LandingScreen()
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LandingScreenCompactPreview() {
    ClipSyncTheme {
        MeshBackground(
            modifier = Modifier.fillMaxSize(),
            isPaused = true
        ) {
            LandingScreen()
        }
    }
}
