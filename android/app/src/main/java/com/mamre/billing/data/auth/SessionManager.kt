package com.mamre.billing.data.auth

import com.mamre.billing.data.api.ApiException
import com.mamre.billing.data.api.BackendApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The session is over and the worker must sign in again. */
class SessionExpiredException(cause: Throwable? = null) : Exception("Session expired", cause)

/**
 * Login, logout and the 401 rule (Doc 2 s5, s6.8): the access token lasts about 15 minutes
 * and the refresh token about 30 days without rotation. On a 401 the call refreshes once and
 * is retried once; if the refresh is refused, the tokens are cleared and [signedIn] turns
 * false so the UI returns to Login. A network error never ends the session (offline use
 * must survive, Doc 2 s2), and neither do 429 or 5xx answers.
 */
class SessionManager(
    private val api: BackendApi,
    private val store: TokenStore,
) {
    private val _signedIn = MutableStateFlow(store.refreshToken != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val refreshLock = Mutex()

    suspend fun login(username: String, password: String) {
        val tokens = api.login(username, password)
        store.save(tokens.access, tokens.refresh)
        _signedIn.value = true
    }

    fun logout() {
        store.clear()
        _signedIn.value = false
    }

    suspend fun <T> authorized(call: suspend (accessToken: String) -> T): T {
        val access = store.accessToken ?: throw expire()
        try {
            return call(access)
        } catch (e: ApiException) {
            if (e.status != HTTP_UNAUTHORIZED) throw e
        }
        val fresh = refreshAfterFailure(access)
        try {
            return call(fresh)
        } catch (e: ApiException) {
            if (e.status == HTTP_UNAUTHORIZED) throw expire(e)
            throw e
        }
    }

    private suspend fun refreshAfterFailure(failedAccess: String): String = refreshLock.withLock {
        // Another call may have refreshed while this one waited for the lock.
        val current = store.accessToken
        if (current != null && current != failedAccess) return current
        val refresh = store.refreshToken ?: throw expire()
        try {
            api.refresh(refresh).also { store.saveAccess(it) }
        } catch (e: ApiException) {
            if (e.status in REFRESH_REFUSED) throw expire(e)
            throw e
        }
    }

    private fun expire(cause: Throwable? = null): SessionExpiredException {
        store.clear()
        _signedIn.value = false
        return SessionExpiredException(cause)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        val REFRESH_REFUSED = setOf(400, 401, 403)
    }
}
