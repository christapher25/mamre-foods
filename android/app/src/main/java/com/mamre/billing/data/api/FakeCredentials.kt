package com.mamre.billing.data.api

import com.mamre.billing.domain.auth.Role

/**
 * TEST CREDENTIALS, never in a release build.
 *
 * Used only by FakeApi, which the release build type switches off (USE_FAKE_API=false).
 * The role of an account comes back from /me, never from the typed username.
 */
object FakeCredentials {
    data class Account(
        val username: String,
        val password: String,
        val role: Role,
        val fullName: String,
        val deviceCode: String?,
    )

    val accounts = listOf(
        Account("admin", "admin5", Role.ADMIN, "Test Admin", null),
        Account("user1", "user1", Role.WORKER, "Test Worker", "W1"),
    )

    /** Exact, case-sensitive match on both fields; anything else is null. */
    fun find(username: String, password: String): Account? =
        accounts.firstOrNull { it.username == username && it.password == password }
}
