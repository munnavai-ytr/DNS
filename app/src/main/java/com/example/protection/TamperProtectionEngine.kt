package com.example.protection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TamperProtectionEngine(
    private val store: ProtectionStateStore,
    private val clock: ProtectionClock = SystemProtectionClock,
) {
    private val mutex = Mutex()

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

    suspend fun setInitialPin(pin: String): PinActionResult =
        mutex.withLock {
            if (!PinHasher.isValidPinFormat(pin)) {
                return@withLock PinActionResult.Rejected("PIN must be 6 digits.")
            }
            val synced = syncTimeLocked(store.readState())
            if (synced.protectionActivated && !synced.unlockWindowActive) {
                return@withLock PinActionResult.Rejected(
                    "PIN can only be changed during an active 15-minute unlock window."
                )
            }
            val (salt, hash) = PinHasher.hashPin(pin)
            val updated =
                synced.copy(
                    pinSaltBase64 = salt,
                    pinHashBase64 = hash,
                    failedPinAttempts = 0,
                    lockoutTier = 0,
                    lockoutRemainingMs = 0L,
                )
            store.writeState(updated)
            PinActionResult.Success
        }

    suspend fun changePin(currentPin: String, newPin: String): PinActionResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
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
            PinActionResult.Success
        }

    suspend fun confirmUnlockWindow(pin: String): PinActionResult =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
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
            toPublicState(updated)
        }

    suspend fun deactivateProtectionInUnlockWindow(): Boolean =
        mutex.withLock {
            val synced = syncTimeLocked(store.readState())
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
            true
        }

    fun computeProtectionLevel(
        checklist: ProtectionChecklistState,
        state: TamperProtectionState,
    ): ProtectionLevel {
        if (!state.protectionSetupDone && !checklist.isCoreStepsComplete) {
            return ProtectionLevel.OFF
        }
        if (state.isUnlockWindowActive) {
            return ProtectionLevel.OFF
        }
        return if (checklist.isCoreStepsComplete && checklist.vpnRunning) {
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

        // Same boot: rely purely on monotonic elapsedRealtime() so neither moving the system
        // clock backwards nor jumping it forwards can shorten any timer.
        // Across a reboot (elapsedNow < lastElapsedRealtimeMs): rely on highest-seen wall clock
        // delta (or elapsedNow if the wall clock was rewound before reboot).
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
        return TamperProtectionState(
            isPinConfigured = raw.pinHashBase64.isNotBlank(),
            protectionSetupDone = raw.protectionActivated,
            waitDuration = WaitDurationOption.fromDurationMs(raw.waitDurationMs),
            protectionLevel =
                if (raw.protectionActivated && !raw.unlockWindowActive) {
                    ProtectionLevel.ON
                } else {
                    ProtectionLevel.OFF
                },
            isCountdownActive = raw.countdownActive,
            countdownRemainingMs = raw.countdownRemainingMs,
            isCountdownReadyToUnlock = raw.countdownReadyForSecondPin,
            isUnlockWindowActive = raw.unlockWindowActive,
            unlockWindowRemainingMs = raw.unlockWindowRemainingMs,
            isLockedOut = raw.lockoutRemainingMs > 0L,
            lockoutRemainingMs = raw.lockoutRemainingMs,
            failedPinAttempts = raw.failedPinAttempts,
            lockoutTier = raw.lockoutTier,
        )
    }

    companion object {
        const val MAX_FAILED_PIN_ATTEMPTS = 5
        const val BASE_LOCKOUT_DURATION_MS = 30L * 60L * 1000L
        const val UNLOCK_WINDOW_DURATION_MS = 15L * 60L * 1000L
        private const val MAX_LOCKOUT_SHIFT = 6 // caps at 30m * 64 = 32 hours
    }
}
