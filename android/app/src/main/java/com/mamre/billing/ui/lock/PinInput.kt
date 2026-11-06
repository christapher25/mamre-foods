package com.mamre.billing.ui.lock

import com.mamre.billing.domain.security.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// What the login screen and the Change my password screen share (Doc 2 s6, B8).

/** The sentence under the field while every password is refused; the seconds round up. */
fun waitMessage(ms: Long): String = "Too many wrong passwords. Please wait ${(ms + 999) / 1000} seconds."

/**
 * Counts the wrong-password delay down once a second on the MONOTONIC clock (the phone's date and time cannot move it, and a tick
 * that comes late after sleep still shows the true time left). [onChange] gets the milliseconds left, at once and at every tick,
 * and 0 when the wait is over. Starting again replaces the countdown that was running.
 */
class WaitCountdown(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val onChange: (waitMs: Long) -> Unit,
) {
    private var job: Job? = null

    fun start(remainingMs: Long) {
        val until = clock.elapsedMs() + remainingMs
        onChange(remainingMs)
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(1_000)
                val left = until - clock.elapsedMs()
                if (left <= 0) {
                    onChange(0)
                    return@launch
                }
                onChange(left)
            }
        }
    }
}
