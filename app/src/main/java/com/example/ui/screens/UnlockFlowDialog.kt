package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.protection.PinActionResult
import com.example.protection.PinHasher
import com.example.protection.ProtectionNotificationHelper
import com.example.protection.TamperProtectionState
import com.example.ui.theme.ShieldAmber
import com.example.ui.theme.ShieldCoral
import com.example.ui.theme.ShieldEmerald
import kotlinx.coroutines.launch

@Composable
fun UnlockFlowDialog(
    tamperState: TamperProtectionState,
    onRequestTurnOff: suspend (String) -> PinActionResult,
    onConfirmUnlockWindow: suspend (String) -> PinActionResult,
    onCancelRequest: () -> Unit,
    onLockAgain: () -> Unit,
    onDeactivateProtection: () -> Unit,
    formatError: (PinActionResult) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    when {
                        tamperState.isUnlockWindowActive -> "Unlock Window Active"
                        tamperState.isCountdownReadyToUnlock -> "Enter PIN to Unlock"
                        tamperState.isCountdownActive -> "Cooling-Off Countdown Active"
                        else -> stringResource(R.string.request_turn_off_button)
                    },
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    tamperState.isUnlockWindowActive -> {
                        val rem =
                            ProtectionNotificationHelper.formatDurationShort(
                                tamperState.unlockWindowRemainingMs
                            )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ShieldEmerald.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ShieldEmerald.copy(alpha = 0.45f)),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "15-Minute Unlock Window",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ShieldEmerald,
                                )
                                Text(
                                    text = "Remaining: $rem",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text =
                                        "Settings Guard is paused. You can turn off VPN, change PIN, lower wait time, disable Device Admin, or uninstall.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                onLockAgain()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("dialog_lock_again_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.lock_again_button))
                        }

                        OutlinedButton(
                            onClick = {
                                onDeactivateProtection()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("dialog_deactivate_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ShieldCoral),
                            border = BorderStroke(1.dp, ShieldCoral.copy(alpha = 0.6f)),
                        ) {
                            Text("Disable Tamper Protection")
                        }
                    }

                    tamperState.isCountdownActive -> {
                        val rem =
                            ProtectionNotificationHelper.formatDurationShort(
                                tamperState.countdownRemainingMs
                            )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ShieldAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ShieldAmber.copy(alpha = 0.45f)),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Time remaining until unlock:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ShieldAmber,
                                )
                                Text(
                                    text = rem,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text =
                                        "Protection stays ON during cooling-off. Once the timer finishes, enter your PIN again to open a 15-minute unlock window.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    else -> {
                        val isSecondStep = tamperState.isCountdownReadyToUnlock
                        Text(
                            text =
                                if (isSecondStep) {
                                    "Cooling-off countdown finished. Enter your 6-digit PIN again to open the 15-minute unlock window."
                                } else {
                                    "Enter your 6-digit PIN to start the ${tamperState.waitDuration.label} cooling-off countdown."
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (tamperState.isLockedOut) {
                            val lockRem =
                                ProtectionNotificationHelper.formatDurationShort(
                                    tamperState.lockoutRemainingMs
                                )
                            Text(
                                text = "Too many wrong PIN attempts. Locked for $lockRem.",
                                style = MaterialTheme.typography.labelLarge,
                                color = ShieldCoral,
                            )
                        }

                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { input ->
                                if (input.length <= PinHasher.PIN_LENGTH && input.all { it.isDigit() }) {
                                    pinInput = input
                                    errorMessage = null
                                }
                            },
                            label = { Text("6-digit PIN") },
                            singleLine = true,
                            enabled = !tamperState.isLockedOut,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth().testTag("unlock_pin_input"),
                            shape = RoundedCornerShape(12.dp),
                        )

                        errorMessage?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelLarge,
                                color = ShieldCoral,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                tamperState.isUnlockWindowActive -> {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                tamperState.isCountdownActive -> {
                    Button(
                        onClick = {
                            onCancelRequest()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ShieldCoral),
                        modifier = Modifier.testTag("dialog_cancel_countdown_button"),
                    ) {
                        Text(stringResource(R.string.cancel_request_button))
                    }
                }
                else -> {
                    Button(
                        enabled =
                            !tamperState.isLockedOut && pinInput.length == PinHasher.PIN_LENGTH,
                        onClick = {
                            scope.launch {
                                val res =
                                    if (tamperState.isCountdownReadyToUnlock) {
                                        onConfirmUnlockWindow(pinInput)
                                    } else {
                                        onRequestTurnOff(pinInput)
                                    }
                                if (res is PinActionResult.Success) {
                                    pinInput = ""
                                    errorMessage = null
                                } else {
                                    errorMessage = formatError(res)
                                }
                            }
                        },
                        modifier = Modifier.testTag("dialog_submit_pin_button"),
                    ) {
                        Text(
                            if (tamperState.isCountdownReadyToUnlock) "Unlock (15 min)"
                            else "Start Countdown"
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (tamperState.isCountdownReadyToUnlock) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onCancelRequest()
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.cancel_request_button), color = ShieldCoral)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            } else if (!tamperState.isUnlockWindowActive) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
fun ChangePinDialog(
    tamperState: TamperProtectionState,
    onChangePin: suspend (currentPin: String, newPin: String) -> PinActionResult,
    formatError: (PinActionResult) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Protection PIN", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tamperState.protectionSetupDone && !tamperState.isUnlockWindowActive) {
                    Text(
                        text =
                            "PIN can only be changed during an active 15-minute unlock window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShieldCoral,
                    )
                } else {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { input ->
                            if (input.length <= PinHasher.PIN_LENGTH && input.all { it.isDigit() }) {
                                currentPin = input
                                feedback = null
                            }
                        },
                        label = { Text("Current 6-digit PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag("change_pin_current"),
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { input ->
                            if (input.length <= PinHasher.PIN_LENGTH && input.all { it.isDigit() }) {
                                newPin = input
                                feedback = null
                            }
                        },
                        label = { Text("New 6-digit PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag("change_pin_new"),
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { input ->
                            if (input.length <= PinHasher.PIN_LENGTH && input.all { it.isDigit() }) {
                                confirmPin = input
                                feedback = null
                            }
                        },
                        label = { Text("Confirm new 6-digit PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag("change_pin_confirm"),
                    )
                }

                feedback?.let { (msg, ok) ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ok) ShieldEmerald else ShieldCoral,
                    )
                }
            }
        },
        confirmButton = {
            if (!tamperState.protectionSetupDone || tamperState.isUnlockWindowActive) {
                Button(
                    onClick = {
                        when {
                            !PinHasher.isValidPinFormat(currentPin) ||
                                !PinHasher.isValidPinFormat(newPin) -> {
                                feedback = "Both PINs must be 6 digits." to false
                            }
                            newPin != confirmPin -> {
                                feedback = "New PINs do not match." to false
                            }
                            else -> {
                                scope.launch {
                                    when (val res = onChangePin(currentPin, newPin)) {
                                        PinActionResult.Success -> {
                                            feedback = "PIN updated." to true
                                            onDismiss()
                                        }
                                        else -> {
                                            feedback = (formatError(res) ?: "Failed") to false
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("change_pin_save_button"),
                ) {
                    Text("Save PIN")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
