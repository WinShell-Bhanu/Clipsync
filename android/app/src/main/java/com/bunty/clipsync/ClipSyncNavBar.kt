package com.bunty.clipsync

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Data model ───────────────────────────────────────────────────────────────

data class ClipSyncNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)

// ─── Main floating navbar ──────────────────────────────────────────────────────

@Composable
fun ClipSyncNavBar(
    items: List<ClipSyncNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceGlass)                           // your existing glass color
                .border(1.dp, SurfaceGlassBorder, RoundedCornerShape(20.dp))
                .padding(vertical = 8.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                ClipSyncNavBarTab(
                    item = item,
                    isSelected = selectedIndex == index,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onItemSelected(index)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ─── Individual animated tab ───────────────────────────────────────────────────

@Composable
private fun ClipSyncNavBarTab(
    item: ClipSyncNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ── Pill width: expands from icon-only (48dp) to icon+label (140dp) ──────
    val pillWidth by animateDpAsState(
        targetValue = if (isSelected) 140.dp else 52.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pill_width"
    )

    // ── Background alpha: glass highlight fades into pill when selected ───────
    val bgAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "bg_alpha"
    )

    // ── Label alpha: fades in when selected ──────────────────────────────────
    val labelAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 180, delayMillis = 60, easing = LinearOutSlowInEasing),
        label = "label_alpha"
    )

    // ── Icon tint color ───────────────────────────────────────────────────────
    val iconTint = if (isSelected) TextPrimary else TextCaption   // white vs 70% white

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(pillWidth)
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                // Selected pill: ActionBlue highlight inside the glass bar
                .background(
                    if (isSelected)
                        ActionBlue.copy(alpha = 0.25f * bgAlpha)
                    else
                        Color.Transparent
                )
                .border(
                    width = if (isSelected) 1.dp else 0.dp,
                    color = if (isSelected) ActionBlue.copy(alpha = 0.40f * bgAlpha) else Color.Transparent,
                    shape = RoundedCornerShape(14.dp)
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Icon(
                    imageVector = if (isSelected) item.selectedIcon else item.icon,
                    contentDescription = item.label,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
                // Label only renders when selected so its space is taken by pillWidth animation
                if (isSelected) {
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = item.label,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = TextPrimary.copy(alpha = labelAlpha),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
