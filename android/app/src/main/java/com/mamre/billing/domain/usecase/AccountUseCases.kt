package com.mamre.billing.domain.usecase

import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.UnitOfWork
import com.mamre.billing.data.local.UserEntity
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.data.repo.toAccount
import com.mamre.billing.domain.accounts.Account
import com.mamre.billing.domain.accounts.MAX_DISPLAY_NAME
import com.mamre.billing.domain.accounts.MAX_USERNAME
import com.mamre.billing.domain.accounts.MIN_DISPLAY_NAME
import com.mamre.billing.domain.accounts.MIN_PASSWORD
import com.mamre.billing.domain.accounts.MIN_USERNAME
import com.mamre.billing.domain.accounts.cleanDisplayName
import com.mamre.billing.domain.accounts.cleanUsername
import com.mamre.billing.domain.accounts.isValidPassword
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.security.MonotonicClock
import com.mamre.billing.domain.security.PinHasher
import com.mamre.billing.domain.security.PinLockState
import com.mamre.billing.domain.security.PinLockout
import com.mamre.billing.domain.security.PinTime
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.map

// Accounts and login (B8, replaces the PIN of Doc 1 A-25). Only a salted PBKDF2 hash and the salt are stored; a password is never
// logged, put in the change log or exported. There is no recovery in version 1: a forgotten ADMIN password means clearing the data.

/**
 * Whether any account exists. FAILS CLOSED (L10): if the users table has any row, even a damaged one, this is not a first run, so
 * the "Create the admin account" screen can never be reached while accounts exist.
 */
class AccountStatus(private val users: UsersRepository) {
    suspend fun anyExists(): Boolean = users.count() > 0

    /** Follows the table: the debug build fills it in the background after the first screen is up, so the login screen must notice. */
    fun observeAnyExists(): kotlinx.coroutines.flow.Flow<Boolean> = users.observeCount().map { it > 0 }
}

/** What a login attempt answered. */
sealed interface LoginResult {
    data class Ok(val user: Account) : LoginResult

    /** No such username. Nothing is counted (L5), and the screen does not show a tries count. */
    data object UnknownUser : LoginResult

    /** The account hash, salt or role cannot be read. Fails closed: nothing opens, nothing is counted or repaired (L10). */
    data object Damaged : LoginResult

    /** The right password for an account that is switched off. */
    data object Inactive : LoginResult

    data class Wrong(val triesBeforeDelay: Int) : LoginResult

    data class Locked(val remainingMs: Long) : LoginResult
}

private fun UserEntity.lockState() = PinLockState(
    failCount = failCount.coerceAtLeast(0),
    lockedUntilMs = lockedUntil.coerceAtLeast(0),
    delayMs = lockDelayMs.coerceAtLeast(0),
    monoAtMs = lockMonoAt.coerceAtLeast(0),
    bootId = lockBoot,
)

private fun readTime(clock: Clock, mono: MonotonicClock) = PinTime(clock.millis(), mono.elapsedMs(), mono.bootId())

private fun UserEntity.usable() = PinHasher.isWellFormed(passwordSalt, passwordHash) && toAccount() != null

private fun checkNewPassword(password: String, confirm: String) {
    if (!isValidPassword(password)) refuse("A password is at least $MIN_PASSWORD characters")
    if (password != confirm) refuse("The two entries are not the same")
}

private fun newUser(displayName: String, username: String, role: Role, password: String, now: Long): UserEntity {
    val name = cleanDisplayName(displayName) ?: refuse("The name is $MIN_DISPLAY_NAME to $MAX_DISPLAY_NAME characters")
    val login = cleanUsername(username)
        ?: refuse("The username is $MIN_USERNAME to $MAX_USERNAME letters, digits, dots or underscores")
    val salt = PinHasher.newSalt()
    return UserEntity(
        id = UUID.randomUUID().toString(), username = login, displayName = name, role = role.name,
        passwordHash = PinHasher.hash(password, salt), passwordSalt = salt, active = true, createdAt = now,
    )
}

/** First run: creates the one ADMIN. Refused as soon as any account exists. */
class CreateAdmin(
    private val unitOfWork: UnitOfWork,
    private val users: UsersRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(displayName: String, username: String, password: String, confirm: String) {
        unitOfWork.run {
            if (AccountStatus(users).anyExists()) refuse("An account already exists")
            checkNewPassword(password, confirm)
            val row = newUser(displayName, username, Role.ADMIN, password, clock.millis())
            users.insert(row)
            changeLog.add(clock.millis(), "Admin account created", "none", row.username)
        }
    }
}

/** Admin > Settings > Users: adds a SALESMAN. The username must be new, in any case (L4). */
class AddSalesman(
    private val unitOfWork: UnitOfWork,
    private val users: UsersRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(displayName: String, username: String, password: String, confirm: String) {
        unitOfWork.run {
            checkNewPassword(password, confirm)
            val row = newUser(displayName, username, Role.SALESMAN, password, clock.millis())
            if (users.byUsername(row.username) != null) refuse("That username is already used")
            users.insert(row)
            changeLog.add(clock.millis(), "Salesman account added", "none", row.username)
        }
    }
}

