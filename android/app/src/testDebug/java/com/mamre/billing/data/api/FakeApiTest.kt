package com.mamre.billing.data.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** FakeApi stands in for the server until one is hosted (task 4d). */
class FakeApiTest {
    private val api = FakeApi()

    private suspend fun expectError(status: Int, code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected ApiException $code")
        } catch (e: ApiException) {
            assertEquals(status, e.status)
            assertEquals(code, e.code)
        }
    }

    private suspend fun workerToken() = api.login("user1", "user1").access

    @Test fun loginWithAnythingButTheTwoTestAccountsIsInvalidCredentials() = runTest {
        expectError(401, "invalid_credentials") { api.login("", "x") }
        expectError(401, "invalid_credentials") { api.login("user1", "") }
        expectError(401, "invalid_credentials") { api.login("worker", "pw") }
        expectError(401, "invalid_credentials") { api.login("user1", "admin5") }
        expectError(401, "invalid_credentials") { api.login("admin", "user1") }
        expectError(401, "invalid_credentials") { api.login("ADMIN", "admin5") }
    }

    @Test fun loginReturnsTokensAndMeIsAWorker() = runTest {
        val me = api.me(workerToken())
        assertEquals("worker", me.role)
        assertEquals("W1", me.deviceCode)
    }


    @Test fun theDemoSalesmanIsCalledRajesh() = runTest {
        assertEquals("Rajesh", api.me(workerToken()).fullName)
    }
    @Test fun adminLoginReturnsRoleAdminFromMeNotFromTheUsername() = runTest {
        val me = api.me(api.login("admin", "admin5").access)
        assertEquals("admin", me.role)
        assertEquals(null, me.deviceCode)
    }

    @Test fun badAccessTokenIs401SoTheRefreshPathCanBeExercised() = runTest {
        expectError(401, "not_authenticated") { api.me("garbage") }
        expectError(401, "not_authenticated") { api.catalog("garbage", 0) }
    }

    @Test fun refreshIssuesAnAccessTokenOnlyForAFakeRefreshToken() = runTest {
        val t = api.login("user1", "user1")
        val fresh = api.refresh(t.refresh)
        assertEquals("worker", api.me(fresh).role)
        expectError(401, "token_not_valid") { api.refresh("garbage") }
    }

    @Test fun catalogHasNoCostKeysAndNoZeroPrices() = runTest {
        val c = api.catalog(workerToken(), 0)
        assertTrue(c.products.isNotEmpty())
        assertTrue(c.priceDefaults.all { it.unitPriceCents > 0 })
        assertTrue(c.customerTypes.any { it.name == "Retail" })
    }

    @Test fun catalogWithCurrentCursorIsEmptyAndKeepsTheCursor() = runTest {
        val token = workerToken()
        val first = api.catalog(token, 0)
        val again = api.catalog(token, first.cursor)
        assertEquals(first.cursor, again.cursor)
        assertTrue(again.products.isEmpty() && again.priceDefaults.isEmpty())
    }
}
