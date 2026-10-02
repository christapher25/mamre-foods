package com.mamre.billing.data.auth

import com.mamre.billing.data.api.ApiException
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.domain.auth.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The session is over and the worker must sign in again. */
class SessionExpiredException(cause: Throwable? = null) : Exception("Session expired", cause)

/** Who is signed in, as last returned by /me. [deviceCode] is null for an admin. */
data class Profile(val fullName: String, val deviceCode: String?)

/** Signing in was refused because of the role, not the password. [message] is shown on Login. */
class RoleRejectedException(message: String) : Exception(message)

const val UNKNOWN_ROLE_MESSAGE = "This account type is not supported by this app."
const val ADMIN_UNAVAILABLE_MESSAGE = "Admin sign-in is not available on the server yet."

/**
 * Login, logout and the 401 rule (Doc 2 s5, s6.8): the access token lasts about 15 minutes
 * and the refresh token about 30 days without rotation. On a 401 the call refreshes once and
 * is retried once; if the refresh is refused, the tokens are cleared and [signedIn] turns
 * false so the UI returns to Login. A network error never ends the session (offline use
 * must survive, Doc 2 s2), and neither do 429 or 5xx answers.
 *
 * The role comes from /me at sign-in, never from the typed username. It is stored with the
 * tokens so a restart lands on the right home. A stored session with no valid role is not a
 * session. When [adminSignInAvailable] is false (a real server that cannot yet sign admins in
 * on mobile) an admin account is refused.
 */
class SessionManager(
    private val api: BackendApi,
    private val store: TokenStore,
    private val adminSignInAvailable: Boolean = true,
) {
    private val storedRole: Role? =
        if (store.refreshToken != null) Role.parse(store.role) else null

    /** Stored at sign-in, so it is there offline and after a restart. */
    val profile: Profile?
        get() = store.fullName?.let { Profile(it, store.deviceCode) }

    private val _role = MutableStateFlow(storedRole)
    val role: StateFlow<Role?> = _role.asStateFlow()

    private val _signedIn = MutableStateFlow(storedRole != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    init {
        if (storedRole == null && store.refreshToken != null) store.clear()
    }

    private val refreshLock = Mutex()

    suspend fun login(username: String, password: String) {
        val tokens = api.login(username, password)
        val me = api.me(tokens.access)
        val role = Role.parse(me.role) ?: throw RoleRejectedException(UNKNOWN_ROLE_MESSAGE)
        if (role == Role.ADMIN && !adminSignInAvailable) {
            throw RoleRejectedException(ADMIN_UNAVAILABLE_MESSAGE)
        }
        store.save(tokens.access, tokens.refresh)
        store.saveRole(role.wire)
        store.saveProfile(me.fullName, me.deviceCode)
        _role.value = role
        _signedIn.value = true
    }

    fun logout() {
        store.clear()
        _role.value = null
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
        _role.value = null
        _signedIn.value = false
        return SessionExpiredException(cause)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        val REFRESH_REFUSED = setOf(400, 401, 403)
    }
}
