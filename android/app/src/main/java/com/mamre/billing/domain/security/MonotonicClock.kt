package com.mamre.billing.domain.security

/**
 * Time that the phone's clock settings cannot move (Doc 2 s6). [elapsedMs] counts milliseconds since the phone booted, sleep
 * included (Android's SystemClock.elapsedRealtime); [bootId] changes at every reboot. The idle lock, the background lock and the
 * wrong-PIN delay all use this, never the wall clock. Behind an interface so tests control it.
 */
interface MonotonicClock {
    fun elapsedMs(): Long

    /** Identifies the current boot; "" when the phone does not say (then only a smaller [elapsedMs] shows a reboot). */
    fun bootId(): String
}

/** One reading of both clocks. */
data class PinTime(val wallMs: Long, val monoMs: Long, val bootId: String)
