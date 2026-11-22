package com.example.protection

data class PersistedTamperState(
    val pinSaltBase64: String = "",
    val pinHashBase64: String = "",
    val waitDurationMs: Long = WaitDurationOption.DEFAULT.durationMs,
    val waitDurationExplicitlyChosen: Boolean = false,
    val protectionActivated: Boolean = false,
    val wizardPromptedAfterOnboarding: Boolean = false,
    val highestWallClockMs: Long = 0L,
    val lastElapsedRealtimeMs: Long = 0L,
    val countdownActive: Boolean = false,
    val countdownRemainingMs: Long = 0L,
    val countdownReadyForSecondPin: Boolean = false,
    val unlockWindowActive: Boolean = false,
    val unlockWindowRemainingMs: Long = 0L,
    val failedPinAttempts: Int = 0,
    val lockoutTier: Int = 0,
    val lockoutRemainingMs: Long = 0L,
)

interface ProtectionStateStore {
    suspend fun readState(): PersistedTamperState

    suspend fun writeState(state: PersistedTamperState)
}

class InMemoryProtectionStateStore(
    initial: PersistedTamperState = PersistedTamperState()
) : ProtectionStateStore {
    private var current: PersistedTamperState = initial

    override suspend fun readState(): PersistedTamperState = current

    override suspend fun writeState(state: PersistedTamperState) {
        current = state
    }
}
