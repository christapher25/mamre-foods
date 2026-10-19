package com.mamre.billing.data.local

import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.PinLockState
import com.mamre.billing.domain.security.PinLockout
import com.mamre.billing.domain.security.PinResult
import com.mamre.billing.domain.security.PinTime
import com.mamre.billing.domain.usecase.ChangePin
import com.mamre.billing.domain.usecase.SetPin
import com.mamre.billing.domain.usecase.VerifyPin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review item 3: the phone's date and time can be changed by whoever holds the phone, so the wrong-PIN delay and the idle lock
 * run on a MONOTONIC clock (a controllable one here) and the wall clock is only a second opinion after a reboot. Each test moves
 * the two clocks separately: MutableClock.jumpWallMs moves only the wall clock, reboot() restarts the monotonic clock.
 */
@RunWith(RobolectricTestRunner::class)
class PinClockTest {
    private val file = "pin-clock-test.db"
    private val hour = 3_600_000L

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun lockedWorld(db: MamreDatabase = TestDatabase.inMemory()): World {
        val w = World(db).also { it.seed() }
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("1234", "1234")
        repeat(5) { verify(w, "9999") } // the 5th wrong PIN: a 30 second delay
        return w
    }

    private fun verify(w: World, pin: String) = runBlocking { VerifyPin(w.unitOfWork, w.settings, w.clock, w.clock)(pin) }

    private fun locked(ms: Long) = PinResult.Locked(ms)

    // ---------------------------------------------------------------- normal waiting

    @Test fun normalWaitingCountsDownAndTheRightPinOpensAfterTheDelay() = runBlocking {
        val w = lockedWorld()
        assertEquals(locked(30_000), verify(w, "1234"))
        w.clock.advanceMs(10_000)
        assertEquals(locked(20_000), verify(w, "1234"))
        w.clock.advanceMs(19_999)
        assertEquals(locked(1), verify(w, "1234"))
        w.clock.advanceMs(1)
        assertEquals(PinResult.Ok, verify(w, "1234"))
    }

    // ---------------------------------------------------------------- the wall clock moved while the app keeps running

