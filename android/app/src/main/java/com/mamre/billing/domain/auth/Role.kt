package com.mamre.billing.domain.auth

/**
 * The two experiences of the app, chosen by the role the server returns from /me and never
 * by the typed username (DECISIONS 2026-10-02, amends Doc 1 s2 and Doc 2 s5, s10).
 */
enum class Role(val wire: String) {
    WORKER("worker"),
    ADMIN("admin");

    companion object {
        /** Exact wire values only. An unknown role is refused at sign-in. */
        fun parse(value: String?): Role? = entries.firstOrNull { it.wire == value }
    }
}
