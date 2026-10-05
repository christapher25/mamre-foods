package com.mamre.billing.domain.security

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is locked (Doc 2 s6). It is locked at every process start, so a killed and restarted app always asks for the
 * PIN; "unlocked" is never saved anywhere. It locks again after the set minutes without a touch, also when the app was in the
 * background that long. All time comes from the injected clock; there is no timer that could survive the process.
 */
@Singleton
class AppLock @Inject constructor(private val clock: Clock) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var lastActiveAt = clock.millis()

    fun unlock() {
        lastActiveAt = clock.millis()
        _locked.value = false
    }

    fun lockNow() {
        _locked.value = true
    }

    /** A touch or key press. Does nothing while locked: the PIN screen opens nothing by being touched. */
    fun noteActivity() {
        if (!_locked.value) lastActiveAt = clock.millis()
    }

    /** True (and locked) when [lockMinutes] have passed since the last activity. */
    fun checkIdle(lockMinutes: Int): Boolean {
        if (_locked.value) return true
        if (clock.millis() - lastActiveAt >= lockMinutes * 60_000L) _locked.value = true
        return _locked.value
    }

    /** The app left the foreground. Nothing to record: the time since the last touch already counts while the app is away. */
    fun onBackgrounded() = Unit

    /** Back in the foreground: lock if the app was idle or away for [lockMinutes] or more. */
    fun onForegrounded(lockMinutes: Int) {
        checkIdle(lockMinutes)
    }
}
