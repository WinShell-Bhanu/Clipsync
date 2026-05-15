package com.bunty.clipsync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.bunty.clipsync.R

// ── Brand / Accent ──────────────────────────────────────────────────────────
val ActionBlue    = Color(0xFF0A84FF)
val StatusSuccess = Color(0xFF34C759)
val StatusWarning = Color(0xFFFF9500)
val StatusError   = Color(0xFFFF3B30)
val Secondary     = Color(0xFF9B59B6) // purple accent for images

// ── Background gradient endpoints (used in MeshBackground) ──────────────────
val GradientStart = Color(0xFFB1C2F6)
val GradientEnd   = Color(0xFF91ACFD)

// ── Glass card surfaces ──────────────────────────────────────────────────────
// Card bg: rgba(20, 30, 90, 0.50) → hex alpha 80 = 50%
val SurfaceGlass      = Color(0x80141E5A)
// Heavier card variant (action buttons)
val SurfaceGlassHeavy = Color(0xA0141E5A)
// Border: rgba(255,255,255,0.15)
val SurfaceGlassBorder = Color(0x26FFFFFF)

// ── Text colours (all white-based for white-on-glass) ───────────────────────
val TextPrimary   = Color(0xFFFFFFFF)                  // 100% white
val TextSecondary = Color(0xD9FFFFFF)                  // 85% white
val TextCaption   = Color(0xB3FFFFFF)                  // 70% white

// ── Material3 colour scheme (always dark because background is always the gradient) ──
private val ClipSyncColorScheme = darkColorScheme(
    primary             = ActionBlue,
    primaryContainer    = ActionBlue,
    onPrimaryContainer  = TextPrimary,
    secondary           = StatusSuccess,
    background          = Color(0xFF91ACFD),  // gradient fallback
    surface             = SurfaceGlass,
    onSurface           = TextPrimary,        // PRIMARY text → white
    onSurfaceVariant    = TextSecondary,      // SECONDARY text → white 85%
    error               = StatusError,
    outline             = SurfaceGlassBorder,
    surfaceVariant      = Color(0xFF606880)   // toggle off track
)

// ── Typography ───────────────────────────────────────────────────────────────
val RobotoFontFamily = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium,  FontWeight.Medium),
    Font(R.font.roboto_bold,    FontWeight.Bold),
    Font(R.font.roboto_black,   FontWeight.Black)
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily   = RobotoFontFamily,
        fontWeight   = FontWeight.Black,
        fontSize     = 40.sp,
        lineHeight   = 48.sp,
        letterSpacing = (-0.02).sp,
        color        = TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize   = 24.sp,
        lineHeight = 32.sp,
        color      = TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
        color      = TextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize   = 15.sp,
        lineHeight = 22.sp,
        color      = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize   = 13.sp,
        lineHeight = 18.sp,
        color      = TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily    = RobotoFontFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 11.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.08.sp,
        color         = TextCaption
    )
)

// ── Theme wrapper ─────────────────────────────────────────────────────────────
@Composable
fun ClipSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClipSyncColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