    @Test fun aWallClockMovedForwardDoesNotShortenTheDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000)
        w.clock.jumpWallMs(+hour)
        assertEquals(locked(20_000), verify(w, "1234")) // not 0
        w.clock.advanceMs(20_000)
        assertEquals(PinResult.Ok, verify(w, "1234"))
    }

    @Test fun aWallClockMovedBackwardsDoesNotStretchTheDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000)
        w.clock.jumpWallMs(-hour)
        assertEquals(locked(20_000), verify(w, "1234")) // not an hour and 20 seconds
        w.clock.advanceMs(20_000)
        assertEquals(PinResult.Ok, verify(w, "1234"))
    }

    @Test fun aWrongPinAfterTheDelayDoublesItEvenWithTheWallClockMovedBack() = runBlocking {
        val w = lockedWorld()
        w.clock.jumpWallMs(-hour)
        w.clock.advanceMs(30_000)
        assertEquals(locked(60_000), verify(w, "9999")) // the 6th wrong PIN
        w.clock.jumpWallMs(+10 * hour)
        w.clock.advanceMs(59_000)
        assertEquals(locked(1_000), verify(w, "1234"))
    }

    // ---------------------------------------------------------------- reboot

    @Test fun afterARebootTheDelayIsNeverShorterThanItWas() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000) // 20 s were still to go
        w.clock.reboot(sinceBootMs = 2_000)
        val after = (verify(w, "1234") as PinResult.Locked).remainingMs
        assertTrue("$after must be at least the true 20000", after >= 20_000)
        assertTrue("$after is at most the whole delay", after <= 30_000)
    }

    @Test fun aRebootPlusTheWallClockMovedForwardStillLeavesTheDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000)
        w.clock.reboot(sinceBootMs = 2_000)
        w.clock.jumpWallMs(+hour) // the wall clock says the delay is long over
        val after = (verify(w, "1234") as PinResult.Locked).remainingMs
        assertTrue("$after must not be 0 and not shorter than the true 20000", after >= 20_000)
    }

    @Test fun aRebootPlusTheWallClockMovedBackIsCappedAtTheDelayItself() = runBlocking {
        val w = lockedWorld()
        w.clock.reboot(sinceBootMs = 2_000)
        w.clock.jumpWallMs(-hour)
        val after = (verify(w, "1234") as PinResult.Locked).remainingMs
        assertEquals("never more than the whole 30 second delay, not an hour", 30_000L, after)
    }

    @Test fun afterARebootTheDelayEndsOnlyWhenBothTheTimeSinceBootAndTheWallClockSayItIsOver() = runBlocking {
        val w = lockedWorld()
        w.clock.reboot(sinceBootMs = 2_000) // the phone was back at once: the wall clock has not moved
        w.clock.advanceMs(27_999) // 29 999 ms since boot (1 ms to go by that count), wall clock 2 001 ms short of the deadline
        assertEquals(locked(2_001), verify(w, "1234")) // the larger of the two
        w.clock.advanceMs(2_001)
        assertEquals(PinResult.Ok, verify(w, "1234"))
    }

    @Test fun theCounterAndTheDelaySurviveARebootAndTheDatabaseBeingReopened() = runBlocking {
        var w = lockedWorld(TestDatabase.file(file))
        w.db.close()
        w.clock.reboot(sinceBootMs = 1_000)
        w = World(TestDatabase.file(file), w.clock)
        assertTrue(verify(w, "1234") is PinResult.Locked)
        assertEquals("5", w.settings.get(SettingKeys.PIN_FAIL_COUNT))
        w.db.close()
    }

    @Test fun aRebootNeverResetsTheWrongPinCounter() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("1234", "1234")
        repeat(4) { verify(w, "9999") }
        w.clock.reboot()
        assertEquals(locked(30_000), verify(w, "9999")) // the 5th wrong PIN counts across the reboot
    }

    @Test fun changePinSharesTheSameMonotonicDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.jumpWallMs(+hour)
        val change = ChangePin(w.unitOfWork, w.settings, w.changeLog, w.clock, w.clock)
        val e = runCatching { change("1234", "5678", "5678") }.exceptionOrNull()
        assertTrue(e is com.mamre.billing.domain.usecase.PinDelay)
        assertEquals(30_000L, (e as com.mamre.billing.domain.usecase.PinDelay).remainingMs)
    }

    // ---------------------------------------------------------------- the pure rule, including odd stored values

    @Test fun theStoredPartsAreSettingsRowsThatAreNeverExported() {
        for (key in listOf(SettingKeys.PIN_LOCK_DELAY_MS, SettingKeys.PIN_LOCK_MONO_AT, SettingKeys.PIN_LOCK_BOOT)) {
            assertTrue(key, com.mamre.billing.data.export.isNeverExported(key))
        }
    }

    @Test fun anAbsurdStoredDelayIsCappedAndATamperedMonotonicReadingCountsAsAReboot() {
        val now = PinTime(wallMs = 1_000_000, monoMs = 10_000, bootId = "b")
        val huge = PinLockState(failCount = 9, lockedUntilMs = 9_999_999_999, delayMs = 10 * hour, monoAtMs = 10_000, bootId = "b")
        assertEquals(PinLockout.MAX_DELAY_MS, PinLockout.remainingMs(huge, now))
        val future = PinLockState(failCount = 5, lockedUntilMs = 1_000_000 + 30_000, delayMs = 30_000, monoAtMs = 99_000_000, bootId = "b")
        assertTrue(PinLockout.remainingMs(future, now) in 20_000..30_000) // monoAt is in the future: treated as another boot
    }

    // ---------------------------------------------------------------- the idle lock

    @Test fun movingTheWallClockNeverStopsOrStartsTheIdleLock() {
        val clock = MutableClock()
        val lock = AppLock(clock).also { it.unlock() }
        clock.advanceMinutes(2)
        clock.jumpWallMs(-hour) // back an hour: the app must still lock on schedule
        assertFalse(lock.checkIdle(5))
        clock.advanceMinutes(3)
        assertTrue(lock.checkIdle(5))
        lock.unlock()
        clock.jumpWallMs(+10 * hour) // forward ten hours: no early lock
        assertFalse(lock.checkIdle(5))
        clock.advanceMinutes(5)
        assertTrue(lock.checkIdle(5))
    }

    @Test fun theBackgroundTimeCountsOnTheMonotonicClockToo() {
        val clock = MutableClock()
        val lock = AppLock(clock).also { it.unlock() }
        clock.jumpWallMs(-hour)
        clock.advanceMinutes(6)
        lock.onForegrounded(lockMinutes = 5)
        assertTrue(lock.locked.value)
    }
}
