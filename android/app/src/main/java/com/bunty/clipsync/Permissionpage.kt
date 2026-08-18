package com.bunty.clipsync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable

data class PermissionItem(
    val title: String,
    val description: String,
    val iconResId: Int
)

@Composable
fun PermissionPageScreen(modifier: Modifier = Modifier, onFinishSetup: () -> Unit = {}) {
    val permissions = listOf(
        PermissionItem("Accessibility", "Detect when you copy text to sync it automatically.", R.raw.accessibility_new),
        PermissionItem("Notifications", "Get alerts when sync completes or if there's an error.", R.raw.notifications_active),
        PermissionItem("Display Over Apps", "Detect copy events (when user copies something)", R.raw.displayoverapps),
        PermissionItem("Notification Access", "Read notifications to auto-sync OTPs and alerts from apps.", R.raw.notificationaccess),
        PermissionItem("SMS Access", "Read SMS messages to capture OTPs that apps hide from notifications.", R.raw.notificationaccess),
        PermissionItem("Battery Optimization", "Disable battery restrictions so sync works in the background.", R.raw.batteryoptimiaztion)
    )

    val context = LocalContext.current
    val checkedStates = remember { mutableStateListOf(*Array(permissions.size) { false }) }
    
    // Permission launchers
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean -> checkedStates[1] = isGranted }
    )

    val smsLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            checkedStates[4] = permissions[android.Manifest.permission.RECEIVE_SMS] == true &&
                               permissions[android.Manifest.permission.READ_SMS] == true
        }
    )
    

    LaunchedEffect(Unit) {
        while(true) {
            checkedStates[0] = PermissionHelper.isAccessibilityEnabled(context)
            checkedStates[1] = PermissionHelper.isNotificationsEnabled(context)
            checkedStates[2] = PermissionHelper.isOverlayEnabled(context)
            checkedStates[3] = PermissionHelper.isNotificationAccessEnabled(context)
            checkedStates[4] = PermissionHelper.isSmsPermissionEnabled(context)
            checkedStates[5] = PermissionHelper.isBatteryOptimizationIgnored(context)
            delay(1000)
        }
    }

    val activeStepIndex = checkedStates.indexOfFirst { !it }.takeIf { it >= 0 } ?: permissions.size

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp)
        ) {
            // Title
            item {
                Text(
                    text = "Just Few\nPermissions",
                    textAlign = TextAlign.Center,
                    fontSize = 47.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    lineHeight = 52.sp,
                    letterSpacing = (-0.03).em,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.27f),
                            offset = Offset(0f, 4f),
                            blurRadius = 42.3f
                        )
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 40.dp)
                )
            }

            // Active Permission Card
            if (activeStepIndex < permissions.size) {
                item {
                    ActivePermissionCard(
                        item = permissions[activeStepIndex],
                        stepIndex = activeStepIndex + 1,
                        totalSteps = permissions.size,
                        isChecked = checkedStates[activeStepIndex],
                        onCheckedChange = { checked ->
                            if (checked) {
                                when (activeStepIndex) {
                                    0 -> PermissionHelper.requestAccessibility(context)
                                    1 -> if (android.os.Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    2 -> PermissionHelper.requestOverlay(context)
                                    3 -> PermissionHelper.requestNotificationAccess(context)
                                    4 -> smsLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.RECEIVE_SMS,
                                            android.Manifest.permission.READ_SMS
                                        )
                                    )
                                    5 -> PermissionHelper.requestBatteryOptimization(context)
                                }
                            } else {
                                checkedStates[activeStepIndex] = false
                            }
                        }
                    )
                }

                // "Up Next" Section
                if (activeStepIndex + 1 < permissions.size) {
                    item {
                        Text(
                            text = "Up Next",
                            color = Color(0xFF414754).copy(alpha = 0.7f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = RobotoFontFamily,
                            modifier = Modifier.padding(
                                top = 28.dp,
                                bottom = 12.dp,
                                start = 8.dp
                            )
                        )
                    }

                    // Pending Permission Items
                    items(permissions.size - activeStepIndex - 1) { offset ->
                        val index = activeStepIndex + 1 + offset
                        PendingPermissionRow(
                            item = permissions[index]
                        )
                        if (offset < permissions.size - activeStepIndex - 2) {
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            } else {
                // All Done State
                item {
                    AllDoneCard(permissions = permissions, onFinishSetup = onFinishSetup)
                }
            }
        }
    }
}

