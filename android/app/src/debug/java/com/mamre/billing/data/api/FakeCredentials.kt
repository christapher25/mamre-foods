package com.mamre.billing.data.api

import com.mamre.billing.domain.auth.Area

/**
 * TEST CREDENTIALS, never in a release build (this file is in the debug source set only).
 *
 * The debug build puts a demo login in front of the app, so the screenshots and the manual checks can open either area.
 * The area an account opens comes from this table, never from anything the user types.
 */
object FakeCredentials {
    data class Account(
        val username: String,
        val password: String,
        val area: Area,
        val fullName: String,
    )

    val accounts = listOf(
        Account("admin", "admin5", Area.ADMIN, "Test Admin"),
        Account("user1", "user1", Area.SALES, "Rajesh"),
    )

    /** Exact, case-sensitive match on both fields; anything else is null. */
    fun find(username: String, password: String): Account? =
        accounts.firstOrNull { it.username == username && it.password == password }
}
