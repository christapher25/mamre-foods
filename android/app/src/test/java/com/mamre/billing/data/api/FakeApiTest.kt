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

    @Test fun loginWithBlankFieldsIsInvalidCredentials() = runTest {
        expectError(401, "invalid_credentials") { api.login("", "x") }
        expectError(401, "invalid_credentials") { api.login("worker", "") }
    }

    @Test fun loginReturnsTokensAndMeIsAWorker() = runTest {
        val t = api.login("worker", "pw")
        val me = api.me(t.access)
        assertEquals("worker", me.role)
        assertEquals("W1", me.deviceCode)
    }

    @Test fun badAccessTokenIs401SoTheRefreshPathCanBeExercised() = runTest {
        expectError(401, "not_authenticated") { api.me("garbage") }
        expectError(401, "not_authenticated") { api.catalog("garbage", 0) }
    }

    @Test fun refreshIssuesAnAccessTokenOnlyForAFakeRefreshToken() = runTest {
        val t = api.login("worker", "pw")
        val fresh = api.refresh(t.refresh)
        assertEquals("worker", api.me(fresh).role)
        expectError(401, "token_not_valid") { api.refresh("garbage") }
    }

    @Test fun catalogHasNoCostKeysAndNoZeroPrices() = runTest {
        val t = api.login("worker", "pw")
        val c = api.catalog(t.access, 0)
        assertTrue(c.products.isNotEmpty())
        assertTrue(c.priceDefaults.all { it.unitPriceCents > 0 })
        assertTrue(c.customerTypes.any { it.name == "Retail" })
    }

    @Test fun catalogWithCurrentCursorIsEmptyAndKeepsTheCursor() = runTest {
        val t = api.login("worker", "pw")
        val first = api.catalog(t.access, 0)
        val again = api.catalog(t.access, first.cursor)
        assertEquals(first.cursor, again.cursor)
        assertTrue(again.products.isEmpty() && again.priceDefaults.isEmpty())
    }
}
