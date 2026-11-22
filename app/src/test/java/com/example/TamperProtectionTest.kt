package com.example

import com.example.protection.InMemoryProtectionStateStore
import com.example.protection.PinActionResult
import com.example.protection.PinHasher
import com.example.protection.ProtectionChecklistState
import com.example.protection.ProtectionClock
import com.example.protection.ProtectionLevel
import com.example.protection.TamperProtectionEngine
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
        pin: String = "482913",
        waitDuration: WaitDurationOption = WaitDurationOption.TEN_MINUTES,
    ) {
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

        // Changing PIN outside unlock window is also rejected
        assertTrue(engine.changePin("987654", "111222") is PinActionResult.Rejected)

        // Complete unlock flow to enter 15-minute unlock window
        assertEquals(PinActionResult.Success, engine.requestTurnOffProtection("987654"))
        clock.advanceBy(24L * 60 * 60_000L)
        assertEquals(PinActionResult.Success, engine.confirmUnlockWindow("987654"))

        // Inside unlock window: lowering wait time AND changing PIN succeed
        assertEquals(
            WaitTimeUpdateResult.Success,
            engine.setWaitDuration(WaitDurationOption.TEN_MINUTES),
        )
        assertEquals(WaitDurationOption.TEN_MINUTES, engine.getState().waitDuration)
        assertEquals(PinActionResult.Success, engine.changePin("987654", "111222"))
    }

    @Test
    fun computeProtectionLevel_reflectsOnWeakOffCorrectly() = runBlocking {
        val clock = FakeProtectionClock()
        val engine = TamperProtectionEngine(InMemoryProtectionStateStore(), clock)
        setupActivatedProtection(engine, pin = "223344", waitDuration = WaitDurationOption.TEN_MINUTES)

        val fullChecklist =
            ProtectionChecklistState(
                pinConfigured = true,
                waitDurationConfigured = true,
                deviceAdminEnabled = true,
                accessibilityEnabled = true,
                vpnRunning = true,
            )
        assertEquals(ProtectionLevel.ON, engine.computeProtectionLevel(fullChecklist, engine.getState()))

        val weakenedChecklist = fullChecklist.copy(accessibilityEnabled = false)
        assertEquals(
            ProtectionLevel.WEAK,
            engine.computeProtectionLevel(weakenedChecklist, engine.getState()),
        )
    }
}
