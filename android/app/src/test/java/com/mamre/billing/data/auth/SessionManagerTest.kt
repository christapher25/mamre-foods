package com.mamre.billing.data.auth

import com.mamre.billing.data.api.ApiException
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.data.api.CatalogPull
import com.mamre.billing.data.api.Me
import com.mamre.billing.data.api.TokenPair
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun apiError(status: Int, code: String = "x") =
    ApiException(status, code, "msg", JsonObject(emptyMap()))

class MemoryTokenStore(
    override var accessToken: String? = null,
    override var refreshToken: String? = null,
) : TokenStore {
    override fun save(access: String, refresh: String) {
        accessToken = access
        refreshToken = refresh
    }
    override fun saveAccess(access: String) {
        accessToken = access
    }
    override fun clear() {
        accessToken = null
        refreshToken = null
    }
}

/** Only login and refresh matter to SessionManager. */
private class ScriptedApi : BackendApi {
    var loginResult: () -> TokenPair = { TokenPair("a1", "r1") }
    var refreshResult: () -> String = { "a2" }
    var refreshCalls = 0
    var refreshDelayMs = 0L

    override suspend fun login(username: String, password: String) = loginResult()
    override suspend fun refresh(refreshToken: String): String {
        refreshCalls++
        if (refreshDelayMs > 0) delay(refreshDelayMs)
        return refreshResult()
    }
    override suspend fun me(accessToken: String): Me = error("not used")
    override suspend fun catalog(accessToken: String, cursor: Long): CatalogPull = error("not used")
}

/** Doc 2 s5 and s6.8: access ~15 min, refresh ~30 days, no rotation; 401 refreshes once. */
class SessionManagerTest {
    private val api = ScriptedApi()
    private val store = MemoryTokenStore()
    private var session = SessionManager(api, store)

    /** Tokens already on the device when the app starts. */
    private fun startWith(access: String, refresh: String) {
        store.save(access, refresh)
        session = SessionManager(api, store)
    }

    @Test fun signedInStartsFromStoredRefreshToken() {
        assertFalse(SessionManager(api, MemoryTokenStore()).signedIn.value)
        assertTrue(SessionManager(api, MemoryTokenStore("a", "r")).signedIn.value)
    }

    @Test fun loginStoresBothTokens() = runTest {
        session.login("w", "pw")
        assertEquals("a1", store.accessToken)
        assertEquals("r1", store.refreshToken)
        assertTrue(session.signedIn.value)
    }

    @Test fun failedLoginStoresNothing() = runTest {
        api.loginResult = { throw apiError(401, "invalid_credentials") }
        try {
            session.login("w", "bad")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("invalid_credentials", e.code)
        }
        assertNull(store.accessToken)
        assertFalse(session.signedIn.value)
    }

    @Test fun authorizedCallUsesStoredAccessToken() = runTest {
        startWith("a1", "r1")
        assertEquals("used a1", session.authorized { "used $it" })
        assertEquals(0, api.refreshCalls)
    }

    @Test fun on401RefreshesOnceAndRetries() = runTest {
        startWith("old", "r1")
        val seen = mutableListOf<String>()
        val result = session.authorized { token ->
            seen += token
            if (token == "old") throw apiError(401, "not_authenticated")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf("old", "a2"), seen)
        assertEquals(1, api.refreshCalls)
        assertEquals("a2", store.accessToken)
        assertEquals("r1", store.refreshToken) // refresh tokens do not rotate
        assertTrue(session.signedIn.value)
    }

    @Test fun refreshFailureClearsTokensAndReturnsToLogin() = runTest {
        startWith("old", "r1")
        api.refreshResult = { throw apiError(401, "token_not_valid") }
        try {
            session.authorized<String> { throw apiError(401) }
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            // expected
        }
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertFalse(session.signedIn.value)
        assertEquals(1, api.refreshCalls)
    }

    @Test fun secondUnauthorizedAfterRefreshEndsTheSessionWithoutLooping() = runTest {
        startWith("old", "r1")
        try {
            session.authorized<String> { throw apiError(401) }
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            // expected
        }
        assertEquals(1, api.refreshCalls)
        assertFalse(session.signedIn.value)
    }

    @Test fun otherErrorsPassThroughWithoutRefresh() = runTest {
        startWith("a1", "r1")
        try {
            session.authorized<String> { throw apiError(403, "permission_denied") }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
        assertEquals(0, api.refreshCalls)
        assertTrue(session.signedIn.value)
    }

    @Test fun offlineDuringRefreshKeepsTheSession() = runTest {
        startWith("old", "r1")
        api.refreshResult = { throw IOException("no signal") }
        try {
            session.authorized<String> { throw apiError(401) }
            fail("expected IOException")
        } catch (e: IOException) {
            // Offline use must survive (Doc 2 s2): no logout on a network error.
        }
        assertEquals("r1", store.refreshToken)
        assertTrue(session.signedIn.value)
    }

    @Test fun throttledRefreshDoesNotLogOut() = runTest {
        startWith("old", "r1")
        api.refreshResult = { throw apiError(429, "throttled") }
        try {
            session.authorized<String> { throw apiError(401) }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(429, e.status)
        }
        assertTrue(session.signedIn.value)
    }

    @Test fun missingTokensMeansLoginRequired() = runTest {
        try {
            session.authorized { it }
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            // expected
        }
        assertFalse(session.signedIn.value)
    }

    @Test fun parallelCallsWithTheSameStaleTokenRefreshOnce() = runTest {
        startWith("old", "r1")
        api.refreshDelayMs = 100
        val results = (1..3).map {
            async {
                session.authorized { token ->
                    if (token == "old") throw apiError(401)
                    token
                }
            }
        }.awaitAll()
        assertEquals(listOf("a2", "a2", "a2"), results)
        assertEquals(1, api.refreshCalls)
    }

    @Test fun logoutClearsTokens() = runTest {
        session.login("w", "pw")
        session.logout()
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertFalse(session.signedIn.value)
    }
}
