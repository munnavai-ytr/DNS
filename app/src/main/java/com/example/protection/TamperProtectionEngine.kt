package com.example.protection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TimerTransitions(
    val countdownCompleted: Boolean = false,
    val unlockWindowEnded: Boolean = false,
)

class TamperProtectionEngine(
    private val store: ProtectionStateStore,
    private val clock: ProtectionClock = SystemProtectionClock,
) {
    private val mutex = Mutex()
    private var pendingCountdownCompleted = false
    private var pendingUnlockWindowEnded = false

    suspend fun consumePendingTransitions(): TimerTransitions =
        mutex.withLock {
            val transitions =
                TimerTransitions(
                    countdownCompleted = pendingCountdownCompleted,
                    unlockWindowEnded = pendingUnlockWindowEnded,
                )
            pendingCountdownCompleted = false
            pendingUnlockWindowEnded = false
            transitions
        }

    suspend fun getState(): TamperProtectionState =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            toPublicState(synced)
        }

    suspend fun getRawState(): PersistedTamperState =
        mutex.withLock { syncTimeLocked(store.readState()) }

    suspend fun markWizardPromptedAfterOnboarding() =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            if (!synced.wizardPromptedAfterOnboarding) {
                store.writeState(synced.copy(wizardPromptedAfterOnboarding = true))
            }
        }

    suspend fun setProtectionMode(mode: ProtectionMode): WaitTimeUpdateResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            val lockType = LockType.fromStored(synced.lockTypeName)
            if (synced.protectionActivated && lockType == LockType.STRICT_LOCK) {
                return@withLock WaitTimeUpdateResult.Rejected(
                    "Strict Lock is active. Protection mode cannot be changed."
                )
            }
            if (synced.protectionActivated && !synced.unlockWindowActive) {
                return@withLock WaitTimeUpdateResult.Rejected(
                    "Protection mode can only be changed during an active 15-minute unlock window."
                )
            }
            store.writeState(
                synced.copy(
                    protectionModeName = mode.name,
                    modeExplicitlyChosen = true,
                )
            )
            WaitTimeUpdateResult.Success
        }

    suspend fun setInitialPin(pin: String): PinActionResult =
        mutex.withLock {
            if (!PinHasher.isValidPinFormat(pin)) {
                return@withLock PinActionResult.Rejected("PIN must be 6 digits.")
            }
            val synced = syncTimeLocked(store.readState())
            val lockType = LockType.fromStored(synced.lockTypeName)
            if (synced.protectionActivated && lockType == LockType.STRICT_LOCK) {
                return@withLock PinActionResult.Rejected(
                    "Strict Lock is permanent. Lock type and PIN cannot be set or changed."
                )
            }
            if (synced.protectionActivated && !synced.unlockWindowActive) {
                return@withLock PinActionResult.Rejected(
                    "PIN can only be changed during an active 15-minute unlock window."
                )
            }
            val (salt, hash) = PinHasher.hashPin(pin)
            val updated =
                synced.copy(
                    lockTypeName = LockType.TRUSTED_PIN.name,
                    pinSaltBase64 = salt,
                    pinHashBase64 = hash,
                    failedPinAttempts = 0,
                    lockoutTier = 0,
                    lockoutRemainingMs = 0L,
                )
            store.writeState(updated)
            PinActionResult.Success
        }

    suspend fun activateStrictLock(confirmationPhrase: String): PinActionResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            val currentLock = LockType.fromStored(synced.lockTypeName)
            if (synced.protectionActivated && currentLock == LockType.STRICT_LOCK) {
                return@withLock PinActionResult.Rejected("Strict Lock is already active.")
            }
            if (synced.protectionActivated && !synced.unlockWindowActive) {
                return@withLock PinActionResult.Rejected(
                    "Lock type can only be changed during an active 15-minute unlock window."
                )
            }
            if (confirmationPhrase.trim() != STRICT_LOCK_CONFIRMATION_PHRASE) {
                return@withLock PinActionResult.Rejected(
                    "Type exact phrase \"$STRICT_LOCK_CONFIRMATION_PHRASE\" to confirm Strict Lock."
                )
            }
            val updated =
                synced.copy(
                    lockTypeName = LockType.STRICT_LOCK.name,
                    pinSaltBase64 = "",
                    pinHashBase64 = "",
                    countdownActive = false,
                    countdownRemainingMs = 0L,
                    countdownReadyForSecondPin = false,
                    unlockWindowActive = false,
                    unlockWindowRemainingMs = 0L,
                    failedPinAttempts = 0,
                    lockoutTier = 0,
                    lockoutRemainingMs = 0L,
                )
            store.writeState(updated)
            pendingCountdownCompleted = false
            pendingUnlockWindowEnded = false
            PinActionResult.Success
        }

    suspend fun changePin(currentPin: String, newPin: String): PinActionResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            if (LockType.fromStored(synced.lockTypeName) == LockType.STRICT_LOCK) {
                return@withLock PinActionResult.Rejected(
                    "Strict Lock has no PIN and no unlock path."
                )
            }
            if (synced.pinHashBase64.isBlank()) {
                return@withLock PinActionResult.Rejected("No PIN is currently configured.")
            }
            if (synced.protectionActivated && !synced.unlockWindowActive) {
                return@withLock PinActionResult.Rejected(
                    "PIN can only be changed during an active 15-minute unlock window."
                )
            }
            if (!PinHasher.isValidPinFormat(newPin)) {
                return@withLock PinActionResult.Rejected("New PIN must be 6 digits.")
            }
            if (synced.lockoutRemainingMs > 0L) {
                return@withLock PinActionResult.LockedOut(synced.lockoutRemainingMs)
            }
            if (!PinHasher.verifyPin(currentPin, synced.pinSaltBase64, synced.pinHashBase64)) {
                return@withLock recordFailedAttemptLocked(synced)
            }
            val (salt, hash) = PinHasher.hashPin(newPin)
            store.writeState(
                synced.copy(
                    pinSaltBase64 = salt,
                    pinHashBase64 = hash,
                    failedPinAttempts = 0,
                    lockoutTier = 0,
                    lockoutRemainingMs = 0L,
                )
            )
            PinActionResult.Success
        }

    suspend fun setWaitDuration(option: WaitDurationOption): WaitTimeUpdateResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            if (
                synced.protectionActivated &&
                    LockType.fromStored(synced.lockTypeName) == LockType.STRICT_LOCK
            ) {
                return@withLock WaitTimeUpdateResult.Rejected(
                    "Strict Lock has no unlock path or cooling-off wait time."
                )
            }
            val isLowering = option.durationMs < synced.waitDurationMs
            if (isLowering && synced.protectionActivated && !synced.unlockWindowActive) {
                return@withLock WaitTimeUpdateResult.Rejected(
                    "Wait time can only be lowered during an active 15-minute unlock window."
                )
            }
            store.writeState(
                synced.copy(
                    waitDurationMs = option.durationMs,
                    waitDurationExplicitlyChosen = true,
                )
            )
            WaitTimeUpdateResult.Success
        }

    suspend fun confirmWaitDurationSelection(): TamperProtectionState =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            val updated = synced.copy(waitDurationExplicitlyChosen = true)
            store.writeState(updated)
            toPublicState(updated)
        }

    suspend fun syncCoreProtectionActivation(coreStepsComplete: Boolean): TamperProtectionState =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            val updated =
                if (coreStepsComplete && !synced.protectionActivated) {
                    synced.copy(
                        protectionActivated = true,
                        wizardPromptedAfterOnboarding = true,
                    )
                } else {
                    synced
                }
            if (updated != synced) {
                store.writeState(updated)
            }
            toPublicState(updated)
        }

    suspend fun requestTurnOffProtection(pin: String): PinActionResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            if (LockType.fromStored(synced.lockTypeName) == LockType.STRICT_LOCK) {
                return@withLock PinActionResult.Rejected(
                    "Strict Lock is permanent. There is no unlock path; only a factory reset removes DNS Shield."
                )
            }
            if (synced.pinHashBase64.isBlank()) {
                return@withLock PinActionResult.Rejected("Protection PIN is not set.")
            }
            if (synced.lockoutRemainingMs > 0L) {
                return@withLock PinActionResult.LockedOut(synced.lockoutRemainingMs)
            }
            if (!PinHasher.verifyPin(pin, synced.pinSaltBase64, synced.pinHashBase64)) {
                return@withLock recordFailedAttemptLocked(synced)
            }

            val updated =
                synced.copy(
                    failedPinAttempts = 0,
                    lockoutTier = 0,
                    lockoutRemainingMs = 0L,
                    countdownActive = true,
                    countdownRemainingMs = synced.waitDurationMs,
                    countdownReadyForSecondPin = false,
                    unlockWindowActive = false,
                    unlockWindowRemainingMs = 0L,
                )
            store.writeState(updated)
            pendingCountdownCompleted = false
            pendingUnlockWindowEnded = false
            PinActionResult.Success
        }

    suspend fun confirmUnlockWindow(pin: String): PinActionResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            if (LockType.fromStored(synced.lockTypeName) == LockType.STRICT_LOCK) {
                return@withLock PinActionResult.Rejected(
                    "Strict Lock has no unlock path."
                )
            }
            if (!synced.countdownReadyForSecondPin) {
                return@withLock PinActionResult.Rejected(
                    if (synced.countdownActive) {
                        "Cooling-off countdown has not finished yet."
                    } else {
                        "Start a turn-off request first."
                    }
                )
            }
            if (synced.lockoutRemainingMs > 0L) {
                return@withLock PinActionResult.LockedOut(synced.lockoutRemainingMs)
            }
            if (!PinHasher.verifyPin(pin, synced.pinSaltBase64, synced.pinHashBase64)) {
                return@withLock recordFailedAttemptLocked(synced)
            }

            val updated =
                synced.copy(
                    failedPinAttempts = 0,
                    lockoutTier = 0,
                    lockoutRemainingMs = 0L,
                    countdownActive = false,
                    countdownRemainingMs = 0L,
                    countdownReadyForSecondPin = false,
                    unlockWindowActive = true,
                    unlockWindowRemainingMs = UNLOCK_WINDOW_DURATION_MS,
                )
            store.writeState(updated)
            pendingCountdownCompleted = false
            pendingUnlockWindowEnded = false
            PinActionResult.Success
        }

    suspend fun cancelTurnOffRequest(): TamperProtectionState =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            val updated =
                synced.copy(
                    countdownActive = false,
                    countdownRemainingMs = 0L,
                    countdownReadyForSecondPin = false,
                )
            store.writeState(updated)
            pendingCountdownCompleted = false
            toPublicState(updated)
        }

    suspend fun lockAgainNow(): TamperProtectionState =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            val updated =
                synced.copy(
                    unlockWindowActive = false,
                    unlockWindowRemainingMs = 0L,
                )
            store.writeState(updated)
            pendingUnlockWindowEnded = false
            toPublicState(updated)
        }

    suspend fun deactivateProtectionInUnlockWindow(): Boolean =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
            if (LockType.fromStored(synced.lockTypeName) == LockType.STRICT_LOCK) {
                return@withLock false
            }
            if (synced.protectionActivated && !synced.unlockWindowActive) {
                return@withLock false
            }
            val updated =
                synced.copy(
                    protectionActivated = false,
                    countdownActive = false,
                    countdownRemainingMs = 0L,
                    countdownReadyForSecondPin = false,
                    unlockWindowActive = false,
                    unlockWindowRemainingMs = 0L,
                )
            store.writeState(updated)
            pendingCountdownCompleted = false
            pendingUnlockWindowEnded = false
            true
        }

    fun computeProtectionLevel(
        checklist: ProtectionChecklistState,
        state: TamperProtectionState,
    ): ProtectionLevel {
        if (!state.protectionSetupDone && !checklist.isCoreStepsComplete) {
            return ProtectionLevel.OFF
        }
        if (state.lockType == LockType.TRUSTED_PIN && state.isUnlockWindowActive) {
            return ProtectionLevel.OFF
        }
        return if (checklist.isCoreStepsComplete && checklist.isModeActiveOk) {
            ProtectionLevel.ON
        } else {
            ProtectionLevel.WEAK
        }
    }

    private suspend fun recordFailedAttemptLocked(synced: PersistedTamperState): PinActionResult {
        val nextAttempts = synced.failedPinAttempts + 1
        return if (nextAttempts >= MAX_FAILED_PIN_ATTEMPTS) {
            val nextTier = synced.lockoutTier + 1
            val shift = (nextTier - 1).coerceIn(0, MAX_LOCKOUT_SHIFT)
            val lockoutMs = BASE_LOCKOUT_DURATION_MS shl shift
            store.writeState(
                synced.copy(
                    failedPinAttempts = 0,
                    lockoutTier = nextTier,
                    lockoutRemainingMs = lockoutMs,
                )
            )
            PinActionResult.LockedOut(lockoutMs)
        } else {
            store.writeState(synced.copy(failedPinAttempts = nextAttempts))
            PinActionResult.InvalidPin(attemptsRemaining = MAX_FAILED_PIN_ATTEMPTS - nextAttempts)
        }
    }

    private suspend fun syncTimeLocked(raw: PersistedTamperState): PersistedTamperState {
        val wallNow = clock.wallTimeMs().coerceAtLeast(0L)
        val elapsedNow = clock.elapsedRealtimeMs().coerceAtLeast(0L)

        if (raw.highestWallClockMs == 0L) {
            val initialized =
                raw.copy(
                    highestWallClockMs = wallNow,
                    lastElapsedRealtimeMs = elapsedNow,
                )
            if (initialized != raw) {
                store.writeState(initialized)
            }
            return initialized
        }

        val prevHighestWall = raw.highestWallClockMs
        val newHighestWall = maxOf(prevHighestWall, wallNow)

        val advanceMs =
            if (elapsedNow >= raw.lastElapsedRealtimeMs) {
                elapsedNow - raw.lastElapsedRealtimeMs
            } else {
                val wallDelta = (newHighestWall - prevHighestWall).coerceAtLeast(0L)
                maxOf(wallDelta, elapsedNow)
            }

        var nextLockoutRem = raw.lockoutRemainingMs
        var nextCountdownActive = raw.countdownActive
        var nextCountdownRem = raw.countdownRemainingMs
        var nextCountdownReady = raw.countdownReadyForSecondPin
        var nextUnlockActive = raw.unlockWindowActive
        var nextUnlockRem = raw.unlockWindowRemainingMs

        if (advanceMs > 0L) {
            if (nextLockoutRem > 0L) {
                nextLockoutRem = (nextLockoutRem - advanceMs).coerceAtLeast(0L)
            }
            if (nextCountdownActive) {
                nextCountdownRem = (nextCountdownRem - advanceMs).coerceAtLeast(0L)
                if (nextCountdownRem == 0L) {
                    nextCountdownActive = false
                    nextCountdownReady = true
                }
            }
            if (nextUnlockActive) {
                nextUnlockRem = (nextUnlockRem - advanceMs).coerceAtLeast(0L)
                if (nextUnlockRem == 0L) {
                    nextUnlockActive = false
                }
            }
        }

        val lockType = LockType.fromStored(raw.lockTypeName)
        if (lockType == LockType.STRICT_LOCK) {
            nextCountdownActive = false
            nextCountdownRem = 0L
            nextCountdownReady = false
            nextUnlockActive = false
            nextUnlockRem = 0L
        } else {
            if (raw.countdownActive && !nextCountdownActive && nextCountdownReady) {
                pendingCountdownCompleted = true
            }
            if (raw.unlockWindowActive && !nextUnlockActive) {
                pendingUnlockWindowEnded = true
            }
        }

        val synced =
            raw.copy(
                highestWallClockMs = newHighestWall,
                lastElapsedRealtimeMs = elapsedNow,
                lockoutRemainingMs = nextLockoutRem,
                countdownActive = nextCountdownActive,
                countdownRemainingMs = nextCountdownRem,
                countdownReadyForSecondPin = nextCountdownReady,
                unlockWindowActive = nextUnlockActive,
                unlockWindowRemainingMs = nextUnlockRem,
            )

        if (synced != raw) {
            store.writeState(synced)
        }
        return synced
    }

    private fun toPublicState(raw: PersistedTamperState): TamperProtectionState {
        val mode = ProtectionMode.fromStored(raw.protectionModeName)
        val lockType = LockType.fromStored(raw.lockTypeName)
        val isStrict = lockType == LockType.STRICT_LOCK
        return TamperProtectionState(
            protectionMode = mode,
            modeConfigured = raw.modeExplicitlyChosen || raw.protectionActivated,
            lockType = lockType,
            isPinConfigured = raw.pinHashBase64.isNotBlank(),
            protectionSetupDone = raw.protectionActivated,
            waitDuration = WaitDurationOption.fromDurationMs(raw.waitDurationMs),
            protectionLevel =
                if (raw.protectionActivated && (isStrict || !raw.unlockWindowActive)) {
                    ProtectionLevel.ON
                } else {
                    ProtectionLevel.OFF
                },
            isCountdownActive = !isStrict && raw.countdownActive,
            countdownRemainingMs = if (isStrict) 0L else raw.countdownRemainingMs,
            isCountdownReadyToUnlock = !isStrict && raw.countdownReadyForSecondPin,
            isUnlockWindowActive = !isStrict && raw.unlockWindowActive,
            unlockWindowRemainingMs = if (isStrict) 0L else raw.unlockWindowRemainingMs,
            isLockedOut = !isStrict && raw.lockoutRemainingMs > 0L,
            lockoutRemainingMs = if (isStrict) 0L else raw.lockoutRemainingMs,
            failedPinAttempts = if (isStrict) 0 else raw.failedPinAttempts,
            lockoutTier = if (isStrict) 0 else raw.lockoutTier,
        )
    }

    companion object {
        const val STRICT_LOCK_CONFIRMATION_PHRASE = "I UNDERSTAND"
        const val MAX_FAILED_PIN_ATTEMPTS = 5
        const val BASE_LOCKOUT_DURATION_MS = 30L * 60L * 1000L
        const val UNLOCK_WINDOW_DURATION_MS = 15L * 60L * 1000L
        private const val MAX_LOCKOUT_SHIFT = 6
    }
}
