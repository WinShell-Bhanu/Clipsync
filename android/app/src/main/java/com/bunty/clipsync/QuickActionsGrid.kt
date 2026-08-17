package com.bunty.clipsync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun QuickActionsGrid(
    onSendFileClick: () -> Unit,
    onStartHotspotClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickActionButton(
            text = "Send\nFile",
            icon = Icons.Default.Description,
            iconTint = ActionBlue,
            onClick = onSendFileClick,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            text = "Start Hotspot\nDirect",
            icon = Icons.Default.CellTower,
            iconTint = StatusWarning,
            onClick = onStartHotspotClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickActionButton(
    text: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceGlassHeavy)
            .border(1.dp, SurfaceGlassBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text.replace("\n", " "),
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = text,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }
}
