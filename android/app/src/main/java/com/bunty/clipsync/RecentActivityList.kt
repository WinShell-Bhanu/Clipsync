package com.bunty.clipsync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ActivityItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconTint: Color,
    val isMono: Boolean = false
)

@Composable
fun RecentActivityList(modifier: Modifier = Modifier) {
    val items = listOf(
        ActivityItem(
            title    = "https://designsystem.apple.com/...",
            subtitle = "Just now • Sent to Mac",
            icon     = Icons.Default.TextFields,
            iconTint = ActionBlue
        ),
        ActivityItem(
            title    = "748 291",
            subtitle = "2 mins ago • Auto-synced",
            icon     = Icons.Default.Key,
            iconTint = StatusWarning,
            isMono   = true
        ),
        ActivityItem(
            title    = "Screenshot_2023-10-25.png",
            subtitle = "15 mins ago • Received from Mac",
            icon     = Icons.Default.Image,
            iconTint = Secondary
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceGlass)
            .border(1.dp, SurfaceGlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            // Header row with "View All" link
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Activity",
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Text(
                    text = "View All",
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = ActionBlue,          // #0A84FF per spec
                    modifier = Modifier.clickable { /* TODO */ }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item -> ActivityRow(item) }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { /* TODO */ }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(item.iconTint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontFamily = if (item.isMono) FontFamily.Monospace else RobotoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
        // Success checkmark — always green per spec
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            tint = StatusSuccess,
            modifier = Modifier.size(20.dp)
        )
    }
}
