package com.example.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.protection.PinActionResult
import com.example.protection.PinHasher
import com.example.protection.ProtectionChecklistState
import com.example.protection.ProtectionLevel
import com.example.protection.TamperProtectionState
import com.example.protection.WaitDurationOption
import com.example.protection.WaitTimeUpdateResult
import com.example.ui.theme.ShieldAmber
import com.example.ui.theme.ShieldCoral
import com.example.ui.theme.ShieldEmerald
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtectionWizardScreen(
    tamperState: TamperProtectionState,
    checklist: ProtectionChecklistState,
    protectionLevel: ProtectionLevel,
    initialShowChecklistOnly: Boolean,
    onSaveInitialPin: suspend (String) -> PinActionResult,
    onSelectWaitDuration: suspend (WaitDurationOption) -> WaitTimeUpdateResult,
    onConfirmWaitDurationStep: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    val scope = rememberCoroutineScope()
    var showChecklistTab by remember(initialShowChecklistOnly) {
        mutableStateOf(initialShowChecklistOnly)
    }

    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var pinFeedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var waitFeedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("wizard_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                    Text(
                        text =
                            if (showChecklistTab) stringResource(R.string.protection_checklist_title)
                            else stringResource(R.string.protection_setup_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                ProtectionLevelBadge(level = protectionLevel)
            }

            // Mode Switch: Setup Wizard vs Status Checklist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChip(
                    selected = !showChecklistTab,
                    onClick = { showChecklistTab = false },
                    label = { Text("Setup Steps (1-6)") },
                    modifier = Modifier.weight(1f).testTag("wizard_tab_setup"),
                )
                FilterChip(
                    selected = showChecklistTab,
                    onClick = { showChecklistTab = true },
                    label = { Text("Status Checklist") },
                    modifier = Modifier.weight(1f).testTag("wizard_tab_checklist"),
                )
            }

            if (showChecklistTab) {
                ProtectionChecklistCard(
                    checklist = checklist,
                    tamperState = tamperState,
                    protectionLevel = protectionLevel,
                    onGoToSetup = { showChecklistTab = false },
                )

                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("checklist_done_button"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = "Back to DNS Shield",
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                // Step 1: 6-digit PIN
                WizardStepCard(
                    stepNumber = 1,
                    title = "Set a 6-Digit PIN",
                    icon = Icons.Filled.Lock,
                    isDone = checklist.pinConfigured,
                    required = true,
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ShieldAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ShieldAmber.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text =
                                "Advice: Give this PIN to a trusted person and do not keep it yourself.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShieldAmber,
                            modifier = Modifier.padding(12.dp),
                        )
                    }

                    val canEditPin =
                        !tamperState.protectionSetupDone || tamperState.isUnlockWindowActive

                    if (!canEditPin) {
                        Text(
                            text =
                                "PIN is already set and locked. Enter the 15-minute unlock window to change it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShieldEmerald,
                        )
                    } else {
                        OutlinedTextField(
                            value = pin1,
                            onValueChange = { input ->
                                if (input.length <= PinHasher.PIN_LENGTH && input.all { it.isDigit() }) {
                                    pin1 = input
                                    pinFeedback = null
                                }
                            },
                            label = { Text("Enter 6-digit PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth().testTag("wizard_pin_1"),
                            shape = RoundedCornerShape(12.dp),
                        )

                        OutlinedTextField(
                            value = pin2,
                            onValueChange = { input ->
                                if (input.length <= PinHasher.PIN_LENGTH && input.all { it.isDigit() }) {
                                    pin2 = input
                                    pinFeedback = null
                                }
                            },
                            label = { Text("Confirm 6-digit PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth().testTag("wizard_pin_2"),
                            shape = RoundedCornerShape(12.dp),
                        )

                        Button(
                            onClick = {
                                when {
                                    !PinHasher.isValidPinFormat(pin1) -> {
                                        pinFeedback = "PIN must be exactly 6 digits." to false
                                    }
                                    pin1 != pin2 -> {
                                        pinFeedback = "PINs do not match. Enter the same 6 digits twice." to false
                                    }
                                    else -> {
                                        scope.launch {
                                            when (val res = onSaveInitialPin(pin1)) {
                                                PinActionResult.Success -> {
                                                    pinFeedback = "PIN saved securely (PBKDF2 hash)." to true
                                                    pin1 = ""
                                                    pin2 = ""
                                                }
                                                is PinActionResult.Rejected -> {
                                                    pinFeedback = res.reason to false
                                                }
                                                else -> {
                                                    pinFeedback = "Could not save PIN." to false
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wizard_save_pin_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(if (checklist.pinConfigured) "Update PIN" else "Save PIN")
                        }
                    }

                    pinFeedback?.let { (msg, ok) ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ok) ShieldEmerald else ShieldCoral,
                        )
                    }
                }

                // Step 2: Cooling-off wait time
                WizardStepCard(
                    stepNumber = 2,
                    title = "Cooling-Off Wait Time",
                    icon = Icons.Filled.Schedule,
                    isDone = checklist.waitDurationConfigured,
                    required = true,
                ) {
                    Text(
                        text =
                            "Wait time required after entering your PIN before protection can be unlocked. Default is 24 hours.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WaitDurationOption.entries.forEach { option ->
                            val selected = tamperState.waitDuration == option
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    scope.launch {
                                        when (val res = onSelectWaitDuration(option)) {
                                            WaitTimeUpdateResult.Success -> {
                                                waitFeedback = "Wait time set to ${option.label}." to true
                                            }
                                            is WaitTimeUpdateResult.Rejected -> {
                                                waitFeedback = res.reason to false
                                            }
                                        }
                                    }
                                },
                                label = { Text(option.label) },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ShieldEmerald.copy(alpha = 0.2f),
                                        selectedLabelColor = ShieldEmerald,
                                    ),
                                modifier = Modifier.testTag("wizard_wait_${option.name}"),
                            )
                        }
                    }

                    if (!checklist.waitDurationConfigured) {
                        OutlinedButton(
                            onClick = {
                                onConfirmWaitDurationStep()
                                waitFeedback =
                                    "Confirmed ${tamperState.waitDuration.label}." to true
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wizard_confirm_wait_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Confirm Wait Time (${tamperState.waitDuration.label})")
                        }
                    }

                    waitFeedback?.let { (msg, ok) ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ok) ShieldEmerald else ShieldCoral,
                        )
                    }
                }

                // Step 3: Device Admin
                WizardStepCard(
                    stepNumber = 3,
                    title = "Enable Device Admin",
                    icon = Icons.Filled.AdminPanelSettings,
                    isDone = checklist.deviceAdminEnabled,
                    required = true,
                ) {
                    Text(
                        text =
                            "Blocks direct uninstallation of DNS Shield while Tamper Protection is ON.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onRequestDeviceAdmin,
                        enabled = !checklist.deviceAdminEnabled,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wizard_device_admin_button"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            if (checklist.deviceAdminEnabled) "Device Admin Enabled"
                            else "Enable Device Admin"
                        )
                    }
                }

                // Step 4: Accessibility Guard
                WizardStepCard(
                    stepNumber = 4,
                    title = "Enable DNS Shield Guard",
                    icon = Icons.Filled.AccessibilityNew,
                    isDone = checklist.accessibilityEnabled,
                    required = true,
                ) {
                    Text(
                        text =
                            "Protects Settings, Package Installer, and security apps from force-stopping, disabling, or uninstalling DNS Shield.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Android 13+ Sideloaded APK Instructions:",
                                style = MaterialTheme.typography.labelLarge,
                                color = ShieldAmber,
                            )
                            Text(
                                text =
                                    "1. Tap Open App Info below.\n" +
                                        "2. Tap the three-dot menu (top right).\n" +
                                        "3. Tap Allow restricted settings.\n" +
                                        "4. Tap Open Accessibility Settings below and enable DNS Shield Guard.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onOpenAppInfo,
                            modifier = Modifier.weight(1f).height(48.dp).testTag("wizard_app_info_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Open App Info", maxLines = 1)
                        }
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier =
                                Modifier.weight(1f)
                                    .height(48.dp)
                                    .testTag("wizard_accessibility_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                if (checklist.accessibilityEnabled) "Guard Active"
                                else "Accessibility Settings",
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Step 5: Always-on VPN
                WizardStepCard(
                    stepNumber = 5,
                    title = "Always-On VPN & Lockdown",
                    icon = Icons.Filled.VpnLock,
                    isDone = checklist.isAlwaysOnAndLockdownOk,
                    required = false,
                ) {
                    Text(
                        text =
                            "In VPN settings, tap the gear icon next to DNS Shield and turn ON both \"Always-on VPN\" and \"Block connections without VPN\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        StatusTickRow("Always-on VPN", checklist.vpnAlwaysOn)
                        StatusTickRow("Block connections without VPN", checklist.vpnLockdownEnabled)
                    } else {
                        Text(
                            text = "Always-on VPN inspection requires Android 10+ (API 29).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenVpnSettings,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wizard_vpn_settings_button"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Open VPN Settings")
                    }
                }

                // Step 6: Battery optimization
                WizardStepCard(
                    stepNumber = 6,
                    title = "Ignore Battery Optimization",
                    icon = Icons.Filled.BatteryChargingFull,
                    isDone = checklist.batteryOptimizationIgnored,
                    required = false,
                ) {
                    Text(
                        text =
                            "Allows the DNS filter and watchdog to stay alive reliably in the background.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onRequestBatteryOptimization,
                        enabled = !checklist.batteryOptimizationIgnored,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wizard_battery_button"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            if (checklist.batteryOptimizationIgnored) "Unrestricted"
                            else "Allow Unrestricted Battery"
                        )
                    }
                }

                Button(
                    onClick = { showChecklistTab = true },
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(52.dp)
                            .testTag("wizard_finish_to_checklist_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                if (checklist.isCoreStepsComplete) ShieldEmerald
                                else MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Text(
                        text = "Finish & Review Status Checklist",
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (!checklist.isCoreStepsComplete) {
                    TextButton(
                        onClick = onFinish,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Set up later (Protection remains OFF)")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProtectionChecklistCard(
    checklist: ProtectionChecklistState,
    tamperState: TamperProtectionState,
    protectionLevel: ProtectionLevel,
    onGoToSetup: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("protection_checklist_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text =
                    when (protectionLevel) {
                        ProtectionLevel.ON -> "Tamper Protection is ON"
                        ProtectionLevel.WEAK -> "Protection Weakened — Complete Missing Steps"
                        ProtectionLevel.OFF ->
                            if (tamperState.isUnlockWindowActive) "Unlock Window Active (Paused)"
                            else "Complete Steps 1-4 to turn Protection ON"
                    },
                style = MaterialTheme.typography.titleMedium,
                color =
                    when (protectionLevel) {
                        ProtectionLevel.ON -> ShieldEmerald
                        ProtectionLevel.WEAK -> ShieldCoral
                        ProtectionLevel.OFF -> ShieldAmber
                    },
                fontWeight = FontWeight.Bold,
            )

            StatusTickRow("1. 6-digit PIN configured (Required)", checklist.pinConfigured)
            StatusTickRow(
                "2. Cooling-off wait time (${tamperState.waitDuration.label}) (Required)",
                checklist.waitDurationConfigured,
            )
            StatusTickRow("3. Device Admin active (Required)", checklist.deviceAdminEnabled)
            StatusTickRow(
                "4. Accessibility Guard (DNS Shield Guard) enabled (Required)",
                checklist.accessibilityEnabled,
            )
            StatusTickRow("5. Local DNS VPN running", checklist.vpnRunning)
            StatusTickRow("6. Always-on VPN enabled", checklist.vpnAlwaysOn)
            StatusTickRow("7. Block connections without VPN", checklist.vpnLockdownEnabled)
            StatusTickRow(
                "8. Battery optimization unrestricted",
                checklist.batteryOptimizationIgnored,
            )

            if (!checklist.isCoreStepsComplete || !checklist.isAlwaysOnAndLockdownOk || !checklist.batteryOptimizationIgnored) {
                OutlinedButton(
                    onClick = onGoToSetup,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Configure Missing Steps")
                }
            }
        }
    }
}

@Composable
private fun WizardStepCard(
    stepNumber: Int,
    title: String,
    icon: ImageVector,
    isDone: Boolean,
    required: Boolean,
    content: @Composable () -> Unit,
) {
    val borderColor = if (isDone) ShieldEmerald.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isDone) ShieldEmerald else ShieldAmber,
                    )
                    Column {
                        Text(
                            text = "Step $stepNumber: $title",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (required) "Required for ON status" else "Recommended hardening",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (isDone) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = if (isDone) "Complete" else "Incomplete",
                    tint = if (isDone) ShieldEmerald else ShieldCoral,
                    modifier = Modifier.size(22.dp),
                )
            }
            content()
        }
    }
}

@Composable
fun StatusTickRow(label: String, isGreen: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isGreen) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (isGreen) "Enabled" else "Disabled",
            tint = if (isGreen) ShieldEmerald else ShieldCoral,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isGreen) MaterialTheme.colorScheme.onSurface else ShieldCoral,
        )
    }
}

@Composable
fun ProtectionLevelBadge(level: ProtectionLevel) {
    val (label, color) =
        when (level) {
            ProtectionLevel.ON -> "ON" to ShieldEmerald
            ProtectionLevel.WEAK -> "WEAK" to ShieldCoral
            ProtectionLevel.OFF -> "OFF" to ShieldAmber
        }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
