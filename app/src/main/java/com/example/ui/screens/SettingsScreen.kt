package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CustomRuleEntity
import com.example.dns.DomainVerdict
import com.example.dns.UnsurePolicy
import com.example.protection.WaitDurationOption
import com.example.protection.WaitTimeUpdateResult
import com.example.ui.theme.ShieldAmber
import com.example.ui.theme.ShieldCoral
import com.example.ui.theme.ShieldCyan
import com.example.ui.theme.ShieldEmerald
import com.example.ui.viewmodel.AiTestState
import com.example.ui.viewmodel.DomainCheckState
import com.example.ui.viewmodel.MainUiState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onToggleAiVerification: (Boolean) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onChangeWhenUnsure: (UnsurePolicy) -> Unit,
    onTestAiConnection: () -> Unit,
    onClearCache: () -> Unit,
    onCheckDomain: (String) -> Unit,
    onAddCustomBlock: (String) -> Unit,
    onAddWhitelist: (String) -> Unit,
    onRemoveRule: (String) -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenProtectionWizard: () -> Unit,
    onOpenUnlockFlow: () -> Unit,
    onOpenChangePin: () -> Unit,
    onSelectWaitDuration: suspend (WaitDurationOption) -> WaitTimeUpdateResult,
    onShowFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            // V3 Tamper Protection Card
            TamperProtectionSettingsCard(
                uiState = uiState,
                onOpenProtectionWizard = onOpenProtectionWizard,
                onOpenUnlockFlow = onOpenUnlockFlow,
                onOpenChangePin = onOpenChangePin,
                onSelectWaitDuration = onSelectWaitDuration,
                onShowFeedback = onShowFeedback,
            )

            // AI Verification Card
            AiVerificationSettingsCard(
                uiState = uiState,
                onToggleAiVerification = onToggleAiVerification,
                onSaveApiKey = onSaveApiKey,
                onSaveModel = onSaveModel,
                onChangeWhenUnsure = onChangeWhenUnsure,
                onTestAiConnection = onTestAiConnection,
                onClearCache = onClearCache,
            )

            // Check a Domain Card
            CheckDomainCard(
                domainCheckState = uiState.domainCheckState,
                onCheckDomain = onCheckDomain,
            )

            // Custom Blocklist Card
            DomainRuleSectionCard(
                title = stringResource(R.string.custom_block_title),
                subtitle = "Extra domains blocked in addition to the built-in list.",
                hint = stringResource(R.string.custom_block_hint),
                icon = Icons.Filled.Block,
                accent = ShieldCoral,
                rules = uiState.customBlockedRules,
                inputTestTag = "custom_block_input",
                addButtonTestTag = "custom_block_add_button",
                onAdd = onAddCustomBlock,
                onRemove = onRemoveRule,
            )

            // Whitelist Card
            DomainRuleSectionCard(
                title = stringResource(R.string.whitelist_title),
                subtitle = "Whitelisted domains and their subdomains always bypass the blocklist.",
                hint = stringResource(R.string.whitelist_hint),
                icon = Icons.Filled.VerifiedUser,
                accent = ShieldEmerald,
                rules = uiState.whitelistRules,
                inputTestTag = "whitelist_input",
                addButtonTestTag = "whitelist_add_button",
                onAdd = onAddWhitelist,
                onRemove = onRemoveRule,
            )

            // Battery & Boot Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint =
                                if (uiState.permissions.isBatteryOptimizationIgnored) ShieldEmerald
                                else ShieldAmber,
                        )
                        Text(
                            text = stringResource(R.string.battery_opt_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Text(
                        text =
                            if (uiState.permissions.isBatteryOptimizationIgnored) {
                                stringResource(R.string.battery_unrestricted)
                            } else {
                                stringResource(R.string.battery_restricted)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (uiState.permissions.isBatteryOptimizationIgnored) ShieldEmerald
                            else ShieldAmber,
                    )

                    if (!uiState.permissions.isBatteryOptimizationIgnored) {
                        OutlinedButton(
                            onClick = onRequestBatteryOptimization,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("settings_battery_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        ) {
                            Text(text = stringResource(R.string.disable_battery_opt))
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = ShieldEmerald,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.auto_start_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AiVerificationSettingsCard(
    uiState: MainUiState,
    onToggleAiVerification: (Boolean) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onChangeWhenUnsure: (UnsurePolicy) -> Unit,
    onTestAiConnection: () -> Unit,
    onClearCache: () -> Unit,
) {
    var apiKeyDraft by remember { mutableStateOf(uiState.geminiApiKey) }
    var modelDraft by remember { mutableStateOf(uiState.geminiModel) }
    var showApiKey by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.geminiApiKey) {
        if (apiKeyDraft != uiState.geminiApiKey && uiState.geminiApiKey.isNotEmpty()) {
            apiKeyDraft = uiState.geminiApiKey
        }
    }
    LaunchedEffect(uiState.geminiModel) {
        if (modelDraft != uiState.geminiModel) {
            modelDraft = uiState.geminiModel
        }
    }

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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = ShieldCyan,
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.ai_verification_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Verifies unknown domains within 3s via Gemini",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = uiState.aiVerificationEnabled,
                    onCheckedChange = onToggleAiVerification,
                    modifier = Modifier.testTag("ai_verification_switch"),
                )
            }

            // Gemini API Key (masked)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    modifier = Modifier.weight(1f).testTag("gemini_api_key_input"),
                    label = { Text(stringResource(R.string.gemini_api_key_label)) },
                    placeholder = { Text(stringResource(R.string.gemini_api_key_hint)) },
                    singleLine = true,
                    visualTransformation =
                        if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { showApiKey = !showApiKey },
                            modifier = Modifier.testTag("toggle_api_key_visibility"),
                        ) {
                            Icon(
                                imageVector =
                                    if (showApiKey) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                contentDescription =
                                    if (showApiKey) "Hide API key" else "Show API key",
                            )
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { onSaveApiKey(apiKeyDraft) }),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ShieldCyan,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                )

                Button(
                    onClick = { onSaveApiKey(apiKeyDraft) },
                    modifier = Modifier.height(52.dp).testTag("save_api_key_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = ShieldCyan.copy(alpha = 0.2f),
                            contentColor = ShieldCyan,
                        ),
                ) {
                    Text(stringResource(R.string.save_button))
                }
            }

            // Model Name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it },
                    modifier = Modifier.weight(1f).testTag("gemini_model_input"),
                    label = { Text(stringResource(R.string.gemini_model_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveModel(modelDraft) }),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ShieldCyan,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                )

                Button(
                    onClick = { onSaveModel(modelDraft) },
                    modifier = Modifier.height(52.dp).testTag("save_model_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = ShieldCyan.copy(alpha = 0.2f),
                            contentColor = ShieldCyan,
                        ),
                ) {
                    Text(stringResource(R.string.save_button))
                }
            }

            // When unsure: Allow / Block
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.when_unsure_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = uiState.whenUnsure == UnsurePolicy.ALLOW,
                        onClick = { onChangeWhenUnsure(UnsurePolicy.ALLOW) },
                        label = { Text(stringResource(R.string.when_unsure_allow)) },
                        modifier = Modifier.weight(1f).testTag("unsure_allow_chip"),
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ShieldEmerald.copy(alpha = 0.2f),
                                selectedLabelColor = ShieldEmerald,
                            ),
                    )
                    FilterChip(
                        selected = uiState.whenUnsure == UnsurePolicy.BLOCK,
                        onClick = { onChangeWhenUnsure(UnsurePolicy.BLOCK) },
                        label = { Text(stringResource(R.string.when_unsure_block)) },
                        modifier = Modifier.weight(1f).testTag("unsure_block_chip"),
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ShieldCoral.copy(alpha = 0.2f),
                                selectedLabelColor = ShieldCoral,
                            ),
                    )
                }
            }

            // Test AI Connection & Clear Cache buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onTestAiConnection,
                    enabled = uiState.aiTestState !is AiTestState.Running,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("test_ai_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ShieldCyan.copy(alpha = 0.6f)),
                ) {
                    if (uiState.aiTestState is AiTestState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ShieldCyan,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Testing…", maxLines = 1)
                    } else {
                        Text(
                            text = stringResource(R.string.test_ai_connection),
                            color = ShieldCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onClearCache,
                    modifier = Modifier.height(48.dp).testTag("clear_cache_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear cache (${uiState.cacheSize})")
                }
            }

            AnimatedVisibility(visible = uiState.aiTestState is AiTestState.Result) {
                val res = uiState.aiTestState as? AiTestState.Result
                if (res != null) {
                    val color = if (res.isSuccess) ShieldEmerald else ShieldCoral
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("test_ai_result"),
                        shape = RoundedCornerShape(10.dp),
                        color = color.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
                    ) {
                        Text(
                            text = res.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckDomainCard(
    domainCheckState: DomainCheckState,
    onCheckDomain: (String) -> Unit,
) {
    var domainInput by remember { mutableStateOf("") }

    val submit = {
        if (domainInput.isNotBlank()) {
            onCheckDomain(domainInput)
        }
    }

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
                    imageVector = Icons.Filled.Policy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.check_domain_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = "Inspect how DNS Shield classifies any domain without opening or visiting it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    modifier = Modifier.weight(1f).testTag("check_domain_input"),
                    placeholder = { Text(stringResource(R.string.check_domain_hint)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                )

                Button(
                    onClick = submit,
                    enabled = domainCheckState !is DomainCheckState.Checking,
                    modifier = Modifier.height(52.dp).testTag("check_domain_button"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (domainCheckState is DomainCheckState.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.check_domain_button))
                    }
                }
            }

            AnimatedVisibility(visible = domainCheckState is DomainCheckState.Result) {
                val res = (domainCheckState as? DomainCheckState.Result)?.summary
                if (res != null) {
                    val verdictColor =
                        when (res.verdict) {
                            DomainVerdict.SAFE -> ShieldEmerald
                            DomainVerdict.ADULT -> ShieldCoral
                            DomainVerdict.UNSURE -> ShieldAmber
                        }
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("check_domain_result"),
                        shape = RoundedCornerShape(12.dp),
                        color = verdictColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, verdictColor.copy(alpha = 0.45f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Verdict: ${res.verdict.name}",
                                style = MaterialTheme.typography.labelLarge,
                                color = verdictColor,
                            )
                            Text(
                                text = "Reason: ${res.reasonLabel} • Time: ${res.latencyMs} ms",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (!res.errorDetail.isNullOrBlank()) {
                                Text(
                                    text = res.errorDetail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainRuleSectionCard(
    title: String,
    subtitle: String,
    hint: String,
    icon: ImageVector,
    accent: Color,
    rules: List<CustomRuleEntity>,
    inputTestTag: String,
    addButtonTestTag: String,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var inputText by remember { mutableStateOf("") }

    val submit = {
        if (inputText.isNotBlank()) {
            onAdd(inputText)
            inputText = ""
        }
    }

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
                Icon(imageVector = icon, contentDescription = null, tint = accent)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).testTag(inputTestTag),
                    placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                )

                Button(
                    onClick = submit,
                    modifier = Modifier.height(52.dp).testTag(addButtonTestTag),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = accent.copy(alpha = 0.2f),
                            contentColor = accent,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_domain),
                    )
                }
            }

            if (rules.isEmpty()) {
                Text(
                    text = "No domains added yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rules.forEach { rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = rule.domain,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { onRemove(rule.domain) },
                                modifier = Modifier.testTag("remove_rule_${rule.domain}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove ${rule.domain}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TamperProtectionSettingsCard(
    uiState: MainUiState,
    onOpenProtectionWizard: () -> Unit,
    onOpenUnlockFlow: () -> Unit,
    onOpenChangePin: () -> Unit,
    onSelectWaitDuration: suspend (WaitDurationOption) -> WaitTimeUpdateResult,
    onShowFeedback: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tamper = uiState.tamperState

    Card(
        modifier = Modifier.fillMaxWidth().testTag("settings_tamper_protection_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = ShieldAmber,
                    )
                    Column {
                        Text(
                            text = "Tamper Protection",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "PIN lock, cooling-off timer, Device Admin & Guard",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ProtectionLevelBadge(level = uiState.protectionLevel)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Cooling-off wait time (raising allowed anytime; lowering only when unlocked)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WaitDurationOption.entries.forEach { option ->
                        FilterChip(
                            selected = tamper.waitDuration == option,
                            onClick = {
                                scope.launch {
                                    when (val res = onSelectWaitDuration(option)) {
                                        WaitTimeUpdateResult.Success ->
                                            onShowFeedback("Wait time updated to ${option.label}")
                                        is WaitTimeUpdateResult.Rejected ->
                                            onShowFeedback(res.reason)
                                    }
                                }
                            },
                            label = { Text(option.label) },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ShieldEmerald.copy(alpha = 0.2f),
                                    selectedLabelColor = ShieldEmerald,
                                ),
                            modifier = Modifier.testTag("settings_wait_${option.name}"),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenProtectionWizard,
                    modifier =
                        Modifier.weight(1f)
                            .height(48.dp)
                            .testTag("settings_open_wizard_button"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Setup & Checklist", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (tamper.isPinConfigured) {
                    OutlinedButton(
                        onClick = {
                            if (tamper.protectionSetupDone && !tamper.isUnlockWindowActive) {
                                onShowFeedback(
                                    "PIN can only be changed during an active 15-minute unlock window."
                                )
                            } else {
                                onOpenChangePin()
                            }
                        },
                        modifier =
                            Modifier.weight(1f)
                                .height(48.dp)
                                .testTag("settings_change_pin_button"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.change_pin_button), maxLines = 1)
                    }
                }
            }

            if (tamper.protectionSetupDone || tamper.isUnlockWindowActive || tamper.isCountdownActive) {
                Button(
                    onClick = onOpenUnlockFlow,
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_unlock_flow_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = ShieldAmber.copy(alpha = 0.2f),
                            contentColor = ShieldAmber,
                        ),
                ) {
                    Text(
                        text =
                            when {
                                tamper.isUnlockWindowActive -> "Unlock Window Active (Manage / Lock)"
                                tamper.isCountdownActive -> "Countdown Active (View / Cancel)"
                                tamper.isCountdownReadyToUnlock -> "Enter PIN to Open Unlock Window"
                                else -> stringResource(R.string.request_turn_off_button)
                            }
                    )
                }
            }
        }
    }
}

