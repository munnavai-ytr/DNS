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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.protection.LockType
import com.example.protection.PinActionResult
import com.example.protection.PinHasher
import com.example.protection.ProtectionChecklistState
import com.example.protection.ProtectionLevel
import com.example.protection.ProtectionMode
import com.example.protection.ProtectionSystemChecker
import com.example.protection.TamperProtectionEngine
import com.example.protection.TamperProtectionState
import com.example.protection.WaitDurationOption
import com.example.protection.WaitTimeUpdateResult
import com.example.ui.theme.ShieldAmber
import com.example.ui.theme.ShieldCoral
import com.example.ui.theme.ShieldCyan
import com.example.ui.theme.ShieldEmerald
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtectionWizardScreen(
    tamperState: TamperProtectionState,
    checklist: ProtectionChecklistState,
    protectionLevel: ProtectionLevel,
    initialShowChecklistOnly: Boolean,
    onSelectProtectionMode: suspend (ProtectionMode) -> WaitTimeUpdateResult,
    onSaveInitialPin: suspend (String) -> PinActionResult,
    onActivateStrictLock: suspend (String) -> PinActionResult,
    onSelectWaitDuration: suspend (WaitDurationOption) -> WaitTimeUpdateResult,
    onConfirmWaitDurationStep: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    val scope = rememberCoroutineScope()
    var showChecklistTab by remember(initialShowChecklistOnly) {
        mutableStateOf(initialShowChecklistOnly)
    }

    var selectedLockTab by
        remember(tamperState.lockType) {
            mutableStateOf(
                if (tamperState.lockType == LockType.STRICT_LOCK) LockType.STRICT_LOCK
                else LockType.TRUSTED_PIN
            )
        }
    var strictLockStep by remember { mutableIntStateOf(1) }
    var strictUnderstandInput by remember { mutableStateOf("") }

    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var modeFeedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var lockFeedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
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

            // Mode Switch: Setup Steps vs Status Checklist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChip(
                    selected = !showChecklistTab,
                    onClick = { showChecklistTab = false },
                    label = {
                        Text(
                            if (tamperState.protectionMode == ProtectionMode.VPN) "Setup Steps (1-6)"
                            else "Setup Steps"
                        )
                    },
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
                    onOpenNetworkSettings = onOpenNetworkSettings,
                    onGoToSetup = { showChecklistTab = false },
                )

                Button(
                    onClick = onFinish,
                    modifier =
                        Modifier.fillMaxWidth().height(50.dp).testTag("checklist_done_button"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(text = "Back to DNS Shield", fontWeight = FontWeight.Bold)
                }
            } else {
                // Step 1: Choose Protection Mode (VPN or Private DNS)
                WizardStepCard(
                    stepNumber = 1,
                    title = "Choose Protection Mode",
                    icon = Icons.Filled.Dns,
                    isDone = checklist.modeConfigured,
                    required = true,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProtectionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = tamperState.protectionMode == mode,
                                onClick = {
                                    scope.launch {
                                        when (val res = onSelectProtectionMode(mode)) {
                                            WaitTimeUpdateResult.Success -> {
                                                modeFeedback = "Selected ${mode.label}." to true
                                            }
                                            is WaitTimeUpdateResult.Rejected -> {
                                                modeFeedback = res.reason to false
                                            }
                                        }
                                    }
                                },
                                label = { Text(mode.label) },
                                modifier = Modifier.weight(1f).testTag("wizard_mode_${mode.name}"),
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ShieldCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = ShieldCyan,
                                    ),
                            )
                        }
                    }

                    if (tamperState.protectionMode == ProtectionMode.VPN) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ShieldCyan.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ShieldCyan.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text =
                                    "VPN Mode uses the local DNS VPN with all V1/V2 features (blocklist, AI verification, custom rules, logs). Note: Android allows only one VPN at a time, so turning on another VPN will switch DNS Shield off.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        if (!checklist.modeConfigured) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        when (val res = onSelectProtectionMode(ProtectionMode.VPN)) {
                                            WaitTimeUpdateResult.Success -> {
                                                modeFeedback = "Confirmed VPN Mode." to true
                                            }
                                            is WaitTimeUpdateResult.Rejected -> {
                                                modeFeedback = res.reason to false
                                            }
                                        }
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("wizard_confirm_vpn_mode_button"),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Confirm VPN Mode")
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ShieldAmber.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ShieldAmber.copy(alpha = 0.4f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Private DNS Mode (Lite) — No VPN Used",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ShieldAmber,
                                )
                                Text(
                                    text =
                                        "1. Tap Open Network Settings below.\n" +
                                            "2. Open Private DNS (or More connection settings -> Private DNS).\n" +
                                            "3. Choose \"Private DNS provider hostname\".\n" +
                                            "4. Enter: ${ProtectionSystemChecker.PRIVATE_DNS_REQUIRED_HOSTNAME} and tap Save.\n" +
                                            "Note: AI check and custom rules do not apply in this mode.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        StatusTickRow(
                            label =
                                "Private DNS set to ${ProtectionSystemChecker.PRIVATE_DNS_REQUIRED_HOSTNAME}",
                            isGreen = checklist.privateDnsConfigured,
                        )

                        OutlinedButton(
                            onClick = onOpenNetworkSettings,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("wizard_network_settings_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Open Network Settings")
                        }
                    }

                    modeFeedback?.let { (msg, ok) ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ok) ShieldEmerald else ShieldCoral,
                        )
                    }
                }

                // Step 2: Choose Lock Type (Trusted PIN or Strict Lock)
                WizardStepCard(
                    stepNumber = 2,
                    title = "Choose Lock Type",
                    icon = Icons.Filled.Lock,
                    isDone = checklist.isLockStepComplete,
                    required = true,
                ) {
                    val canEditLock = tamperState.canChangeLockOrMode

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedLockTab == LockType.TRUSTED_PIN,
                            onClick = {
                                if (canEditLock) {
                                    selectedLockTab = LockType.TRUSTED_PIN
                                    lockFeedback = null
                                } else {
                                    lockFeedback =
                                        if (tamperState.isStrictLock) {
                                            "Strict Lock is permanent and cannot be changed." to false
                                        } else {
                                            "Lock type can only be changed inside an active 15-minute unlock window." to false
                                        }
                                }
                            },
                            label = { Text("Trusted PIN") },
                            modifier = Modifier.weight(1f).testTag("wizard_lock_trusted_pin"),
                        )
                        FilterChip(
                            selected = selectedLockTab == LockType.STRICT_LOCK,
                            onClick = {
                                if (canEditLock) {
                                    selectedLockTab = LockType.STRICT_LOCK
                                    strictLockStep = 1
                                    lockFeedback = null
                                } else {
                                    lockFeedback =
                                        if (tamperState.isStrictLock) {
                                            "Strict Lock is already active." to false
                                        } else {
                                            "Lock type can only be changed inside an active 15-minute unlock window." to false
                                        }
                                }
                            },
                            label = { Text("Strict Lock") },
                            modifier = Modifier.weight(1f).testTag("wizard_lock_strict_lock"),
                        )
                    }

                    if (selectedLockTab == LockType.TRUSTED_PIN) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ShieldAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ShieldAmber.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text =
                                    "Advice: Give this 6-digit PIN to a trusted person and do not keep it yourself. The PIN is stored only as a salted PBKDF2 hash.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShieldAmber,
                                modifier = Modifier.padding(12.dp),
                            )
                        }

                        if (!canEditLock) {
                            Text(
                                text =
                                    "PIN is locked. It is never shown and can only be changed inside an active 15-minute unlock window.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShieldEmerald,
                            )
                        } else {
                            OutlinedTextField(
                                value = pin1,
                                onValueChange = { input ->
                                    if (
                                        input.length <= PinHasher.PIN_LENGTH &&
                                            input.all { it.isDigit() }
                                    ) {
                                        pin1 = input
                                        lockFeedback = null
                                    }
                                },
                                label = { Text("Enter 6-digit PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth().testTag("wizard_pin_1"),
                                shape = RoundedCornerShape(12.dp),
                            )

                            OutlinedTextField(
                                value = pin2,
                                onValueChange = { input ->
                                    if (
                                        input.length <= PinHasher.PIN_LENGTH &&
                                            input.all { it.isDigit() }
                                    ) {
                                        pin2 = input
                                        lockFeedback = null
                                    }
                                },
                                label = { Text("Confirm 6-digit PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth().testTag("wizard_pin_2"),
                                shape = RoundedCornerShape(12.dp),
                            )

                            Button(
                                onClick = {
                                    when {
                                        !PinHasher.isValidPinFormat(pin1) -> {
                                            lockFeedback = "PIN must be exactly 6 digits." to false
                                        }
                                        pin1 != pin2 -> {
                                            lockFeedback =
                                                "PINs do not match. Enter the same 6 digits twice." to
                                                    false
                                        }
                                        else -> {
                                            scope.launch {
                                                when (val res = onSaveInitialPin(pin1)) {
                                                    PinActionResult.Success -> {
                                                        lockFeedback =
                                                            "Trusted PIN saved securely." to true
                                                        pin1 = ""
                                                        pin2 = ""
                                                    }
                                                    is PinActionResult.Rejected -> {
                                                        lockFeedback = res.reason to false
                                                    }
                                                    else -> {
                                                        lockFeedback = "Could not save PIN." to false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("wizard_save_pin_button"),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(if (checklist.pinConfigured) "Update Trusted PIN" else "Save Trusted PIN")
                            }
                        }

                        // Cooling-off wait time selector
                        Text(
                            text = "Cooling-off wait time (default 24 hours):",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
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
                                                    waitFeedback =
                                                        "Wait time set to ${option.label}." to true
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
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("wizard_confirm_wait_button"),
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
                    } else {
                        // STRICT LOCK: 2 confirmation screens including typing "I UNDERSTAND"
                        if (tamperState.isStrictLock && tamperState.protectionSetupDone) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ShieldCoral.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, ShieldCoral.copy(alpha = 0.5f)),
                            ) {
                                Text(
                                    text =
                                        "Strict Lock is ACTIVE. There is no PIN and no unlock path. Only a factory reset removes this app.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ShieldCoral,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        } else if (strictLockStep == 1) {
                            // Confirmation Screen 1
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ShieldCoral.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, ShieldCoral.copy(alpha = 0.5f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = ShieldCoral,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = "Strict Lock Confirmation (1 of 2)",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = ShieldCoral,
                                        )
                                    }
                                    Text(
                                        text =
                                            "Strict Lock has NO PIN and NO unlock path at all. Once Steps 1-4 are complete, you cannot turn off protection, change mode, or uninstall DNS Shield. Only a factory reset removes the app.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            Button(
                                onClick = { strictLockStep = 2 },
                                colors = ButtonDefaults.buttonColors(containerColor = ShieldCoral),
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("strict_lock_step1_continue"),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("I Want Strict Lock — Continue (1/2)")
                            }
                        } else {
                            // Confirmation Screen 2: Type "I UNDERSTAND"
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ShieldCoral.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, ShieldCoral.copy(alpha = 0.5f)),
                            ) {
                                Text(
                                    text =
                                        "Strict Lock Final Confirmation (2 of 2): Only a factory reset removes this app. Type ${TamperProtectionEngine.STRICT_LOCK_CONFIRMATION_PHRASE} below to confirm.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ShieldCoral,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }

                            OutlinedTextField(
                                value = strictUnderstandInput,
                                onValueChange = {
                                    strictUnderstandInput = it
                                    lockFeedback = null
                                },
                                label = { Text("Type I UNDERSTAND") },
                                singleLine = true,
                                modifier =
                                    Modifier.fillMaxWidth().testTag("strict_lock_phrase_input"),
                                shape = RoundedCornerShape(12.dp),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { strictLockStep = 1 },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Back")
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            when (val res = onActivateStrictLock(strictUnderstandInput)) {
                                                PinActionResult.Success -> {
                                                    lockFeedback =
                                                        "Strict Lock selected. Only a factory reset can remove this app once Steps 1-4 are active." to
                                                            true
                                                    strictUnderstandInput = ""
                                                }
                                                is PinActionResult.Rejected -> {
                                                    lockFeedback = res.reason to false
                                                }
                                                else -> Unit
                                            }
                                        }
                                    },
                                    enabled =
                                        strictUnderstandInput.trim() ==
                                            TamperProtectionEngine.STRICT_LOCK_CONFIRMATION_PHRASE,
                                    colors =
                                        ButtonDefaults.buttonColors(containerColor = ShieldCoral),
                                    modifier =
                                        Modifier.weight(1f)
                                            .height(48.dp)
                                            .testTag("strict_lock_step2_confirm"),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Confirm Strict Lock")
                                }
                            }
                        }
                    }

                    lockFeedback?.let { (msg, ok) ->
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
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(48.dp)
                                .testTag("wizard_device_admin_button"),
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
                            "Covers protected system settings, uninstallers, and Private DNS settings with an opaque Guard Overlay whenever Tamper Protection is ON.",
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
                            modifier =
                                Modifier.weight(1f)
                                    .height(48.dp)
                                    .testTag("wizard_app_info_button"),
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

                // Step 5 (VPN mode ONLY): Always-on VPN
                if (tamperState.protectionMode == ProtectionMode.VPN) {
                    WizardStepCard(
                        stepNumber = 5,
                        title = "Always-On VPN",
                        icon = Icons.Filled.VpnLock,
                        isDone = checklist.vpnAlwaysOn,
                        required = false,
                    ) {
                        Text(
                            text =
                                "In VPN settings, tap the gear icon next to DNS Shield and turn ON \"Always-on VPN\". Do NOT turn on \"Block connections without VPN\" — doing so would cut all internet because only DNS traffic goes through this VPN.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            StatusTickRow("Always-on VPN enabled", checklist.vpnAlwaysOn)
                        } else {
                            Text(
                                text = "Always-on VPN inspection requires Android 10+ (API 29).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenVpnSettings,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("wizard_vpn_settings_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Open VPN Settings")
                        }
                    }
                }

                // Step 6: Battery optimization
                WizardStepCard(
                    stepNumber = if (tamperState.protectionMode == ProtectionMode.VPN) 6 else 5,
                    title = "Ignore Battery Optimization",
                    icon = Icons.Filled.BatteryChargingFull,
                    isDone = checklist.batteryOptimizationIgnored,
                    required = false,
                ) {
                    Text(
                        text =
                            "Allows the DNS filter and 15-minute watchdog to run reliably in the background.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onRequestBatteryOptimization,
                        enabled = !checklist.batteryOptimizationIgnored,
                        modifier =
                            Modifier.fillMaxWidth().height(48.dp).testTag("wizard_battery_button"),
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
                    Text(text = "Finish & Review Status Checklist", fontWeight = FontWeight.Bold)
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
    onOpenNetworkSettings: () -> Unit,
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
                        ProtectionLevel.ON -> "Tamper Protection is ON (${tamperState.protectionMode.label})"
                        ProtectionLevel.WEAK -> "Protection Weakened — Tap Missing Step Below"
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

            StatusTickRow(
                "1. Mode: ${checklist.protectionMode.label} (Required)",
                checklist.modeConfigured,
            )
            StatusTickRow(
                label =
                    when (checklist.lockType) {
                        LockType.TRUSTED_PIN ->
                            "2. Lock: Trusted PIN (${tamperState.waitDuration.label}) (Required)"
                        LockType.STRICT_LOCK -> "2. Lock: Strict Lock — Permanent (Required)"
                        LockType.NONE -> "2. Lock Type configured (Required)"
                    },
                isGreen = checklist.isLockStepComplete,
            )
            StatusTickRow("3. Device Admin active (Required)", checklist.deviceAdminEnabled)
            StatusTickRow(
                "4. Accessibility Guard (DNS Shield Guard) enabled (Required)",
                checklist.accessibilityEnabled,
            )
            StatusTickRow(
                label =
                    when {
                        !checklist.screenGuardEnabled -> "Screen Guard: Disabled in Settings"
                        !checklist.accessibilityEnabled ->
                            "Screen Guard: Accessibility service not running"
                        checklist.screenGuardRecentlyActive ->
                            "Screen Guard: Running and recently active"
                        else -> "Screen Guard: Enabled (waiting for app activity)"
                    },
                isGreen = checklist.screenGuardRecentlyActive,
            )

            if (checklist.protectionMode == ProtectionMode.VPN) {
                StatusTickRow("5. Local DNS VPN running", checklist.vpnRunning)
                StatusTickRow("6. Always-on VPN enabled (recommended)", checklist.vpnAlwaysOn)
            } else {
                StatusTickRow(
                    "5. Private DNS set to ${ProtectionSystemChecker.PRIVATE_DNS_REQUIRED_HOSTNAME}",
                    checklist.privateDnsConfigured,
                )
                if (!checklist.privateDnsConfigured) {
                    OutlinedButton(
                        onClick = onOpenNetworkSettings,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Open Network Settings (Set Private DNS)")
                    }
                }
            }

            StatusTickRow(
                "Battery optimization unrestricted",
                checklist.batteryOptimizationIgnored,
            )

            if (!checklist.isCoreStepsComplete || !checklist.isModeActiveOk || !checklist.batteryOptimizationIgnored) {
                OutlinedButton(
                    onClick = onGoToSetup,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Configure Steps")
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
    val borderColor =
        if (isDone) ShieldEmerald.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
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
                            text =
                                if (required) "Required for ON status"
                                else "Recommended hardening",
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
