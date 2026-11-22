package com.example.protection

import android.os.SystemClock

enum class ProtectionMode(val label: String) {
    VPN("VPN Mode"),
    PRIVATE_DNS("Private DNS Mode (Lite)");

    companion object {
        fun fromStored(raw: String?): ProtectionMode {
            return entries.firstOrNull { it.name == raw } ?: VPN
        }
    }
}

enum class LockType(val label: String) {
    NONE("Not Configured"),
    TRUSTED_PIN("Trusted PIN"),
    STRICT_LOCK("Strict Lock");

    companion object {
        fun fromStored(raw: String?): LockType {
            return entries.firstOrNull { it.name == raw } ?: NONE
        }
    }
}

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
    val protectionMode: ProtectionMode = ProtectionMode.VPN,
    val modeConfigured: Boolean = false,
    val lockType: LockType = LockType.NONE,
    val pinConfigured: Boolean = false,
    val waitDurationConfigured: Boolean = false,
    val deviceAdminEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val screenGuardEnabled: Boolean = true,
    val screenGuardRecentlyActive: Boolean = false,
    val vpnRunning: Boolean = false,
    val vpnAlwaysOn: Boolean = false,
    val privateDnsConfigured: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
) {
    val isLockStepComplete: Boolean
        get() =
            when (lockType) {
                LockType.TRUSTED_PIN -> pinConfigured && waitDurationConfigured
                LockType.STRICT_LOCK -> true
                LockType.NONE -> false
            }

    /**
     * Protection counts as ON only after Steps 1-4 are done:
     * 1. Protection Mode configured
     * 2. Lock Type configured (Trusted PIN + wait time, or Strict Lock)
     * 3. Device Admin active
     * 4. Accessibility Guard enabled
     */
    val isCoreStepsComplete: Boolean
        get() =
            modeConfigured &&
                isLockStepComplete &&
                deviceAdminEnabled &&
                accessibilityEnabled

    val isModeActiveOk: Boolean
        get() =
            when (protectionMode) {
                ProtectionMode.VPN -> vpnRunning
                ProtectionMode.PRIVATE_DNS -> privateDnsConfigured
            }
}

data class TamperProtectionState(
    val protectionMode: ProtectionMode = ProtectionMode.VPN,
    val modeConfigured: Boolean = false,
    val lockType: LockType = LockType.NONE,
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
    val isStrictLock: Boolean
        get() = lockType == LockType.STRICT_LOCK

    val isGuardActive: Boolean
        get() =
            protectionSetupDone &&
                (lockType == LockType.STRICT_LOCK || !isUnlockWindowActive)

    val canTurnOffVpnDirectly: Boolean
        get() =
            !protectionSetupDone ||
                (lockType == LockType.TRUSTED_PIN && isUnlockWindowActive)

    val canChangeLockOrMode: Boolean
        get() =
            !protectionSetupDone ||
                (lockType == LockType.TRUSTED_PIN && isUnlockWindowActive)
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