/** The login screen check. One transaction: the count, the lock time and the last username are saved with the answer. */
class Login(
    private val unitOfWork: UnitOfWork,
    private val users: UsersRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val mono: MonotonicClock,
) {
    suspend operator fun invoke(username: String, password: String): LoginResult = unitOfWork.run {
        val row = users.byUsername(username.trim().lowercase()) ?: return@run LoginResult.UnknownUser
        if (!row.usable()) return@run LoginResult.Damaged // nothing is counted and nothing is repaired
        val now = readTime(clock, mono)
        val state = row.lockState()
        val remaining = PinLockout.remainingMs(state, now)
        // During the delay every password is refused, the right one too, and a try does not count or lengthen it.
        if (remaining > 0) return@run LoginResult.Locked(remaining)
        if (PinHasher.verify(password, row.passwordSalt, row.passwordHash)) {
            if (!row.active) return@run LoginResult.Inactive
            users.saveLock(row.id, PinLockout.afterCorrect())
            settings.put(SettingKeys.LAST_USERNAME, row.username)
            return@run LoginResult.Ok(row.toAccount()!!)
        }
        val next = PinLockout.afterWrong(state, now)
        users.saveLock(row.id, next)
        val wait = PinLockout.remainingMs(next, now)
        if (wait > 0) LoginResult.Locked(wait) else LoginResult.Wrong(PinLockout.FREE_TRIES + 1 - next.failCount)
    }
}

/** Admin > Users > Reset password, for a SALESMAN. Also clears that account wrong-password count and delay (L7). */
class ResetPassword(
    private val unitOfWork: UnitOfWork,
    private val users: UsersRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: String, password: String, confirm: String) {
        unitOfWork.run {
            val row = users.get(userId) ?: refuse("No such account")
            if (row.role != Role.SALESMAN.name) refuse("Only a salesman password can be reset here")
            checkNewPassword(password, confirm)
            val salt = PinHasher.newSalt()
            users.setCredential(row.id, PinHasher.hash(password, salt), salt)
            users.saveLock(row.id, PinLockout.afterCorrect())
            changeLog.add(clock.millis(), "Password reset", row.username, row.username)
        }
    }
}

/** Switch an account off or on. The ADMIN can never be switched off (L1). */
class SetActive(
    private val unitOfWork: UnitOfWork,
    private val users: UsersRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: String, active: Boolean) {
        unitOfWork.run {
            val row = users.get(userId) ?: refuse("No such account")
            if (row.role == Role.ADMIN.name && !active) refuse("The admin account cannot be switched off")
            if (row.active == active) return@run
            users.setActive(row.id, active)
            changeLog.add(clock.millis(), if (active) "Account switched on" else "Account switched off", row.username, row.username)
        }
    }
}

/** The signed-in person changes their own password; the current one is required and counts towards the same delay. */
class ChangeOwnPassword(
    private val unitOfWork: UnitOfWork,
    private val users: UsersRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock,
    private val mono: MonotonicClock,
) {
    suspend operator fun invoke(userId: String, current: String, password: String, confirm: String) {
        // The current password is checked in its own transaction, so a wrong one is COUNTED even though the change is then refused.
        val problem: RuleException? = unitOfWork.run {
            val row = users.get(userId) ?: return@run RuleException("No such account")
            if (!row.usable()) return@run RuleException("The account data on this phone is damaged, so the password cannot be changed.")
            val now = readTime(clock, mono)
            val state = row.lockState()
            val remaining = PinLockout.remainingMs(state, now)
            if (remaining > 0) return@run PasswordDelay(remaining)
            if (PinHasher.verify(current, row.passwordSalt, row.passwordHash)) {
                users.saveLock(row.id, PinLockout.afterCorrect())
                return@run null
            }
            val next = PinLockout.afterWrong(state, now)
            users.saveLock(row.id, next)
            val wait = PinLockout.remainingMs(next, now)
            if (wait > 0) PasswordDelay(wait) else WrongCurrentPassword(PinLockout.FREE_TRIES + 1 - next.failCount)
        }
        if (problem != null) throw problem
        unitOfWork.run {
            checkNewPassword(password, confirm)
            if (password == current) refuse("The new password must be different from the current password.")
            val row = users.get(userId) ?: refuse("No such account")
            val salt = PinHasher.newSalt()
            users.setCredential(row.id, PinHasher.hash(password, salt), salt)
            changeLog.add(clock.millis(), "Password changed", row.username, row.username)
        }
    }
}

/** The current password given to [ChangeOwnPassword] was wrong; it was counted. Carries only the number of free tries left. */
class WrongCurrentPassword(val triesBeforeDelay: Int) : RuleException("The current password is not right")

/** The wrong-password delay is running (or has just started); every password is refused for [remainingMs] more. */
class PasswordDelay(val remainingMs: Long) : RuleException("Too many wrong passwords: wait ${(remainingMs + 999) / 1000} seconds")
