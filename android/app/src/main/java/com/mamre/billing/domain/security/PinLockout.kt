package com.mamre.billing.domain.security

/** Wrong PINs in a row, and the time (epoch ms) until which every PIN is refused. Kept in the settings table (Doc 2 s6). */
data class PinLockState(val failCount: Int = 0, val lockedUntilMs: Long = 0)

/** What a PIN check answered. */
sealed interface PinResult {
    data object Ok : PinResult

    /** No PIN has been chosen yet: NEITHER the hash nor the salt exists. */
    data object NotSet : PinResult

    /** A hash or a salt exists but the pair is unusable (a half missing, empty, malformed). Fails closed: nothing opens. */
    data object Damaged : PinResult

    /** Refused; [triesBeforeDelay] more wrong PINs are free before the delay starts. */
    data class Wrong(val triesBeforeDelay: Int) : PinResult

    /** Every PIN, even the right one, is refused for [remainingMs] more. */
    data class Locked(val remainingMs: Long) : PinResult
}

/**
 * The wrong-PIN delay (Doc 2 s6, owner decision 2026-10-06): the first four wrong PINs cost nothing; the fifth in a row costs 30
 * seconds, each further wrong PIN doubles it, to a cap of 15 minutes. A correct PIN resets everything. Pure: tested with fixed times.
 */
object PinLockout {
    const val FREE_TRIES = 4
    const val BASE_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 15 * 60_000L

    fun delayAfter(failCount: Int): Long {
        if (failCount <= FREE_TRIES) return 0L
        val doublings = minOf(failCount - FREE_TRIES - 1, 20)
        return minOf(MAX_DELAY_MS, BASE_DELAY_MS shl doublings)
    }

    fun remainingMs(state: PinLockState, nowMs: Long): Long = maxOf(0L, state.lockedUntilMs - nowMs)

    fun afterWrong(state: PinLockState, nowMs: Long): PinLockState {
        val count = state.failCount + 1
        return PinLockState(count, nowMs + delayAfter(count))
    }

    fun afterCorrect(): PinLockState = PinLockState()
}
