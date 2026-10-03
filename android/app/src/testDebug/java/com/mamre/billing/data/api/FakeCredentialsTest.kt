package com.mamre.billing.data.api

import com.mamre.billing.domain.auth.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeCredentialsTest {
    @Test fun adminAccountIsAnAdmin() {
        val a = FakeCredentials.find("admin", "admin5")!!
        assertEquals(Role.ADMIN, a.role)
        assertNull(a.deviceCode)
    }

    @Test fun workerAccountIsAWorkerWithADevice() {
        val a = FakeCredentials.find("user1", "user1")!!
        assertEquals(Role.WORKER, a.role)
        assertEquals("W1", a.deviceCode)
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
