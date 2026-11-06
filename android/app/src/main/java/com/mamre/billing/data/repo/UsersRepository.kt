package com.mamre.billing.data.repo

import com.mamre.billing.data.local.UserDao
import com.mamre.billing.data.local.UserEntity
import com.mamre.billing.domain.accounts.Account
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.security.PinLockState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Accounts (B8). Accounts are never deleted. The credential and lock fields stay in [UserEntity]; callers outside the use cases see [Account]. */
class UsersRepository(private val dao: UserDao) {
    suspend fun count(): Int = dao.count()

    suspend fun byUsername(username: String): UserEntity? = dao.byUsername(username)

    suspend fun get(id: String): UserEntity? = dao.get(id)

    suspend fun insert(row: UserEntity) = dao.insert(row)

    suspend fun setActive(id: String, active: Boolean) = dao.setActive(id, active)

    suspend fun setCredential(id: String, hash: String, salt: String) = dao.setCredential(id, hash, salt)

    suspend fun saveLock(id: String, s: PinLockState) = dao.saveLock(id, s.failCount, s.lockedUntilMs, s.delayMs, s.monoAtMs, s.bootId)

    fun observeAccounts(): Flow<List<Account>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toAccount() } }

    suspend fun accounts(): List<Account> = dao.getAll().mapNotNull { it.toAccount() }
}

/** Null when the stored role is not exactly ADMIN or SALESMAN (fails closed: such a row cannot sign in). */
fun UserEntity.toAccount(): Account? = Role.fromStored(role)?.let { Account(id, username, displayName, it, active, createdAt) }
