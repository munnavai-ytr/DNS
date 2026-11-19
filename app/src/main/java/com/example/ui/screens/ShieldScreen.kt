package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.ShieldAmber
import com.example.ui.theme.ShieldCoral
import com.example.ui.theme.ShieldEmerald
import com.example.ui.viewmodel.MainUiState
import com.example.ui.viewmodel.SelfTestState

@Composable
fun ShieldScreen(
    uiState: MainUiState,
    onToggleVpn: () -> Unit,
    onRunSelfTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = uiState.isVpnRunning
    val accentColor by
        animateColorAsState(
            targetValue = if (isActive) ShieldEmerald else ShieldAmber,
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "shieldAccent",
        )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text =
                            if (isActive) stringResource(R.string.subtitle_active)
                            else stringResource(R.string.subtitle_paused),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(isActive = isActive, accentColor = accentColor)
            }

            // Hero Big ON/OFF Toggle Orb
            ShieldPowerOrb(
                isActive = isActive,
                accentColor = accentColor,
                onClick = onToggleVpn,
            )

            // Status Text Banner
            Text(
                text =
                    if (isActive) stringResource(R.string.status_active)
                    else stringResource(R.string.status_paused),
                style = MaterialTheme.typography.titleLarge,
                color = accentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("shield_status_text"),
            )

            // Counters Row: Blocked Today & Total Blocked
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCounterCard(
                    label = stringResource(R.string.blocked_today),
                    value = uiState.blockedToday,
                    icon = Icons.Filled.Block,
                    accent = ShieldEmerald,
                    testTag = "blocked_today_card",
                    modifier = Modifier.weight(1f),
                )
                StatCounterCard(
                    label = stringResource(R.string.total_blocked),
                    value = uiState.totalBlocked,
                    icon = Icons.Filled.Shield,
                    accent = MaterialTheme.colorScheme.secondary,
                    testTag = "total_blocked_card",
                    modifier = Modifier.weight(1f),
                )
            }

            // Self Test Card
            SelfTestCard(
                selfTestState = uiState.selfTestState,
                onRunSelfTest = onRunSelfTest,
            )

            // Filter Metadata Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.upstream_dns_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "1.1.1.3 • 1.0.0.3 (Family)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.rules_loaded_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val totalRules =
                            uiState.baseRuleCount + uiState.customBlockedRules.size
                        Text(
                            text = "$totalRules domains",
                            style = MaterialTheme.typography.labelMedium,
                            color = ShieldEmerald,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(isActive: Boolean, accentColor: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accentColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (isActive) Icons.Filled.GppGood else Icons.Filled.GppBad,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (isActive) "PROTECTED" else "PAUSED",
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ShieldPowerOrb(
    isActive: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isActive) 1.12f else 1.02f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "pulseScale",
        )

    Box(
        modifier = Modifier.padding(vertical = 8.dp).size(204.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer ambient pulse ring
        Box(
            modifier =
                Modifier.size(196.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    accentColor.copy(alpha = if (isActive) 0.24f else 0.10f),
                                    Color.Transparent,
                                )
                        )
                    )
        )

        // Interactive Power Disc
        Box(
            modifier =
                Modifier.size(156.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.colorScheme.surface,
                                )
                        )
                    )
                    .border(
                        width = 2.5.dp,
                        color = accentColor.copy(alpha = if (isActive) 0.9f else 0.45f),
                        shape = CircleShape,
                    )
                    .clickable(
                        role = Role.Switch,
                        onClickLabel = if (isActive) "Turn off DNS Shield" else "Turn on DNS Shield",
                        onClick = onClick,
                    )
                    .testTag("vpn_power_toggle"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription =
                        if (isActive) "DNS Shield ON" else "DNS Shield OFF",
                    tint = accentColor,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text =
                        if (isActive) stringResource(R.string.toggle_on)
                        else stringResource(R.string.toggle_off),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = accentColor,
                )
            }
        }
    }
}

@Composable
private fun StatCounterCard(
    label: String,
    value: Long,
    icon: ImageVector,
    accent: Color,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.testTag(testTag),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SelfTestCard(
    selfTestState: SelfTestState,
    onRunSelfTest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.self_test_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = stringResource(R.string.self_test_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onRunSelfTest,
                enabled = selfTestState !is SelfTestState.Running,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("self_test_button"),
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ) {
                if (selfTestState is SelfTestState.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ShieldEmerald,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.self_test_running))
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.self_test_button))
                }
            }

            AnimatedVisibility(
                visible =
                    selfTestState is SelfTestState.Working ||
                        selfTestState is SelfTestState.NotWorking
            ) {
                when (selfTestState) {
                    is SelfTestState.Working -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("self_test_result_working"),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, ShieldEmerald.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Blocking works",
                                    tint = ShieldEmerald,
                                )
                                Column {
                                    Text(
                                        text = stringResource(R.string.self_test_working),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = ShieldEmerald,
                                    )
                                    Text(
                                        text =
                                            "blocktest.dnsshield.test → ${selfTestState.resolvedIp} (${selfTestState.latencyMs} ms)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    is SelfTestState.NotWorking -> {
                        Surface(
                            modifier =
                                Modifier.fillMaxWidth().testTag("self_test_result_not_working"),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, ShieldCoral.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Not working",
                                    tint = ShieldCoral,
                                )
                                Column {
                                    Text(
                                        text = stringResource(R.string.self_test_not_working),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = ShieldCoral,
                                    )
                                    Text(
                                        text = selfTestState.reason,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}
