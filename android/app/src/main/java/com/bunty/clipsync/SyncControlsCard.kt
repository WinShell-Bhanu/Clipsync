package com.bunty.clipsync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SyncControlsCard(
    syncToMac: Boolean,
    onSyncToMacChange: (Boolean) -> Unit,
    syncFromMac: Boolean,
    onSyncFromMacChange: (Boolean) -> Unit,
    autoSyncOTPs: Boolean,
    onAutoSyncOTPsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceGlass)
            .border(1.dp, SurfaceGlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            // Section header
            Text(
                text = "Clipboard Sync Rules",
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            SyncToggleRow(
                title = "Sync to Mac",
                subtitle = "Send copied items to Mac",
                icon = Icons.Default.ArrowUpward,
                iconTint = StatusSuccess,
                checked = syncToMac,
                onCheckedChange = onSyncToMacChange,
                checkedTrackColor = StatusSuccess
            )

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.padding(vertical = 2.dp)
            )

            SyncToggleRow(
                title = "Sync from Mac",
                subtitle = "Receive copied items",
                icon = Icons.Default.ArrowDownward,
                iconTint = StatusSuccess,
                checked = syncFromMac,
                onCheckedChange = onSyncFromMacChange,
                checkedTrackColor = StatusSuccess
            )

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.padding(vertical = 2.dp)
            )

            SyncToggleRow(
                title = "Auto-Sync OTPs",
                subtitle = "Detect and send verification codes",
                icon = Icons.Default.Password,
                iconTint = ActionBlue,
                checked = autoSyncOTPs,
                onCheckedChange = onAutoSyncOTPsChange,
                checkedTrackColor = ActionBlue
            )
        }
    }
}

@Composable
private fun SyncToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedTrackColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor   = checkedTrackColor,
                checkedThumbColor   = Color.White,
                uncheckedTrackColor = Color(0xFFC0C0C0).copy(alpha = 0.4f),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
