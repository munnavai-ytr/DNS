package com.example.protection

import android.os.SystemClock

enum class WaitDurationOption(val durationMs: Long, val label: String) {
    TEN_MINUTES(10L * 60L * 1000L, "10 minutes (Testing)"),
    ONE_HOUR(60L * 60L * 1000L, "1 hour"),
    TWENTY_FOUR_HOURS(24L * 60L * 60L * 1000L, "24 hours"),
    SEVENTY_TWO_HOURS(72L * 60L * 60L * 1000L, "72 hours");

    companion object {
        val DEFAULT = TWENTY_FOUR_HOURS

        fun fromDurationMs(durationMs: Long): WaitDurationOption {
            return entries.firstOrNull { it.durationMs == durationMs } ?: DEFAULT
        }
    }
}

enum class ProtectionLevel {
    ON,
    WEAK,
    OFF,
}

data class ProtectionChecklistState(
    val pinConfigured: Boolean = false,
    val waitDurationConfigured: Boolean = false,
    val deviceAdminEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val vpnRunning: Boolean = false,
    val vpnAlwaysOn: Boolean = false,
    val vpnLockdownEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
) {
    /**
     * Core tamper protection counts as ON only after steps 1-4 are done:
     * 1. PIN configured
     * 2. Wait time configured
     * 3. Device Admin active
     * 4. Accessibility Guard enabled
     */
    val isCoreStepsComplete: Boolean
        get() =
            pinConfigured &&
                waitDurationConfigured &&
                deviceAdminEnabled &&
                accessibilityEnabled

    val isAlwaysOnAndLockdownOk: Boolean
        get() = vpnAlwaysOn && vpnLockdownEnabled
}

data class TamperProtectionState(
    val isPinConfigured: Boolean = false,
    val protectionSetupDone: Boolean = false,
    val waitDuration: WaitDurationOption = WaitDurationOption.DEFAULT,
    val protectionLevel: ProtectionLevel = ProtectionLevel.OFF,
    val isCountdownActive: Boolean = false,
    val countdownRemainingMs: Long = 0L,
    val isCountdownReadyToUnlock: Boolean = false,
    val isUnlockWindowActive: Boolean = false,
    val unlockWindowRemainingMs: Long = 0L,
    val isLockedOut: Boolean = false,
    val lockoutRemainingMs: Long = 0L,
    val failedPinAttempts: Int = 0,
    val lockoutTier: Int = 0,
) {
    val isGuardActive: Boolean
        get() = protectionSetupDone && !isUnlockWindowActive

    val canTurnOffVpnDirectly: Boolean
        get() = !protectionSetupDone || isUnlockWindowActive
}

sealed interface PinActionResult {
    data object Success : PinActionResult

    data class InvalidPin(val attemptsRemaining: Int) : PinActionResult

    data class LockedOut(val remainingMs: Long) : PinActionResult

    data class Rejected(val reason: String) : PinActionResult
}

sealed interface WaitTimeUpdateResult {
    data object Success : WaitTimeUpdateResult

    data class Rejected(val reason: String) : WaitTimeUpdateResult
}

interface ProtectionClock {
    fun wallTimeMs(): Long

    fun elapsedRealtimeMs(): Long
}

object SystemProtectionClock : ProtectionClock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
