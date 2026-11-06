package com.mamre.billing.data.local

import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.usecase.ChangeOwnPassword
import com.mamre.billing.domain.usecase.CreateAdmin
import com.mamre.billing.domain.usecase.Login
import com.mamre.billing.domain.usecase.LoginResult
import com.mamre.billing.domain.security.PinLockState
import com.mamre.billing.domain.security.PinLockout
import com.mamre.billing.domain.security.PinTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B8 (was PIN review item 3): the phone's date and time can be changed by whoever holds the phone, so the wrong-password delay and the idle lock
 * run on a MONOTONIC clock (a controllable one here) and the wall clock is only a second opinion after a reboot. Each test moves
 * the two clocks separately: MutableClock.jumpWallMs moves only the wall clock, reboot() restarts the monotonic clock.
 */
@RunWith(RobolectricTestRunner::class)
class LoginClockTest {
    private val file = "login-clock-test.db"
    private val hour = 3_600_000L

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun lockedWorld(db: MamreDatabase = TestDatabase.inMemory()): World {
        val w = World(db).also { it.seed() }
        makeAdmin(w)
        repeat(5) { verify(w, "bad-pass") } // the 5th wrong password: a 30 second delay
        return w
    }

    /** The seeded test accounts are removed so the admin below is the only account; foreign keys are not involved (no bills here). */
    private fun makeAdmin(w: World) = runBlocking {
        w.db.openHelper.writableDatabase.execSQL("DELETE FROM users")
        CreateAdmin(w.unitOfWork, UsersRepository(w.db.userDao()), w.changeLog, w.clock)("Rajesh Admin", "rajesh", "secret1", "secret1")
    }

    private fun verify(w: World, password: String) =
        runBlocking { Login(w.unitOfWork, UsersRepository(w.db.userDao()), w.settings, w.clock, w.clock)("rajesh", password) }

    private fun locked(ms: Long) = LoginResult.Locked(ms)

    private fun assertOk(r: LoginResult) = assertTrue("expected Ok but was $r", r is LoginResult.Ok)

    // ---------------------------------------------------------------- normal waiting

    @Test fun normalWaitingCountsDownAndTheRightPasswordOpensAfterTheDelay() = runBlocking {
        val w = lockedWorld()
        assertEquals(locked(30_000), verify(w, "secret1"))
        w.clock.advanceMs(10_000)
        assertEquals(locked(20_000), verify(w, "secret1"))
        w.clock.advanceMs(19_999)
        assertEquals(locked(1), verify(w, "secret1"))
        w.clock.advanceMs(1)
        assertOk(verify(w, "secret1"))
    }

    // ---------------------------------------------------------------- the wall clock moved while the app keeps running

    @Test fun aWallClockMovedForwardDoesNotShortenTheDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000)
        w.clock.jumpWallMs(+hour)
        assertEquals(locked(20_000), verify(w, "secret1")) // not 0
        w.clock.advanceMs(20_000)
        assertOk(verify(w, "secret1"))
    }

    @Test fun aWallClockMovedBackwardsDoesNotStretchTheDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000)
        w.clock.jumpWallMs(-hour)
        assertEquals(locked(20_000), verify(w, "secret1")) // not an hour and 20 seconds
        w.clock.advanceMs(20_000)
        assertOk(verify(w, "secret1"))
    }

    @Test fun aWrongPasswordAfterTheDelayDoublesItEvenWithTheWallClockMovedBack() = runBlocking {
        val w = lockedWorld()
        w.clock.jumpWallMs(-hour)
        w.clock.advanceMs(30_000)
        assertEquals(locked(60_000), verify(w, "bad-pass")) // the 6th wrong password
        w.clock.jumpWallMs(+10 * hour)
        w.clock.advanceMs(59_000)
        assertEquals(locked(1_000), verify(w, "secret1"))
    }

    // ---------------------------------------------------------------- reboot

    @Test fun afterARebootTheDelayIsNeverShorterThanItWas() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000) // 20 s were still to go
        w.clock.reboot(sinceBootMs = 2_000)
        val after = (verify(w, "secret1") as LoginResult.Locked).remainingMs
        assertTrue("$after must be at least the true 20000", after >= 20_000)
        assertTrue("$after is at most the whole delay", after <= 30_000)
    }

    @Test fun aRebootPlusTheWallClockMovedForwardStillLeavesTheDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.advanceMs(10_000)
        w.clock.reboot(sinceBootMs = 2_000)
        w.clock.jumpWallMs(+hour) // the wall clock says the delay is long over
        val after = (verify(w, "secret1") as LoginResult.Locked).remainingMs
        assertTrue("$after must not be 0 and not shorter than the true 20000", after >= 20_000)
    }

    @Test fun aRebootPlusTheWallClockMovedBackIsCappedAtTheDelayItself() = runBlocking {
        val w = lockedWorld()
        w.clock.reboot(sinceBootMs = 2_000)
        w.clock.jumpWallMs(-hour)
        val after = (verify(w, "secret1") as LoginResult.Locked).remainingMs
        assertEquals("never more than the whole 30 second delay, not an hour", 30_000L, after)
    }

    @Test fun afterARebootTheDelayEndsOnlyWhenBothTheTimeSinceBootAndTheWallClockSayItIsOver() = runBlocking {
        val w = lockedWorld()
        w.clock.reboot(sinceBootMs = 2_000) // the phone was back at once: the wall clock has not moved
        w.clock.advanceMs(27_999) // 29 999 ms since boot (1 ms to go by that count), wall clock 2 001 ms short of the deadline
        assertEquals(locked(2_001), verify(w, "secret1")) // the larger of the two
        w.clock.advanceMs(2_001)
        assertOk(verify(w, "secret1"))
    }

    @Test fun theCounterAndTheDelaySurviveARebootAndTheDatabaseBeingReopened() = runBlocking {
        var w = lockedWorld(TestDatabase.file(file))
        w.db.close()
        w.clock.reboot(sinceBootMs = 1_000)
        w = World(TestDatabase.file(file), w.clock)
        assertTrue(verify(w, "secret1") is LoginResult.Locked)
        assertEquals(5, w.db.userDao().byUsername("rajesh")!!.failCount)
        w.db.close()
    }

    @Test fun aRebootNeverResetsTheWrongPasswordCounter() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        makeAdmin(w)
        repeat(4) { verify(w, "bad-pass") }
        w.clock.reboot()
        assertEquals(locked(30_000), verify(w, "bad-pass")) // the 5th wrong password counts across the reboot
    }

    @Test fun changeOwnPasswordSharesTheSameMonotonicDelay() = runBlocking {
        val w = lockedWorld()
        w.clock.jumpWallMs(+hour)
        val users = UsersRepository(w.db.userDao())
        val change = ChangeOwnPassword(w.unitOfWork, users, w.changeLog, w.clock, w.clock)
        val e = runCatching { change(users.byUsername("rajesh")!!.id, "secret1", "newsecret", "newsecret") }.exceptionOrNull()
        assertTrue(e is com.mamre.billing.domain.usecase.PasswordDelay)
        assertEquals(30_000L, (e as com.mamre.billing.domain.usecase.PasswordDelay).remainingMs)
    }

    // ---------------------------------------------------------------- the pure rule, including odd stored values

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
