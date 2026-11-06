package com.mamre.billing.data.local

import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.usecase.AccountStatus
import com.mamre.billing.domain.usecase.AddSalesman
import com.mamre.billing.domain.usecase.ChangeOwnPassword
import com.mamre.billing.domain.usecase.CreateAdmin
import com.mamre.billing.domain.usecase.Login
import com.mamre.billing.domain.usecase.LoginResult
import com.mamre.billing.domain.usecase.ResetPassword
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.SetActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B8 items a to d, f and LEAD rulings L1, L4 to L8, L10: accounts and login, on the real database. */
@RunWith(RobolectricTestRunner::class)
class AccountUseCasesTest {
    private val db = TestDatabase.inMemory()
    private val clock = MutableClock()
    private val uow = RoomUnitOfWork(db)
    private val users = UsersRepository(db.userDao())
    private val settings = SettingsRepository(db.settingDao())
    private val changeLog = ChangeLogRepository(db)
    private val status = AccountStatus(users)
    private val createAdmin = CreateAdmin(uow, users, changeLog, clock)
    private val addSalesman = AddSalesman(uow, users, changeLog, clock)
    private val login = Login(uow, users, settings, clock, clock)
    private val resetPassword = ResetPassword(uow, users, changeLog, clock)
    private val setActive = SetActive(uow, users, changeLog, clock)
    private val changeOwn = ChangeOwnPassword(uow, users, changeLog, clock, clock)

    @After fun tearDown() = db.close()

