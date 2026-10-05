package com.mamre.billing.data.api

import com.mamre.billing.domain.auth.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeCredentialsTest {
    @Test fun adminAccountOpensTheAdminArea() {
        val a = FakeCredentials.find("admin", "admin5")!!
        assertEquals(Area.ADMIN, a.area)
    }

    @Test fun userAccountOpensTheSalesArea() {
        val a = FakeCredentials.find("user1", "user1")!!
        assertEquals(Area.SALES, a.area)
        assertEquals("Rajesh", a.fullName)
    }

    @Test fun exactlyTwoAccountsExistAndEverythingElseIsRejected() {
        assertEquals(2, FakeCredentials.accounts.size)
        assertNull(FakeCredentials.find("admin", "admin"))
        assertNull(FakeCredentials.find("admin", "Admin5"))
        assertNull(FakeCredentials.find("user1", "admin5"))
        assertNull(FakeCredentials.find("user2", "user2"))
        assertNull(FakeCredentials.find("", ""))
        assertNull(FakeCredentials.find("user1 ", "user1"))
    }
}
