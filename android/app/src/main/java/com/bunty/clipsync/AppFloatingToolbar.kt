package com.bunty.clipsync

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.*
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun LiquidGlassNavBar(
    modifier: Modifier = Modifier,
    items: List<ToolbarItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    val density = LocalDensity.current
    var itemWidths by remember { mutableStateOf(List(items.size) { 0.dp }) }
    var itemOffsets by remember { mutableStateOf(List(items.size) { 0.dp }) }

    // Sliding Pill state – reads measured tab positions
    val targetWidth = if (selectedIndex in items.indices) itemWidths[selectedIndex] else 0.dp
    val targetOffset = if (selectedIndex in items.indices) itemOffsets[selectedIndex] else 0.dp

    val pillWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "pillWidth"
    )
    val pillOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "pillOffset"
    )

    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(percent = 50),
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.05f)
            )
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xFFE8EDF5).copy(alpha = 0.9f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.6f),
                shape = RoundedCornerShape(percent = 50)
            )
            .padding(8.dp)
    ) {
        // Sliding blue pill indicator
        if (targetWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = pillOffset)
                    .width(pillWidth)
                    .height(48.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF007AFF))
            )
        }

        // Tabs Row – rendered on top of the pill
        Row(
            modifier = Modifier.wrapContentWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = selectedIndex == index

                // Lottie animation: play once forward when tab is selected
                val lottieComposition by rememberLottieComposition(
                    LottieCompositionSpec.RawRes(item.lottieResId)
                )
                var isPlaying by rememberSaveable { mutableStateOf(false) }
                val lottieProgress by animateLottieCompositionAsState(
                    composition = lottieComposition,
                    isPlaying = isPlaying,
                    iterations = 1,
                    restartOnPlay = true
                )

                // Trigger animation once when this tab becomes selected
                LaunchedEffect(isSelected) {
                    if (isSelected) {
                        isPlaying = false
                        isPlaying = true
                    }
                }
                // Stop after animation completes
                LaunchedEffect(lottieProgress) {
                    if (lottieProgress == 1f) isPlaying = false
                }

                // Label width: expands when selected
                val labelWidth by animateDpAsState(
                    targetValue = if (isSelected) 60.dp else 0.dp,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
                    label = "labelWidth"
                )

                // Icon tint: white when selected, muted dark when not
                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else Color(0xFF4A4E69),
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    label = "iconTint"
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            if (itemWidths.size == items.size) {
                                val widthDp = with(density) { coords.size.width.toDp() }
                                val offsetDp = with(density) { coords.positionInParent().x.toDp() }
                                val newWidths = itemWidths.toMutableList()
                                val newOffsets = itemOffsets.toMutableList()
                                if (newWidths[index] != widthDp || newOffsets[index] != offsetDp) {
                                    newWidths[index] = widthDp
                                    newOffsets[index] = offsetDp
                                    itemWidths = newWidths
                                    itemOffsets = newOffsets
                                }
                            }
                        }
                        .height(48.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            item.onClick()
                            if (!isSelected) onItemSelected(index)
                        }
                        .padding(horizontal = 16.dp)
                ) {
                    LottieAnimation(
                        composition = lottieComposition,
                        progress = { lottieProgress },
                        modifier = Modifier.size(26.dp),
                        dynamicProperties = rememberLottieDynamicProperties(
                            rememberLottieDynamicProperty(
                                property = LottieProperty.COLOR,
                                value = android.graphics.Color.parseColor(
                                    if (isSelected) "#FFFFFF" else "#4A4E69"
                                ),
                                keyPath = arrayOf("**")
                            ),
                            rememberLottieDynamicProperty(
                                property = LottieProperty.STROKE_COLOR,
                                value = android.graphics.Color.parseColor(
                                    if (isSelected) "#FFFFFF" else "#4A4E69"
                                ),
                                keyPath = arrayOf("**")
                            )
                        )
                    )

                    if (labelWidth > 5.dp) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.label,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.width(labelWidth)
                        )
                    }
                }
            }
        }
    }
}