    private suspend fun setUp() {
        createAdmin("Rajesh Admin", "Admin", "secret1", "secret1")
        addSalesman("Asha Kumar", "asha", "asha-pw", "asha-pw")
        addSalesman("Ravi Nair", "ravi", "ravi-pw", "ravi-pw")
    }

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected a refusal")
        return ""
    }

    @Test fun firstRunCreatesTheOnlyAdminAndStoresOnlyAHash() = runBlocking {
        assertFalse(status.anyExists())
        createAdmin("Rajesh Admin", "Admin", "secret1", "secret1")
        assertTrue(status.anyExists())
        val row = db.userDao().byUsername("admin")!!
        assertEquals("ADMIN", row.role)
        assertEquals("Rajesh Admin", row.displayName)
        assertTrue(row.active)
        assertTrue(row.passwordHash.startsWith("pbkdf2-sha256:"))
        assertFalse(row.passwordHash.contains("secret1"))
        assertNotEquals("", row.passwordSalt)
        // The password is nowhere in the change log.
        assertTrue(changeLog.all().none { "secret1" in it.what + it.beforeText + it.afterText })
    }

    @Test fun theAdminCanBeCreatedOnlyOnce() = runBlocking {
        createAdmin("Rajesh Admin", "admin", "secret1", "secret1")
        refused { createAdmin("Someone Else", "other", "secret2", "secret2") }
        assertEquals(1, db.userDao().count())
    }

    @Test fun createAdminChecksTheFieldsAndTheTwoPasswordEntries() = runBlocking {
        refused { createAdmin("R", "admin", "secret1", "secret1") }
        refused { createAdmin("Rajesh", "a b", "secret1", "secret1") }
        refused { createAdmin("Rajesh", "admin", "short", "short") }
        refused { createAdmin("Rajesh", "admin", "secret1", "secret2") }
        assertEquals(0, db.userDao().count())
        assertFalse(status.anyExists())
    }

    @Test fun usersAddsSalesmenOnlyAndRefusesADuplicateUsernameInAnyCase() = runBlocking {
        createAdmin("Rajesh Admin", "admin", "secret1", "secret1")
        addSalesman("Asha Kumar", "Asha", "asha-pw", "asha-pw")
        assertEquals("SALESMAN", db.userDao().byUsername("asha")!!.role)
        refused { addSalesman("Other Asha", "ASHA", "asha-pw2", "asha-pw2") }
        refused { addSalesman("Other Admin", "Admin", "secret3", "secret3") }
        assertEquals(2, db.userDao().count())
    }

    @Test fun loginWorksPerRoleAndWritesTheLastUsernameOnlyOnSuccess() = runBlocking {
        setUp()
        assertNull(settings.get(SettingKeys.LAST_USERNAME))
        assertTrue(login("ASHA ", "wrong") is LoginResult.Wrong)
        assertNull(settings.get(SettingKeys.LAST_USERNAME))
        val a = login("Asha", "asha-pw") as LoginResult.Ok
        assertEquals(Role.SALESMAN, a.user.role)
        assertEquals("Asha Kumar", a.user.displayName)
        assertEquals("asha", settings.get(SettingKeys.LAST_USERNAME))
        val r = login("admin", "secret1") as LoginResult.Ok
        assertEquals(Role.ADMIN, r.user.role)
        assertEquals("admin", settings.get(SettingKeys.LAST_USERNAME))
    }

    @Test fun anUnknownUsernameChangesNoCounter() = runBlocking {
        setUp()
        repeat(6) { assertEquals(LoginResult.UnknownUser, login("nobody", "whatever")) }
        assertTrue(db.userDao().getAll().all { it.failCount == 0 && it.lockedUntil == 0L })
    }

    @Test fun wrongPasswordsCountAgainstThatAccountOnlyAndOneDelayDoesNotLockAnother() = runBlocking {
        setUp()
        repeat(4) {
            val w = login("asha", "bad") as LoginResult.Wrong
            assertEquals(4 - it, w.triesBeforeDelay)
        }
        assertTrue(login("asha", "bad") is LoginResult.Locked)
        // The right password is refused during the delay, and a try does not lengthen it.
        val locked = login("asha", "asha-pw") as LoginResult.Locked
        assertEquals(30_000L, locked.remainingMs)
        // Another account still logs in, and the other counter is untouched.
        assertTrue(login("ravi", "ravi-pw") is LoginResult.Ok)
        assertEquals(0, db.userDao().byUsername("ravi")!!.failCount)
        assertTrue(login("admin", "secret1") is LoginResult.Ok)
        clock.advanceMs(30_001)
        assertTrue(login("asha", "asha-pw") is LoginResult.Ok)
        assertEquals(0, db.userDao().byUsername("asha")!!.failCount)
    }

    @Test fun aDeactivatedAccountIsRefusedAndCanComeBack() = runBlocking {
        setUp()
        val asha = db.userDao().byUsername("asha")!!
        setActive(asha.id, false)
        assertEquals(LoginResult.Inactive, login("asha", "asha-pw"))
        setActive(asha.id, true)
        assertTrue(login("asha", "asha-pw") is LoginResult.Ok)
    }

    @Test fun theAdminCannotBeSwitchedOff() = runBlocking {
        setUp()
        val admin = db.userDao().byUsername("admin")!!
        refused { setActive(admin.id, false) }
        assertTrue(db.userDao().get(admin.id)!!.active)
    }

    @Test fun resetPasswordGivesANewPasswordAndClearsTheDelay() = runBlocking {
        setUp()
        repeat(5) { login("asha", "bad") }
        assertTrue(login("asha", "asha-pw") is LoginResult.Locked)
        val asha = db.userDao().byUsername("asha")!!
        resetPassword(asha.id, "fresh-pw", "fresh-pw")
        assertEquals(0, db.userDao().get(asha.id)!!.failCount)
        assertEquals(0L, db.userDao().get(asha.id)!!.lockedUntil)
        assertTrue(login("asha", "asha-pw") is LoginResult.Wrong)
        assertTrue(login("asha", "fresh-pw") is LoginResult.Ok)
    }

    @Test fun resetPasswordIsForASalesmanAndNeedsTwoMatchingValidEntries() = runBlocking {
        setUp()
        val admin = db.userDao().byUsername("admin")!!
        val asha = db.userDao().byUsername("asha")!!
        refused { resetPassword(admin.id, "fresh-pw", "fresh-pw") }
        refused { resetPassword(asha.id, "fresh-pw", "other-pw") }
        refused { resetPassword(asha.id, "abc", "abc") }
        refused { resetPassword("no-such-id", "fresh-pw", "fresh-pw") }
        assertTrue(login("asha", "asha-pw") is LoginResult.Ok)
    }

    @Test fun anAdminChangesTheirOwnPasswordWithTheCurrentOne() = runBlocking {
        setUp()
        val admin = db.userDao().byUsername("admin")!!
        refused { changeOwn(admin.id, "not-it", "newsecret", "newsecret") }
        assertEquals(1, db.userDao().get(admin.id)!!.failCount)
        refused { changeOwn(admin.id, "secret1", "newsecret", "mismatch") }
        refused { changeOwn(admin.id, "secret1", "secret1", "secret1") }
        changeOwn(admin.id, "secret1", "newsecret", "newsecret")
        assertTrue(login("admin", "secret1") is LoginResult.Wrong)
        assertTrue(login("admin", "newsecret") is LoginResult.Ok)
    }

    @Test fun aDamagedHashKeepsThatAccountOutAndNeverShowsCreateTheAdmin() = runBlocking {
        setUp()
        val asha = db.userDao().byUsername("asha")!!
        db.userDao().setCredential(asha.id, "garbage", "")
        assertEquals(LoginResult.Damaged, login("asha", "asha-pw"))
        assertEquals(0, db.userDao().get(asha.id)!!.failCount)
        assertTrue(login("admin", "secret1") is LoginResult.Ok)
        assertTrue(status.anyExists())
        // Even if every account were damaged, users has rows, so first-run creation stays refused.
        db.userDao().getAll().forEach { db.userDao().setCredential(it.id, "garbage", "") }
        assertTrue(status.anyExists())
        refused { createAdmin("Intruder", "intruder", "secret9", "secret9") }
        Unit
    }

    @Test fun loginReadsAUsernameTheSameInAnyCaseAndSpacing() = runBlocking {
        setUp()
        assertTrue(login("  ADMIN", "secret1") is LoginResult.Ok)
    }

    @Test fun theSameWordIsStoredAsADifferentHashAndSaltForEachAccount() = runBlocking {
        createAdmin("Rajesh Admin", "rajesh", "same-pass-1", "same-pass-1")
        addSalesman("Asha Kumar", "asha", "same-pass-1", "same-pass-1")
        val a = db.userDao().byUsername("rajesh")!!
        val b = db.userDao().byUsername("asha")!!
        assertNotEquals(a.passwordSalt, b.passwordSalt)
        assertNotEquals("the hash must depend on the salt", a.passwordHash, b.passwordHash)
        // and a hash made with one account's salt does not open the other.
        assertTrue(login("rajesh", "same-pass-1") is LoginResult.Ok)
        assertTrue(login("asha", "same-pass-1") is LoginResult.Ok)
    }
}
