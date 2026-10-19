package com.mamre.billing.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the PIN lock (Doc 2 s6): the hash, the wrong-PIN delay, and the lock after inactivity. */
class PinCoreTest {
    // ---------------------------------------------------------------- the hash

    @Test fun aPinIsFourToSixDigits() {
        for (ok in listOf("1234", "12345", "123456", "0000")) assertTrue(ok, PinHasher.isValidPin(ok))
        for (bad in listOf("", "123", "1234567", "12a4", "12 34", "١٢٣٤")) assertFalse(bad, PinHasher.isValidPin(bad))
    }

    @Test fun theHashVerifiesTheRightPinAndRefusesAWrongOne() {
        val salt = PinHasher.newSalt()
        val stored = PinHasher.hash("4821", salt)
        assertTrue(PinHasher.verify("4821", salt, stored))
        assertFalse(PinHasher.verify("4822", salt, stored))
        assertFalse(PinHasher.verify("", salt, stored))
    }

    @Test fun theStoredValueNamesTheAlgorithmAndIterationsAndNeverContainsThePin() {
        val stored = PinHasher.hash("4821", PinHasher.newSalt())
        assertTrue(stored.startsWith("pbkdf2-sha256:120000:"))
        assertFalse(stored.contains("4821"))
    }

    @Test fun theSaltIsPartOfTheHash() {
        val a = PinHasher.newSalt()
        val b = PinHasher.newSalt()
        assertNotEquals(a, b)
        assertNotEquals(PinHasher.hash("4821", a), PinHasher.hash("4821", b)) // same PIN, different salt, different hash
        assertFalse(PinHasher.verify("4821", b, PinHasher.hash("4821", a))) // checking with another salt fails
    }

    @Test fun aGarbledStoredValueNeverVerifies() {
        val salt = PinHasher.newSalt()
        for (bad in listOf("", "x", "pbkdf2-sha256:abc:def", "pbkdf2-sha256:120000:", "md5:1:AAAA")) assertFalse(bad, PinHasher.verify("4821", salt, bad))
    }

    // ---------------------------------------------------------------- the delay: 30 s doubling to 15 minutes

    @Test fun theFirstFourWrongPinsCostNoDelayAndTheFifthCosts30Seconds() {
        for (n in 0..4) assertEquals("after $n", 0L, PinLockout.delayAfter(n))
        assertEquals(30_000L, PinLockout.delayAfter(5))
    }

