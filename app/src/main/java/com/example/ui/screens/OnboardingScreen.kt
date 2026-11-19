package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.ShieldAmber
import com.example.ui.theme.ShieldEmerald
import com.example.ui.viewmodel.SystemPermissionState

@Composable
fun OnboardingScreen(
    permissions: SystemPermissionState,
    notificationsGranted: Boolean,
    onRequestVpnPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRequestNotifications: () -> Unit,
    onComplete: (activateShield: Boolean) -> Unit,
) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface,
                            )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier =
                        Modifier.size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = stringResource(R.string.app_name),
                        tint = ShieldEmerald,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            PermissionStepCard(
                icon = Icons.Filled.VpnKey,
                title = "1. Local DNS VPN Filter",
                description =
                    "Android uses a local VPN tunnel so DNS Shield can inspect only DNS requests (UDP 53) and block adult domains across every browser and app. All other internet traffic stays direct.",
                isGranted = permissions.hasVpnPermission,
                actionLabel = if (permissions.hasVpnPermission) "VPN Authorized" else "Grant VPN Permission",
                testTag = "onboarding_vpn_button",
                onAction = onRequestVpnPermission,
            )

            PermissionStepCard(
                icon = Icons.Filled.BatteryChargingFull,
                title = "2. Disable Battery Optimization",
                description =
                    "Prevent Android from putting the background DNS filter to sleep so protection stays reliable all day and restarts automatically after reboot.",
                isGranted = permissions.isBatteryOptimizationIgnored,
                actionLabel =
                    if (permissions.isBatteryOptimizationIgnored) "Unrestricted"
                    else "Disable Optimization",
                testTag = "onboarding_battery_button",
                onAction = onRequestBatteryOptimization,
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionStepCard(
                    icon = Icons.Filled.NotificationsActive,
                    title = "3. Shield Status Notification",
                    description =
                        "Allow the persistent status notification required by Android for active foreground VPN protection (${Manifest.permission.POST_NOTIFICATIONS.substringAfterLast('.')}).",
                    isGranted = notificationsGranted,
                    actionLabel =
                        if (notificationsGranted) "Notifications Allowed" else "Allow Notifications",
                    testTag = "onboarding_notifications_button",
                    onAction = onRequestNotifications,
                )
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onComplete(permissions.hasVpnPermission) },
                modifier =
                    Modifier.fillMaxWidth()
                        .height(54.dp)
                        .testTag("onboarding_continue_button"),
                shape = RoundedCornerShape(16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            ) {
                Text(
                    text =
                        if (permissions.hasVpnPermission) {
                            stringResource(R.string.onboarding_get_started)
                        } else {
                            stringResource(R.string.onboarding_skip)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (!permissions.hasVpnPermission) {
                TextButton(
                    onClick = { onComplete(false) },
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("onboarding_skip_button"),
                ) {
                    Text(
                        text = "Set up VPN later from the main screen",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStepCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    testTag: String,
    onAction: () -> Unit,
) {
    val borderColor =
        if (isGranted) ShieldEmerald.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) ShieldEmerald else ShieldAmber,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (isGranted) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Granted",
                        tint = ShieldEmerald,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = onAction,
                enabled = !isGranted,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag(testTag),
                shape = RoundedCornerShape(12.dp),
                border =
                    BorderStroke(
                        1.dp,
                        if (isGranted) ShieldEmerald.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.primary,
                    ),
            ) {
                if (isGranted) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = ShieldEmerald,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = actionLabel,
                    color =
                        if (isGranted) ShieldEmerald else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
