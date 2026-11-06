package com.mamre.billing.domain.accounts

import com.mamre.billing.domain.auth.Role

/** A signed-in or listed account, without any credential: the hash, the salt and the lock fields never leave the data layer. */
data class Account(
    val id: String,
    val username: String,
    val displayName: String,
    val role: Role,
    val active: Boolean,
    val createdAt: Long,
)
