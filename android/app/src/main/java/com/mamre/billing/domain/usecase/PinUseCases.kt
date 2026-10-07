package com.mamre.billing.domain.usecase

import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.UnitOfWork
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.security.PinHasher
import com.mamre.billing.domain.security.PinLockState
import com.mamre.billing.domain.security.PinLockout
import com.mamre.billing.domain.security.PinResult
import java.time.Clock

// The PIN (Doc 2 s6). Version 1 has NO recovery: a forgotten PIN means clearing the app's data. Only a salted PBKDF2 hash and the
// salt are stored; neither the PIN nor its hash is logged, put in the change log, or exported.

/**
 * Whether a PIN exists. FAILS CLOSED: if the hash row OR the salt row exists, the PIN counts as set, even when a half is missing,
 * empty or malformed. Only when neither row exists is it a first run. So damaged data can never lead to the setup screen.
 */
class PinStatus(private val settings: SettingsRepository) {
    suspend fun isSet(): Boolean = settings.get(SettingKeys.PIN_HASH) != null || settings.get(SettingKeys.PIN_SALT) != null
}

private suspend fun readLock(settings: SettingsRepository) = PinLockState(
    failCount = settings.get(SettingKeys.PIN_FAIL_COUNT)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    lockedUntilMs = settings.get(SettingKeys.PIN_LOCKED_UNTIL)?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
)

private suspend fun writeLock(settings: SettingsRepository, s: PinLockState) {
    settings.put(SettingKeys.PIN_FAIL_COUNT, s.failCount.toString())
    settings.put(SettingKeys.PIN_LOCKED_UNTIL, s.lockedUntilMs.toString())
}

/** Checks a PIN, counting wrong ones and enforcing the delay. Must run inside a transaction. */
private suspend fun checkPin(settings: SettingsRepository, clock: Clock, pin: String): PinResult {
    val hash = settings.get(SettingKeys.PIN_HASH)
    val salt = settings.get(SettingKeys.PIN_SALT)
    if (hash == null && salt == null) return PinResult.NotSet
    if (!PinHasher.isWellFormed(salt, hash)) return PinResult.Damaged // nothing is counted and nothing is repaired
    hash!!; salt!!
    val now = clock.millis()
    val state = readLock(settings)
    val remaining = PinLockout.remainingMs(state, now)
    // During the delay every PIN is refused, the right one too, and a try does not count or lengthen it.
    if (remaining > 0) return PinResult.Locked(remaining)
    if (PinHasher.verify(pin, salt, hash)) {
        writeLock(settings, PinLockout.afterCorrect())
        return PinResult.Ok
    }
    val next = PinLockout.afterWrong(state, now)
    writeLock(settings, next)
    val wait = PinLockout.remainingMs(next, now)
    return if (wait > 0) PinResult.Locked(wait) else PinResult.Wrong(triesBeforeDelay = PinLockout.FREE_TRIES + 1 - next.failCount)
}

/** The PIN screen's check. One transaction: the count and the lock time are saved with the answer. */
class VerifyPin(
    private val unitOfWork: UnitOfWork,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(pin: String): PinResult = unitOfWork.run { checkPin(settings, clock, pin) }
}

private fun checkNewPin(pin: String, confirm: String) {
    if (!PinHasher.isValidPin(pin)) refuse("A PIN is 4 to 6 digits")
    if (pin != confirm) refuse("The two entries are not the same")
}

private suspend fun store(settings: SettingsRepository, pin: String) {
    val salt = PinHasher.newSalt()
    settings.put(SettingKeys.PIN_SALT, salt)
    settings.put(SettingKeys.PIN_HASH, PinHasher.hash(pin, salt))
    writeLock(settings, PinLockout.afterCorrect())
}

/** First run: the Owner chooses a PIN of 4 to 6 digits, entered twice. It can be chosen once; after that only [ChangePin] changes it. */
class SetPin(
    private val unitOfWork: UnitOfWork,
    private val settings: SettingsRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(pin: String, confirm: String) {
        unitOfWork.run {
            if (PinStatus(settings).isSet()) refuse("A PIN is already set")
            checkNewPin(pin, confirm)
            store(settings, pin)
            changeLog.add(clock.millis(), "PIN set", "not set", "set")
        }
    }
}

/** The current PIN given to [ChangePin] was wrong; it was counted. Carries only the number of free tries left, never a PIN. */
class WrongCurrentPin(val triesBeforeDelay: Int) : RuleException("The old PIN is not right")

/** The wrong-PIN delay is running (or has just started); every PIN is refused for [remainingMs] more. */
class PinDelay(val remainingMs: Long) : RuleException("Too many wrong PINs: wait ${(remainingMs + 999) / 1000} seconds")

/**
 * Change PIN (Settings): the OLD PIN is required and counts towards the same wrong-PIN delay. There is no way around it: no
 * recovery in version 1 (Doc 2 s6, Doc 1 A-26).
 */
class ChangePin(
    private val unitOfWork: UnitOfWork,
    private val settings: SettingsRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(oldPin: String, newPin: String, confirm: String) {
        // The old PIN is checked in its own transaction, so a wrong one is COUNTED even though the change is then refused.
        when (val r = unitOfWork.run { checkPin(settings, clock, oldPin) }) {
            PinResult.Ok -> Unit
            PinResult.NotSet -> refuse("No PIN is set")
            PinResult.Damaged -> refuse("The PIN data on this phone is damaged, so the PIN cannot be changed.")
            is PinResult.Wrong -> throw WrongCurrentPin(r.triesBeforeDelay)
            is PinResult.Locked -> throw PinDelay(r.remainingMs)
        }
        unitOfWork.run {
            checkNewPin(newPin, confirm)
            if (newPin == oldPin) refuse("The new PIN must be different from the current PIN.")
            store(settings, newPin)
            changeLog.add(clock.millis(), "PIN changed", "set", "set")
        }
    }
}
