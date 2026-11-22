package com.example

import com.example.protection.InMemoryProtectionStateStore
import com.example.protection.LockType
import com.example.protection.PinActionResult
import com.example.protection.PinHasher
import com.example.protection.ProtectionChecklistState
import com.example.protection.ProtectionClock
import com.example.protection.ProtectionLevel
import com.example.protection.ProtectionMode
import com.example.protection.ProtectionWatchdogScheduler
import com.example.protection.TamperProtectionEngine
import com.example.protection.TimerTransitions
import com.example.protection.WaitDurationOption
import com.example.protection.WaitTimeUpdateResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TamperProtectionTest {

    private class FakeProtectionClock(
        var wallMs: Long = 1_700_000_000_000L,
        var elapsedMs: Long = 60_000L,
    ) : ProtectionClock {
        override fun wallTimeMs(): Long = wallMs

        override fun elapsedRealtimeMs(): Long = elapsedMs

        fun advanceBy(deltaMs: Long) {
            wallMs += deltaMs
            elapsedMs += deltaMs
        }

        fun shiftWallClockBackwards(deltaMs: Long) {
            wallMs -= deltaMs
        }

        fun setWallClock(newWallMs: Long) {
            wallMs = newWallMs
        }

        fun reboot(offlineDurationMs: Long) {
            wallMs += offlineDurationMs
            elapsedMs = 5_000L
        }
    }

    private suspend fun setupActivatedProtection(
        engine: TamperProtectionEngine,
        mode: ProtectionMode = ProtectionMode.VPN,
        pin: String = "482913",
        waitDuration: WaitDurationOption = WaitDurationOption.TEN_MINUTES,
    ) {
        assertEquals(WaitTimeUpdateResult.Success, engine.setProtectionMode(mode))
        assertEquals(PinActionResult.Success, engine.setInitialPin(pin))
        assertEquals(WaitTimeUpdateResult.Success, engine.setWaitDuration(waitDuration))
        engine.syncCoreProtectionActivation(coreStepsComplete = true)
    }

    @Test
    fun pinHashVerify_acceptsValidPinAndRejectsWrongPin() {
        val (salt, hash) = PinHasher.hashPin("739204")
        assertTrue(PinHasher.verifyPin("739204", salt, hash))
        assertFalse(PinHasher.verifyPin("739205", salt, hash))
        assertFalse(PinHasher.verifyPin("123", salt, hash))
    }

    @Test
    fun wrongAttemptLockout_locksAfterFiveFailuresAndDoublesDuration() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(engine, pin = "112233")

        // 4 failed attempts
        for (expectedRemaining in listOf(4, 3, 2, 1)) {
            val res = engine.requestTurnOffProtection("000000")
            assertEquals(PinActionResult.InvalidPin(expectedRemaining), res)
        }

        // 5th failed attempt triggers 30-minute lockout
        val firstLockout = engine.requestTurnOffProtection("000000")
        assertEquals(PinActionResult.LockedOut(30L * 60_000L), firstLockout)

        // Even the right PIN is blocked while locked out
        val blockedEvenRightPin = engine.requestTurnOffProtection("112233")
        assertTrue(blockedEvenRightPin is PinActionResult.LockedOut)

        // Advance 30 minutes -> lockout expires
        clock.advanceBy(30L * 60_000L)
        assertFalse(engine.getState().isLockedOut)

        // 5 more wrong attempts -> 60-minute lockout
        repeat(4) {
            assertTrue(engine.requestTurnOffProtection("999999") is PinActionResult.InvalidPin)
        }
        val secondLockout = engine.requestTurnOffProtection("999999")
        assertEquals(PinActionResult.LockedOut(60L * 60_000L), secondLockout)
    }

    @Test
    fun countdownStartAndFinish_opens15MinUnlockWindowAfterSecondPin() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(engine, pin = "246810", waitDuration = WaitDurationOption.TEN_MINUTES)

        val startRes = engine.requestTurnOffProtection("246810")
        assertEquals(PinActionResult.Success, startRes)

        val midState = engine.getState()
        assertTrue(midState.isCountdownActive)
        assertEquals(10L * 60_000L, midState.countdownRemainingMs)
        assertTrue(midState.isGuardActive)

        // Cannot unlock early before countdown finishes
        assertTrue(engine.confirmUnlockWindow("246810") is PinActionResult.Rejected)

        // Advance full 10 minutes
        clock.advanceBy(10L * 60_000L)
        val readyState = engine.getState()
        assertFalse(readyState.isCountdownActive)
        assertTrue(readyState.isCountdownReadyToUnlock)
        assertTrue(readyState.isGuardActive)

        // Second PIN entry opens 15-minute unlock window
        assertEquals(PinActionResult.Success, engine.confirmUnlockWindow("246810"))
        val unlockedState = engine.getState()
        assertTrue(unlockedState.isUnlockWindowActive)
        assertFalse(unlockedState.isGuardActive)
        assertEquals(15L * 60_000L, unlockedState.unlockWindowRemainingMs)
    }

    @Test
    fun clockMovedBackwards_cannotShortenCountdownOrBypassViaRestore() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(engine, pin = "555666", waitDuration = WaitDurationOption.ONE_HOUR)

        // Prime initial clock height before tampering
        val initialWall = clock.wallMs
        engine.getState()

        // Attack A: Rewind wall clock 2 hours BEFORE starting countdown, start countdown, then restore clock
        clock.shiftWallClockBackwards(2L * 60 * 60_000L)
        assertEquals(PinActionResult.Success, engine.requestTurnOffProtection("555666"))
        clock.setWallClock(initialWall)

        val afterRestoreState = engine.getState()
        assertTrue(afterRestoreState.isCountdownActive)
        assertEquals(60L * 60_000L, afterRestoreState.countdownRemainingMs)

        // Advance 20 minutes normally -> 40 minutes remaining
        clock.advanceBy(20L * 60_000L)
        assertEquals(40L * 60_000L, engine.getState().countdownRemainingMs)

        // Attack B: Move clock backwards mid-countdown
        clock.shiftWallClockBackwards(3L * 60 * 60_000L)
        val afterMidBackwards = engine.getState()
        assertTrue(afterMidBackwards.isCountdownActive)
        assertEquals(40L * 60_000L, afterMidBackwards.countdownRemainingMs)

        // Even rebooting while clock is rewound cannot shorten countdown
        clock.reboot(offlineDurationMs = 5L * 60_000L)
        val afterRewoundReboot = engine.getState()
        assertTrue(afterRewoundReboot.isCountdownActive)
        assertEquals(40L * 60_000L - 5_000L, afterRewoundReboot.countdownRemainingMs)

        // Restore real wall clock, run 1 minute, then reboot 15 minutes -> leaves 24m - 5s
        clock.setWallClock(initialWall + 20L * 60_000L)
        clock.advanceBy(60_000L)
        engine.getState()
        clock.reboot(offlineDurationMs = 15L * 60_000L)
        val afterNormalReboot = engine.getState()
        assertTrue(afterNormalReboot.isCountdownActive)
        assertEquals(24L * 60_000L - 5_000L, afterNormalReboot.countdownRemainingMs)
    }

    @Test
    fun unlockWindowExpiry_andManualLockAgainReturnProtectionToOn() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(engine, pin = "135790", waitDuration = WaitDurationOption.TEN_MINUTES)

        engine.requestTurnOffProtection("135790")
        clock.advanceBy(10L * 60_000L)
        assertEquals(PinActionResult.Success, engine.confirmUnlockWindow("135790"))
        assertTrue(engine.getState().isUnlockWindowActive)
        assertFalse(engine.getState().isGuardActive)

        // Advance 14 minutes -> still unlocked with 1 minute left
        clock.advanceBy(14L * 60_000L)
        assertTrue(engine.getState().isUnlockWindowActive)
        assertEquals(60_000L, engine.getState().unlockWindowRemainingMs)

        // Advance final minute -> automatically re-locks
        clock.advanceBy(60_000L)
        val expiredState = engine.getState()
        assertFalse(expiredState.isUnlockWindowActive)
        assertTrue(expiredState.isGuardActive)

        // Manual "Lock again" immediately locks while inside unlock window
        engine.requestTurnOffProtection("135790")
        clock.advanceBy(10L * 60_000L)
        engine.confirmUnlockWindow("135790")
        assertTrue(engine.getState().isUnlockWindowActive)
        val relocked = engine.lockAgainNow()
        assertFalse(relocked.isUnlockWindowActive)
        assertTrue(relocked.isGuardActive)
    }

    @Test
    fun lowerWaitTimeRejectedOutsideUnlockWindow_andAllowedInsideUnlockWindow() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(engine, pin = "987654", waitDuration = WaitDurationOption.ONE_HOUR)

        // Raising wait time outside unlock window (1h -> 24h) is allowed
        assertEquals(
            WaitTimeUpdateResult.Success,
            engine.setWaitDuration(WaitDurationOption.TWENTY_FOUR_HOURS),
        )
        assertEquals(WaitDurationOption.TWENTY_FOUR_HOURS, engine.getState().waitDuration)

        // Lowering wait time outside unlock window (24h -> 10m) is rejected
        val lowerRejected = engine.setWaitDuration(WaitDurationOption.TEN_MINUTES)
        assertTrue(lowerRejected is WaitTimeUpdateResult.Rejected)
        assertEquals(WaitDurationOption.TWENTY_FOUR_HOURS, engine.getState().waitDuration)

        // Changing PIN or Protection Mode outside unlock window is also rejected
        assertTrue(engine.changePin("987654", "111222") is PinActionResult.Rejected)
        assertTrue(engine.setProtectionMode(ProtectionMode.PRIVATE_DNS) is WaitTimeUpdateResult.Rejected)

        // Complete unlock flow to enter 15-minute unlock window
        assertEquals(PinActionResult.Success, engine.requestTurnOffProtection("987654"))
        clock.advanceBy(24L * 60 * 60_000L)
        assertEquals(PinActionResult.Success, engine.confirmUnlockWindow("987654"))

        // Inside unlock window: lowering wait time, changing PIN, and switching mode succeed
        assertEquals(
            WaitTimeUpdateResult.Success,
            engine.setWaitDuration(WaitDurationOption.TEN_MINUTES),
        )
        assertEquals(WaitDurationOption.TEN_MINUTES, engine.getState().waitDuration)
        assertEquals(PinActionResult.Success, engine.changePin("987654", "111222"))
        assertEquals(
            WaitTimeUpdateResult.Success,
            engine.setProtectionMode(ProtectionMode.PRIVATE_DNS),
        )
        assertEquals(ProtectionMode.PRIVATE_DNS, engine.getState().protectionMode)
    }

    @Test
    fun strictLock_requiresExactPhraseAndHasNoUnlockPathAtAll() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        assertEquals(WaitTimeUpdateResult.Success, engine.setProtectionMode(ProtectionMode.VPN))

        // Reject wrong confirmation phrase
        assertTrue(engine.activateStrictLock("i understand") is PinActionResult.Rejected)
        assertTrue(engine.activateStrictLock("YES") is PinActionResult.Rejected)

        // Accept exact phrase "I UNDERSTAND"
        assertEquals(
            PinActionResult.Success,
            engine.activateStrictLock(TamperProtectionEngine.STRICT_LOCK_CONFIRMATION_PHRASE),
        )
        engine.syncCoreProtectionActivation(coreStepsComplete = true)

        val lockedState = engine.getState()
        assertEquals(LockType.STRICT_LOCK, lockedState.lockType)
        assertTrue(lockedState.isStrictLock)
        assertTrue(lockedState.isGuardActive)
        assertFalse(lockedState.isPinConfigured)
        assertFalse(lockedState.canTurnOffVpnDirectly)
        assertFalse(lockedState.canChangeLockOrMode)

        // No unlock path, PIN change, wait change, mode change, or deactivation is ever allowed
        assertTrue(engine.requestTurnOffProtection("000000") is PinActionResult.Rejected)
        assertTrue(engine.confirmUnlockWindow("000000") is PinActionResult.Rejected)
        assertTrue(engine.setInitialPin("123456") is PinActionResult.Rejected)
        assertTrue(engine.changePin("000000", "123456") is PinActionResult.Rejected)
        assertTrue(
            engine.setWaitDuration(WaitDurationOption.SEVENTY_TWO_HOURS)
                is WaitTimeUpdateResult.Rejected
        )
        assertTrue(engine.setProtectionMode(ProtectionMode.PRIVATE_DNS) is WaitTimeUpdateResult.Rejected)
        assertFalse(engine.deactivateProtectionInUnlockWindow())

        // Even after advancing days, guard stays permanently active
        clock.advanceBy(7L * 24L * 60L * 60_000L)
        val stillLocked = engine.getState()
        assertTrue(stillLocked.isGuardActive)
        assertFalse(stillLocked.isUnlockWindowActive)
        assertFalse(stillLocked.isCountdownActive)
    }

    @Test
    fun computeProtectionLevel_reflectsVpnAndPrivateDnsModesCorrectly() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(
            engine,
            mode = ProtectionMode.VPN,
            pin = "223344",
            waitDuration = WaitDurationOption.TEN_MINUTES,
        )

        val vpnChecklist =
            ProtectionChecklistState(
                protectionMode = ProtectionMode.VPN,
                modeConfigured = true,
                lockType = LockType.TRUSTED_PIN,
                pinConfigured = true,
                waitDurationConfigured = true,
                deviceAdminEnabled = true,
                accessibilityEnabled = true,
                vpnRunning = true,
            )
        assertEquals(ProtectionLevel.ON, engine.computeProtectionLevel(vpnChecklist, engine.getState()))

        val weakenedVpnChecklist = vpnChecklist.copy(accessibilityEnabled = false)
        assertEquals(
            ProtectionLevel.WEAK,
            engine.computeProtectionLevel(weakenedVpnChecklist, engine.getState()),
        )

        // Switch to Private DNS mode in a fresh instance
        val privateDnsEngine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(
            privateDnsEngine,
            mode = ProtectionMode.PRIVATE_DNS,
            pin = "223344",
            waitDuration = WaitDurationOption.TEN_MINUTES,
        )
        val privateDnsChecklist =
            ProtectionChecklistState(
                protectionMode = ProtectionMode.PRIVATE_DNS,
                modeConfigured = true,
                lockType = LockType.TRUSTED_PIN,
                pinConfigured = true,
                waitDurationConfigured = true,
                deviceAdminEnabled = true,
                accessibilityEnabled = true,
                vpnRunning = false,
                privateDnsConfigured = true,
            )
        assertEquals(
            ProtectionLevel.ON,
            privateDnsEngine.computeProtectionLevel(privateDnsChecklist, privateDnsEngine.getState()),
        )
        assertEquals(
            ProtectionLevel.WEAK,
            privateDnsEngine.computeProtectionLevel(
                privateDnsChecklist.copy(privateDnsConfigured = false),
                privateDnsEngine.getState(),
            ),
        )
    }

    @Test
    fun countdownCompletesWithoutUi_andBoundaryTimerUsesExactRemainingTimeWithoutCap() =
        runBlocking {
            val clock = FakeProtectionClock()
            val store = InMemoryProtectionStateStore()
            val engine = TamperProtectionEngine(store, clock)
            setupActivatedProtection(
                engine,
                pin = "654321",
                waitDuration = WaitDurationOption.TWENTY_FOUR_HOURS,
            )

            // No timer active -> boundary delay is null
            assertEquals(null, ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(engine.getState()))
            assertFalse(ProtectionWatchdogScheduler.hasActiveTimer(engine.getState()))

            // Start 24-hour countdown
            assertEquals(PinActionResult.Success, engine.requestTurnOffProtection("654321"))
            val startedState = engine.getState()
            assertTrue(ProtectionWatchdogScheduler.hasActiveTimer(startedState))
            assertEquals(
                24L * 60L * 60_000L,
                ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(startedState),
            )

            // Simulate app process dying while closed and resuming headless with no UI after 12 hours
            clock.advanceBy(12L * 60L * 60_000L)
            val headlessResumedEngine = TamperProtectionEngine(store, clock)
            val midState = headlessResumedEngine.getState()
            assertTrue(midState.isCountdownActive)
            assertEquals(
                12L * 60L * 60_000L,
                ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(midState),
            )
            assertEquals(TimerTransitions(), headlessResumedEngine.consumePendingTransitions())

            // Advance final 12 hours with no UI alive -> countdown completes immediately
            clock.advanceBy(12L * 60L * 60_000L)
            val readyState = headlessResumedEngine.getState()
            assertFalse(readyState.isCountdownActive)
            assertTrue(readyState.isCountdownReadyToUnlock)
            assertEquals(
                TimerTransitions(countdownCompleted = true, unlockWindowEnded = false),
                headlessResumedEngine.consumePendingTransitions(),
            )
            // Transition is consumed once only
            assertEquals(TimerTransitions(), headlessResumedEngine.consumePendingTransitions())
            assertEquals(null, ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(readyState))
            assertFalse(ProtectionWatchdogScheduler.hasActiveTimer(readyState))

            // Enter second PIN to start 15-minute unlock window -> exact 15m boundary
            assertEquals(PinActionResult.Success, headlessResumedEngine.confirmUnlockWindow("654321"))
            val unlockedState = headlessResumedEngine.getState()
            assertEquals(
                15L * 60_000L,
                ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(unlockedState),
            )

            // Advance 15 minutes with no UI -> unlock window expires and emits transition once
            clock.advanceBy(15L * 60_000L)
            val relockedState = headlessResumedEngine.getState()
            assertFalse(relockedState.isUnlockWindowActive)
            assertTrue(relockedState.isGuardActive)
            assertEquals(
                TimerTransitions(countdownCompleted = false, unlockWindowEnded = true),
                headlessResumedEngine.consumePendingTransitions(),
            )
            assertEquals(null, ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(relockedState))
        }

    @Test
    fun boundaryTimer_picksNearestActiveTimerIncludingLockoutWithoutCap() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(
            engine,
            pin = "121212",
            waitDuration = WaitDurationOption.TEN_MINUTES,
        )

        // Start 10-minute countdown, then fail PIN 5 times on confirmUnlockWindow (wait: trigger
        // lockout first, then see lockout boundary = 30m exact; or test both)
        repeat(5) { engine.requestTurnOffProtection("000000") }
        val lockedState = engine.getState()
        assertTrue(lockedState.isLockedOut)
        assertTrue(ProtectionWatchdogScheduler.hasActiveTimer(lockedState))
        assertEquals(
            30L * 60_000L,
            ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(lockedState),
        )

        // Advance 30m -> lockout clears and boundary becomes null
        clock.advanceBy(30L * 60_000L)
        val clearedState = engine.getState()
        assertFalse(clearedState.isLockedOut)
        assertEquals(null, ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(clearedState))
    }
}
