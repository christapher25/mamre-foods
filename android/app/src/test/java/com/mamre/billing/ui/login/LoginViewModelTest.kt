package com.mamre.billing.ui.login

import com.mamre.billing.data.local.MutableClock
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.CurrentUser
import com.mamre.billing.domain.usecase.AccountStatus
import com.mamre.billing.domain.usecase.AddSalesman
import com.mamre.billing.domain.usecase.CreateAdmin
import com.mamre.billing.domain.usecase.Login
import com.mamre.billing.domain.usecase.SetActive
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The login screen logic (B8 c, d, h, L5, L6, L8, L9, L10): create the admin, log in, wrong passwords, the delay, log out. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LoginViewModelTest {
    private lateinit var w: World
    private lateinit var lock: AppLock
    private lateinit var current: CurrentUser
    private lateinit var users: UsersRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory(), MutableClock()).also { it.seed() } }
        // The test world already holds two test accounts; this screen is tested on an empty users table unless a test adds its own.
        runBlocking { w.db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF"); w.db.openHelper.writableDatabase.execSQL("DELETE FROM users") }
        lock = AppLock(w.clock)
        current = CurrentUser()
        users = UsersRepository(w.db.userDao())
    }

    private val scopes = ViewModelScopes()

    @After fun tearDown() {
        scopes.endAll() // before resetMain: see ViewModelScopes
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun createAdmin() = CreateAdmin(w.unitOfWork, users, w.changeLog, w.clock)
    private fun addSalesman() = AddSalesman(w.unitOfWork, users, w.changeLog, w.clock)

    private fun vm() = scopes.track(
        LoginViewModel(AccountStatus(users), createAdmin(), Login(w.unitOfWork, users, w.settings, w.clock, w.clock), w.settings, lock, current, w.clock),
    )

    private suspend fun LoginViewModel.ready(mode: LoginMode): LoginUi = withTimeout(10_000) { ui.first { it.mode == mode } }

    private suspend fun LoginViewModel.until(p: (LoginUi) -> Boolean): LoginUi = withTimeout(10_000) { ui.first(p) }

    private suspend fun LoginViewModel.type(username: String, password: String) {
        onUsername(username)
        onPassword(password)
    }

    @Test fun onAFirstRunTheScreenAsksToCreateTheAdminWithTheNoRecoverySentence() = runBlocking {
        val v = vm()
        val ui = v.ready(LoginMode.CREATE_ADMIN)
        assertTrue(lock.locked.value)
        assertEquals(
            "If you forget the admin password, the only way back in is to clear the app's data, which deletes everything.",
            CREATE_ADMIN_NO_RECOVERY_TEXT,
        )
        assertNull(ui.message)
    }

    @Test fun twoDifferentPasswordsAreRefusedAndNoAccountIsMade() = runBlocking {
        val v = vm()
        v.ready(LoginMode.CREATE_ADMIN)
        v.onDisplayName("Rajesh Admin"); v.type("rajesh", "secret1"); v.onConfirm("secret2")
        v.submit()
        assertTrue(v.until { it.message != null }.message!!.contains("same"))
        assertFalse(AccountStatus(users).anyExists())
        assertTrue(lock.locked.value)
    }

    @Test fun theAdminIsCreatedThenTheBusinessScreenComesBeforeTheAppOpens() = runBlocking {
        val v = vm()
        v.ready(LoginMode.CREATE_ADMIN)
        v.onDisplayName("Rajesh Admin"); v.type("rajesh", "secret1"); v.onConfirm("secret1")
        v.submit()
        v.ready(LoginMode.BUSINESS) // Doc 1 s2.1 FR-2, straight after the admin account
        assertTrue(AccountStatus(users).anyExists())
        assertTrue("the app stays locked until the business screen is done", lock.locked.value)
        assertEquals("", v.ui.value.password)
        v.businessDone()
        withTimeout(10_000) { lock.locked.first { !it } }
        assertEquals(Role.ADMIN, current.account.value!!.role)
        assertEquals("Rajesh Admin", current.account.value!!.displayName)
    }

    @Test fun onALaterStartTheLoginIsShownWithTheLastUsernameFilledIn() = runBlocking {
        createAdmin()("Rajesh Admin", "rajesh", "secret1", "secret1")
        w.settings.put(SettingKeys.LAST_USERNAME, "rajesh")
        val v = vm()
        val ui = v.ready(LoginMode.LOGIN)
        assertEquals("rajesh", ui.username)
        v.businessDone() // does nothing outside the first run
        assertEquals(LoginMode.LOGIN, v.ui.value.mode)
    }

    @Test fun theRightPasswordOpensTheAppAndSetsTheCurrentAccount() = runBlocking {
        createAdmin()("Rajesh Admin", "rajesh", "secret1", "secret1")
        addSalesman()("Asha Kumar", "asha", "asha-pw", "asha-pw")
        val v = vm()
        v.ready(LoginMode.LOGIN)
        v.type("Asha", "asha-pw")
        v.submit()
        withTimeout(10_000) { lock.locked.first { !it } }
        assertEquals(Role.SALESMAN, current.account.value!!.role)
        assertEquals("", v.ui.value.password) // the field is cleared
        assertEquals("asha", w.settings.get(SettingKeys.LAST_USERNAME))
    }

    @Test fun theLoginScreenClearsWhoWasLoggedIn() = runBlocking {
        createAdmin()("Rajesh Admin", "rajesh", "secret1", "secret1")
        current.set(users.accounts().first())
        vm().ready(LoginMode.LOGIN)
        assertNull(current.account.value)
    }

    @Test fun aWrongPasswordShowsTheTriesLeftAndAnUnknownUserDoesNot() = runBlocking {
        createAdmin()("Rajesh Admin", "rajesh", "secret1", "secret1")
        val v = vm()
        v.ready(LoginMode.LOGIN)
        v.type("rajesh", "nope")
        v.submit()
        val wrong = v.until { it.message != null }
        assertEquals("Wrong username or password. 4 tries left before a wait.", wrong.message)
        assertEquals("", wrong.password)
        assertTrue(lock.locked.value)
        v.type("nobody", "nope")
        v.submit()
        assertEquals("Wrong username or password.", v.until { it.message == "Wrong username or password." }.message)
    }

    @Test fun aSwitchedOffAccountSaysSo() = runBlocking {
        createAdmin()("Rajesh Admin", "rajesh", "secret1", "secret1")
        addSalesman()("Asha Kumar", "asha", "asha-pw", "asha-pw")
        SetActive(w.unitOfWork, users, w.changeLog, w.clock)(users.byUsername("asha")!!.id, false)
        val v = vm()
        v.ready(LoginMode.LOGIN)
        v.type("asha", "asha-pw")
        v.submit()
        assertEquals("This account is switched off. Ask the admin.", v.until { it.message != null }.message)
        assertTrue(lock.locked.value)
    }

    @Test fun fiveWrongPasswordsShowTheWaitForThatAccountOnlyAndAnotherAccountStillLogsIn() = runBlocking {
        createAdmin()("Rajesh Admin", "rajesh", "secret1", "secret1")
        addSalesman()("Asha Kumar", "asha", "asha-pw", "asha-pw")
        val v = vm()
        v.ready(LoginMode.LOGIN)
        repeat(5) { v.type("asha", "bad"); v.submit(); v.until { it.password.isEmpty() && !it.busy } }
        val ui = v.until { it.waitMs > 0 }
        assertEquals(30_000L, ui.waitMs)
        assertTrue(ui.message!!.contains("wait"))
        v.type("asha", "asha-pw"); v.submit()
        v.until { !it.busy }
        assertTrue("the right password is refused during the delay", lock.locked.value)
        // L6: the other account is not held by it.
        v.type("rajesh", "secret1")
        assertTrue(v.ui.value.canSubmit)
        v.submit()
        withTimeout(10_000) { lock.locked.first { !it } }
        assertEquals("rajesh", current.account.value!!.username)
    }

    @Test fun aDamagedAccountShowsTheLoginNeverTheCreateAdminAndNothingOpens() = runBlocking {
        createAdmin()("Rajesh Admin", "rajesh", "secret1", "secret1")
        users.setCredential(users.byUsername("rajesh")!!.id, "garbage", "")
        val v = vm()
        val ui = v.ready(LoginMode.LOGIN) // never CREATE_ADMIN
        assertFalse(ui.mode == LoginMode.CREATE_ADMIN)
        v.type("rajesh", "secret1")
        v.submit()
        assertEquals(ACCOUNT_DAMAGED_TEXT, v.until { it.message != null }.message)
        assertTrue(lock.locked.value)
        assertNull(current.account.value)
    }

    @Test fun theSubmitButtonNeedsTheFieldsFilled() = runBlocking {
        val v = vm()
        v.ready(LoginMode.CREATE_ADMIN)
        assertFalse(v.ui.value.canSubmit)
        v.onDisplayName("Rajesh Admin"); v.type("rajesh", "secret1"); v.onConfirm("secret1")
        assertTrue(v.ui.value.canSubmit)
    }
}