    @Test fun theDelayDoublesWithEachFurtherWrongPinAndStopsAt15Minutes() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L), (5..10).map { PinLockout.delayAfter(it) })
        assertEquals(900_000L, PinLockout.delayAfter(11))
        assertEquals(900_000L, PinLockout.delayAfter(1_000_000))
    }

    private fun at(ms: Long) = PinTime(wallMs = ms, monoMs = ms, bootId = "b")

    @Test fun aWrongPinCountsAndSetsTheLockedUntilTimeAndACorrectOneResets() {
        var s = PinLockState()
        for (i in 1..4) s = PinLockout.afterWrong(s, at(1_000))
        assertEquals(0L, PinLockout.remainingMs(s, at(1_000)))
        s = PinLockout.afterWrong(s, at(1_000))
        assertEquals(5, s.failCount)
        assertEquals(31_000L, s.lockedUntilMs)
        assertEquals(30_000L, PinLockout.remainingMs(s, at(1_000)))
        assertEquals(0L, PinLockout.remainingMs(s, at(31_000))) // the delay is over
        assertEquals(PinLockState(), PinLockout.afterCorrect())
    }

    // A reboot whose new monotonic reading is NOT smaller than the old one: only the boot identity says it was a reboot.
    // Written 10 minutes of delay at the start of boot "b1"; the new boot "b2" is 400 s old, so 400_000 >= 0 and a check of
    // the monotonic reading alone would call it the same boot.
    private val tenMinutes = 600_000L
    private val writtenAtBootStart = PinLockState(failCount = 9, lockedUntilMs = 1_000_000 + tenMinutes, delayMs = tenMinutes, monoAtMs = 0, bootId = "b1")

    private fun afterReboot(wallMs: Long) = PinLockout.remainingMs(writtenAtBootStart, PinTime(wallMs = wallMs, monoMs = 400_000, bootId = "b2"))

    @Test fun aRebootWithAMonotonicReadingNotBelowTheOldOneIsRecognisedByTheBootIdentityAndTheWallClockIsOnlyASecondOpinion() {
        val sameBootRule = tenMinutes - 400_000L // what the monotonic reading alone would say: 200 s
        // The phone was off for a short while: the wall clock says 500 s are left, and that counts (a second opinion that can lengthen).
        assertEquals(500_000L, afterReboot(wallMs = 1_100_000))
        // The wall clock moved forward by a day or back by a day: it never makes the delay SHORTER than counting again from the reboot.
        assertEquals(sameBootRule, afterReboot(wallMs = 1_000_000 + 86_400_000))
        assertEquals(tenMinutes, afterReboot(wallMs = 1_000_000 - 86_400_000)) // back: capped at the delay itself
        for (wall in listOf(0L, 1_000_000L, 1_100_000L, 1_000_000L + 86_400_000, 1_000_000L - 86_400_000)) {
            assertTrue("wall $wall", afterReboot(wall) >= sameBootRule)
        }
    }

    @Test fun theSameBootWithTheSameReadingIsStillDecidedByTheMonotonicClockAlone() {
        val sameBoot = PinTime(wallMs = 1_000_000 + 86_400_000, monoMs = 400_000, bootId = "b1")
        assertEquals(200_000L, PinLockout.remainingMs(writtenAtBootStart, sameBoot)) // the wall clock moved forward: no effect
    }

    // ---------------------------------------------------------------- the lock after inactivity (a fixed clock)

    private class TestClock : MonotonicClock {
        var ms = 5_000_000L
        override fun elapsedMs() = ms
        override fun bootId() = "boot-1"
        fun plusMinutes(m: Long) { ms += m * 60_000 }
    }

    @Test fun theAppStartsLockedAndOnlyAnExplicitUnlockOpensIt() {
        val lock = AppLock(TestClock())
        assertTrue(lock.locked.value) // every start, every cold process
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test fun itLocksAfterTheSetMinutesOfInactivityAndNotBefore() {
        val clock = TestClock()
        val lock = AppLock(clock).also { it.unlock() }
        clock.plusMinutes(4)
        assertFalse(lock.checkIdle(lockMinutes = 5))
        assertFalse(lock.locked.value)
        clock.plusMinutes(1) // five minutes in all
        assertTrue(lock.checkIdle(lockMinutes = 5))
        assertTrue(lock.locked.value)
    }

    @Test fun touchesKeepItOpen() {
        val clock = TestClock()
        val lock = AppLock(clock).also { it.unlock() }
        repeat(3) { clock.plusMinutes(4); lock.noteActivity() }
        assertFalse(lock.checkIdle(5))
        assertFalse(lock.locked.value)
    }

    @Test fun comingBackFromTheBackgroundAfterTheSetMinutesLocks() {
        val clock = TestClock()
        val lock = AppLock(clock).also { it.unlock() }
        clock.plusMinutes(2)
        lock.onForegrounded(lockMinutes = 5)
        assertFalse("two minutes away is fine", lock.locked.value)
        clock.plusMinutes(6)
        lock.onForegrounded(lockMinutes = 5)
        assertTrue("six minutes away locks", lock.locked.value)
    }

    @Test fun theLockMinutesAreTheSettingNotAConstant() {
        val clock = TestClock()
        val lock = AppLock(clock).also { it.unlock() }
        clock.plusMinutes(2)
        assertTrue(lock.checkIdle(lockMinutes = 1))
        lock.unlock()
        clock.plusMinutes(29)
        assertFalse(lock.checkIdle(lockMinutes = 30))
    }

    @Test fun anUnlockedAppDoesNotRelockWhileLockedStateIsAlreadyLocked() {
        val clock = TestClock()
        val lock = AppLock(clock)
        clock.plusMinutes(60)
        lock.noteActivity() // a touch on the PIN screen does not open anything
        assertTrue(lock.locked.value)
    }
}
