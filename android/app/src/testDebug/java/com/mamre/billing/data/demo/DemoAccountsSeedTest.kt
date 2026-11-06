package com.mamre.billing.data.demo

import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.usecase.Login
import com.mamre.billing.domain.usecase.LoginResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B8 item k: the DEBUG build seeds admin/admin5 (ADMIN, "Demo Admin") and user1/user1 (SALESMAN, "Demo Salesman"), and the seeded
 * bills, payments and returns are authored by the Demo Admin. If the seeder broke, the debug build would open on "Create the admin
 * account" or refuse admin/admin5, and no other test would notice.
 */
@RunWith(RobolectricTestRunner::class)
class DemoAccountsSeedTest {
    private var world: com.mamre.billing.data.local.World? = null

    @After fun close() {
        world?.db?.close()
    }

    private suspend fun open() = DemoWorld.open().also { world = it }

    private fun login(w: com.mamre.billing.data.local.World) =
        Login(w.unitOfWork, UsersRepository(w.db.userDao()), w.settings, w.clock, w.clock)

    @Test fun theSeederCreatesTheTwoDemoAccountsWithTheRightRolesAndNames() = runBlocking {
        val w = open()
        val admin = w.db.userDao().byUsername("admin")!!
        val user = w.db.userDao().byUsername("user1")!!
        assertEquals(Role.ADMIN.name, admin.role)
        assertEquals("Demo Admin", admin.displayName)
        assertEquals(Role.SALESMAN.name, user.role)
        assertEquals("Demo Salesman", user.displayName)
        assertTrue(admin.active && user.active)
    }

    @Test fun theDemoPasswordsLogInAndAWrongOneDoesNot() = runBlocking {
        val w = open()
        val login = login(w)
        val a = login("admin", "admin5") as LoginResult.Ok
        assertEquals(Role.ADMIN, a.user.role)
        val s = login("user1", "user1") as LoginResult.Ok
        assertEquals(Role.SALESMAN, s.user.role)
        assertTrue(login("admin", "not-the-password") is LoginResult.Wrong)
        assertTrue(login("user1", "admin5") is LoginResult.Wrong)
    }

    @Test fun everySeededBillPaymentAndReturnIsAuthoredByTheDemoAdmin() = runBlocking {
        val w = open()
        val adminId = w.db.userDao().byUsername("admin")!!.id
        val invoices = w.db.invoiceDao().getAll()
        assertTrue(invoices.isNotEmpty())
        assertTrue(invoices.all { it.createdByUserId == adminId && it.salesmanName == "Demo Admin" })
        assertTrue(w.db.paymentDao().getAll().all { it.createdByUserId == adminId && it.salesmanName == "Demo Admin" })
        assertTrue(w.db.returnDao().getAll().all { it.createdByUserId == adminId })
    }
}
