package com.bunty.clipsync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bunty.clipsync.db.HistoryRepository
import android.text.format.DateUtils
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    // FIX: Remove the delay(100) + LaunchedEffect pattern that was causing a
    //      mid-transition layout jump. showContent is now immediately true so
    //      AnimatedVisibility blocks don't trigger a second pass during the
    //      parent AnimatedContent tab transition, eliminating the jitter.
    val showContent = true
    var selectedFilter by rememberSaveable { mutableStateOf("All") }

    val context = LocalContext.current
    val historyRepo = remember { HistoryRepository.getInstance(context) }
    val allHistory by historyRepo.allHistory.collectAsStateWithLifecycle()

    val recentSyncs = remember(allHistory) {
        allHistory.map { entity ->
            val iconResId = when (entity.type) {
                "OTP" -> R.raw.home_icon_otp
                "Links" -> R.raw.history_link
                "Screenshots" -> R.raw.home_icon_clipboard
                else -> R.raw.history_document
            }
            val timeAgo = DateUtils.getRelativeTimeSpanString(entity.timestamp).toString()
            RecentSyncItem(
                content = entity.content,
                direction = entity.direction,
                timeAgo = timeAgo,
                iconResId = iconResId,
                isSuccess = entity.isSuccess,
                type = entity.type
            )
        }
    }

    val filteredSyncs = remember(selectedFilter, recentSyncs) {
        if (selectedFilter == "All") recentSyncs else recentSyncs.filter { it.type == selectedFilter }
    }

    Box(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 72.dp + contentPadding.calculateTopPadding())
        ) {
            // ── Title ──
            Text(
                text = "History",
                textAlign = TextAlign.Start,
                fontSize = 48.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-0.03).em,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.27f),
                        offset = Offset(0f, 4f),
                        blurRadius = 42.3f
                    )
                ),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            )

            // FIX: AnimatedVisibility wraps filter chips + list together so the
            //      enter animation only fires when showContent flips. Since
            //      showContent = true from the start, the enter spec below is
            //      only used on the very first composition of this screen —
            //      it won't fight with the parent AnimatedContent transition
            //      because by the time the parent transition completes, this
            //      composable is already fully visible.
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(400)) + slideInHorizontally(
                    initialOffsetX = { 40 },
                    animationSpec = tween(400)
                )
            ) {
                Column {
                    // ── Filter Chips (Horizontal Scroll) ──
                    val filters = listOf("All", "Text", "OTP", "Links", "Screenshots")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        filters.forEach { filter ->
                            val isSelected = selectedFilter == filter
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9999.dp))
                                    .background(
                                        if (isSelected) Color(0xFF2E4FCF) else Color.White.copy(alpha = 0.4f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(9999.dp)
                                    )
                                    .clickable { selectedFilter = filter }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = filter,
                                    color = if (isSelected) Color.White else Color(0xFF1A1B23),
                                    fontSize = 14.sp,
                                    fontFamily = RobotoFontFamily
                                )
                            }
                        }
                    }

                    // ── History List (Glass Panels) ──
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 150.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(filteredSyncs) { item ->
                            HistoryItemCard(item = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(item: RecentSyncItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = Color.Black.copy(alpha = 0.05f),
                ambientColor = Color.Transparent
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.4f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.4f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(21.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                HistorySvgIcon(
                    rawResId = item.iconResId,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.content,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (item.type == "OTP") 18.sp else 16.sp,
                    color = Color(0xFF1A1B23),
                    letterSpacing = if (item.type == "OTP") 1.8.sp else 0.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.direction} • ${item.timeAgo}",
                    fontFamily = RobotoFontFamily,
                    fontSize = 14.sp,
                    color = Color(0xFF444654)
                )

                if (!item.isSuccess) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            HistorySvgIcon(
                                rawResId = R.raw.history_error,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Failed",
                                fontFamily = RobotoFontFamily,
                                fontSize = 12.sp,
                                color = Color(0xFFFF3B30)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clickable { /* Handle retry */ }
                        ) {
                            HistorySvgIcon(
                                rawResId = R.raw.history_retry,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "Retry",
                                fontFamily = RobotoFontFamily,
                                fontSize = 12.sp,
                                color = Color(0xFF2E4FCF)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySvgIcon(
    rawResId: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
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
        contentScale = ContentScale.Fit,
        colorFilter = colorFilter,
        modifier = modifier
    )
}