@Composable
fun ActivePermissionCard(
    item: PermissionItem,
    stepIndex: Int,
    totalSteps: Int,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isChecked)
            Color(0xFF24883F).copy(alpha = 0.6f)
        else
            Color(0xFF1A73E8).copy(alpha = 0.4f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "borderColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(32.dp),
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.85f))
            .border(2.dp, borderColor, RoundedCornerShape(32.dp))
            .padding(horizontal = 28.dp, vertical = 30.dp)
    ) {
        // Top row: Icon + Step Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            PermissionSvgAsset(
                rawResId = item.iconResId,
                contentDescription = null,
                modifier = Modifier.size(56.dp)
            )

            Box(
                modifier = Modifier
                    .background(Color(0xFF1A73E8), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "STEP $stepIndex OF $totalSteps",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RobotoFontFamily,
                    letterSpacing = 0.6.sp
                )
            }
        }

        // Title
        Text(
            text = item.title,
            color = Color(0xFF181C1F),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RobotoFontFamily,
            lineHeight = 32.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        // Description
        Text(
            text = item.description,
            color = Color(0xFF414754),
            fontSize = 16.sp,
            fontFamily = RobotoFontFamily,
            lineHeight = 24.sp,
            modifier = Modifier.padding(bottom = 28.dp)
        )

        // Divider
        HorizontalDivider(
            color = Color.Black.copy(alpha = 0.06f),
            thickness = 1.dp
        )

        // Allow Access row with light-mode Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Allow Access",
                color = Color(0xFF181C1F),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = RobotoFontFamily
            )

            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF24883F),
                    checkedBorderColor = Color(0xFF24883F),
                    checkedIconColor = Color(0xFF24883F),
                    uncheckedThumbColor = Color(0xFF79747E),
                    uncheckedTrackColor = Color(0xFFE7E0EC),
                    uncheckedBorderColor = Color(0xFF79747E),
                    uncheckedIconColor = Color(0xFF79747E)
                ),
                thumbContent = if (isChecked) {
                    @Composable {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                } else null
            )
        }
    }
}

@Composable
fun PendingPermissionRow(
    item: PermissionItem
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.25f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 17.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PermissionSvgAsset(
            rawResId = item.iconResId,
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )

        Text(
            text = item.title,
            color = Color(0xFF181C1F),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = RobotoFontFamily,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "Locked",
            tint = Color(0xFF79747E).copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PermissionSvgAsset(
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

@Composable
fun AllDoneCard(permissions: List<PermissionItem>, onFinishSetup: () -> Unit) {
    // Entrance animation
    val scaleAnim = remember { Animatable(0.85f) }
    var visible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(150)
        visible = true
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    // Lottie tick animation – plays once
    val tickComposition by rememberLottieComposition(
        LottieCompositionSpec.Asset("tick.json")
    )
    val tickProgress by animateLottieCompositionAsState(
        composition = tickComposition,
        iterations = 1,
        isPlaying = visible
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)) + scaleIn(
            initialScale = 0.85f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scaleAnim.value)
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(32.dp),
                    ambientColor = Color(0xFF24883F).copy(alpha = 0.15f),
                    spotColor = Color(0xFF24883F).copy(alpha = 0.15f)
                )
                .clip(RoundedCornerShape(32.dp))
                .background(Color.White.copy(alpha = 0.9f))
                .padding(start = 28.dp, end = 28.dp, top = 0.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lottie tick animation
            LottieAnimation(
                composition = tickComposition,
                progress = { tickProgress },
                modifier = Modifier
                    .size(182.dp)
                    .offset(y = (-20).dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = "You're All Set!",
                color = Color(0xFF181C1F),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = RobotoFontFamily,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "All permissions have been granted successfully.",
                color = Color(0xFF414754),
                fontSize = 15.sp,
                fontFamily = RobotoFontFamily,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Permission confirmation list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF5F7F5))
                    .padding(vertical = 4.dp)
            ) {
                permissions.forEachIndexed { index, permission ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val context = LocalContext.current
                        AsyncImage(
                            model = remember {
                                ImageRequest.Builder(context)
                                    .data("file:///android_asset/tick.svg")
                                    .decoderFactory(SvgDecoder.Factory())
                                    .crossfade(false)
                                    .build()
                            },
                            contentDescription = "Granted",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(28.dp)
                        )

                        Text(
                            text = permission.title,
                            color = Color(0xFF181C1F),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = RobotoFontFamily,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = "Granted",
                            color = Color(0xFF24883F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = RobotoFontFamily
                        )
                    }

                    if (index < permissions.size - 1) {
                        HorizontalDivider(
                            color = Color.Black.copy(alpha = 0.04f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Continue button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF1A73E8),
                                Color(0xFF4285F4)
                            )
                        )
                    )
                    .clickable {
                        onFinishSetup()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Continue",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RobotoFontFamily,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionPageScreenPreview() {
    ClipSyncTheme {
        PermissionPageScreen()
    }
}
