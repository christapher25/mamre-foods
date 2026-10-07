package com.mamre.billing.domain.security

/**
 * Wrong PINs in a row and the running delay. The delay is stored as its length ([delayMs]) with the monotonic reading and boot
 * it was written at, plus the wall-clock deadline ([lockedUntilMs], epoch ms). Kept in the settings table (Doc 2 s6).
 */
data class PinLockState(
    val failCount: Int = 0,
    val lockedUntilMs: Long = 0,
    val delayMs: Long = 0,
    val monoAtMs: Long = 0,
    val bootId: String = "",
)

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
 *
 * The phone's clock can be moved, so the remaining time is worked out like this:
 *  - SAME BOOT: from the monotonic clock only. Moving the wall clock forward or back changes nothing.
 *  - AFTER A REBOOT (another boot id, or a monotonic reading smaller than the one written): the monotonic clock cannot say how
 *    long the phone was off, so the delay counts again from the reboot, [delayMs] minus the time since boot, and the wall clock
 *    may only make it LONGER, never longer than the delay itself: the larger of the two. So a reboot plus any clock change can
 *    never shorten the delay, and at worst the Owner waits the whole recorded delay (15 minutes at most) again.
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

    fun remainingMs(state: PinLockState, now: PinTime): Long {
        if (state.delayMs <= 0L) {
            // Only a wall deadline is stored (written by an older build): it counts, but never for more than the longest delay.
            return if (state.lockedUntilMs > 0) (state.lockedUntilMs - now.wallMs).coerceIn(0L, MAX_DELAY_MS) else 0L
        }
        val delay = minOf(state.delayMs, MAX_DELAY_MS)
        val sameBoot = state.bootId == now.bootId && now.monoMs >= state.monoAtMs
        if (sameBoot) return maxOf(0L, delay - (now.monoMs - state.monoAtMs))
        val sinceBoot = maxOf(0L, delay - now.monoMs)
        val byWall = (state.lockedUntilMs - now.wallMs).coerceIn(0L, delay)
        return maxOf(sinceBoot, byWall)
    }

    fun afterWrong(state: PinLockState, now: PinTime): PinLockState {
        val count = state.failCount + 1
        val delay = delayAfter(count)
        if (delay == 0L) return PinLockState(failCount = count)
        return PinLockState(count, now.wallMs + delay, delay, now.monoMs, now.bootId)
    }

    fun afterCorrect(): PinLockState = PinLockState()
}
