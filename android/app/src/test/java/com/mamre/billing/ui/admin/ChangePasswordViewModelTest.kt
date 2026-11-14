package com.mamre.billing.ui.admin

import com.mamre.billing.data.local.MutableClock
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.CurrentUser
import com.mamre.billing.domain.usecase.ChangeOwnPassword
import com.mamre.billing.domain.usecase.CreateAdmin
import com.mamre.billing.domain.usecase.Login
import com.mamre.billing.domain.usecase.LoginResult
import com.mamre.billing.ui.ViewModelScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Change my password screen logic (B8 f): current password once, new twice, same delay as the login screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChangePasswordViewModelTest {
    private lateinit var w: World
    private lateinit var lock: AppLock
    private lateinit var users: UsersRepository
    private val current = CurrentUser()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory(), MutableClock()).also { it.seed() } }
        users = UsersRepository(w.db.userDao())
        runBlocking {
            w.db.openHelper.writableDatabase.execSQL("DELETE FROM users")
            CreateAdmin(w.unitOfWork, users, w.changeLog, w.clock)("Rajesh Admin", "rajesh", "secret1", "secret1")
            current.set(users.accounts().single())
        }
        lock = AppLock(w.clock)
        lock.unlock()
    }

    private val scopes = ViewModelScopes()

    @After fun tearDown() {
        scopes.endAll() // before resetMain: see ViewModelScopes
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun vm() = scopes.track(
        ChangePasswordViewModel(ChangeOwnPassword(w.unitOfWork, users, w.changeLog, w.clock, w.clock), current, lock, w.clock),
    )

    private fun login(password: String) = runBlocking { Login(w.unitOfWork, users, w.settings, w.clock, w.clock)("rajesh", password) }

    private suspend fun ChangePasswordViewModel.until(p: (ChangePasswordUi) -> Boolean): ChangePasswordUi = withTimeout(10_000) { ui.first(p) }

    private fun ChangePasswordViewModel.fill(cur: String, new: String, again: String) {
        onCurrent(cur); onNew(new); onConfirm(again)
    }

    @Test fun theButtonNeedsAllThreeFieldsAndAtLeastSixCharactersInTheNewPassword() = runBlocking {
        val v = vm()
        v.fill("secret1", "short", "short")
        assertFalse(v.ui.value.canSubmit)
        v.fill("secret1", "newsecret", "newsecret")
        assertTrue(v.ui.value.canSubmit)
    }

    @Test fun aWrongCurrentPasswordIsRefusedCountedAndTheFieldsAreCleared() = runBlocking {
        val v = vm()
        v.fill("wrong-one", "newsecret", "newsecret")
        v.submit()
        val ui = v.until { it.message != null }
        assertEquals("Wrong current password. 4 tries left before a wait.", ui.message)
        assertEquals("", ui.current + ui.newPassword + ui.confirm)
        assertFalse(ui.done)
        assertEquals(1, w.db.userDao().byUsername("rajesh")!!.failCount)
        assertTrue(login("secret1") is LoginResult.Ok) // nothing changed
    }

    @Test fun fiveWrongCurrentPasswordsShowTheWaitAndEvenTheRightOneIsRefusedUntilItEnds() = runBlocking {
        val v = vm()
        repeat(5) { v.fill("wrong-one", "newsecret", "newsecret"); v.submit(); v.until { !it.busy && it.current.isEmpty() } }
        val ui = v.until { it.waitMs > 0 }
        assertEquals(30_000L, ui.waitMs)
        assertTrue(ui.message!!.contains("wait"))
        v.fill("secret1", "newsecret", "newsecret")
        assertFalse("no submit during the delay", v.ui.value.canSubmit)
        assertEquals(LoginResult.Locked(30_000), login("secret1")) // the login screen counts the same delay: one counter
        w.clock.set("2026-10-04T10:00:31Z")
        assertTrue(login("secret1") is LoginResult.Ok)
    }

    @Test fun aCorrectCurrentPasswordChangesItReturnsAndRestartsTheLockTimer() = runBlocking {
        val v = vm()
        w.clock.advanceMinutes(4) // 4 of the 5 idle minutes are gone
        v.fill("secret1", "newsecret", "newsecret")
        v.submit()
        val ui = v.until { it.done }
        assertEquals("", ui.current + ui.newPassword + ui.confirm)
        assertTrue(login("secret1") is LoginResult.Wrong)
        assertTrue(login("newsecret") is LoginResult.Ok)
        w.clock.advanceMinutes(2)
        assertFalse("the lock timer was restarted by the change", lock.checkIdle(5))
        w.clock.advanceMinutes(4)
        assertTrue(lock.checkIdle(5))
    }

    @Test fun aNewPasswordEqualToTheCurrentOneIsRefusedWithAPlainSentence() = runBlocking {
        val v = vm()
        v.fill("secret1", "secret1", "secret1")
        v.submit()
        val ui = v.until { it.message != null }
        assertEquals("The new password must be different from the current password.", ui.message)
        assertFalse(ui.done)
    }

    @Test fun twoDifferentNewEntriesAreRefusedAndNothingChanges() = runBlocking {
        val v = vm()
        v.fill("secret1", "newsecret", "newsecret2")
        v.submit()
        val ui = v.until { it.message != null }
        assertTrue(ui.message!!.contains("same"))
        assertTrue(login("secret1") is LoginResult.Ok)
    }
}
