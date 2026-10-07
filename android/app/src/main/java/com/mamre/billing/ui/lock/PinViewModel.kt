package com.mamre.billing.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.PinHasher
import com.mamre.billing.domain.security.PinResult
import com.mamre.billing.domain.usecase.PinStatus
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.SetPin
import com.mamre.billing.domain.usecase.VerifyPin
import dagger.hilt.android.lifecycle.HiltViewModel
import com.mamre.billing.domain.security.MonotonicClock
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PinMode { LOADING, SETUP, ENTRY }

data class PinUi(
    val mode: PinMode = PinMode.LOADING,
    val pin: String = "",
    val confirm: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    /** Milliseconds still to wait after too many wrong PINs; 0 when none. */
    val waitMs: Long = 0,
) {
    val canSubmit: Boolean
        get() = !busy && waitMs == 0L && pin.length >= PinHasher.MIN_DIGITS && (mode == PinMode.ENTRY || confirm.length >= PinHasher.MIN_DIGITS)
}

/** Said on the PIN-choosing screen in plain words (Doc 2 s6; owner brief: no recovery in version 1). */
const val SETUP_NO_RECOVERY_TEXT = "If you forget this PIN, the only way back in is to clear the app's data, which deletes everything."
const val SETUP_WRITE_DOWN_TEXT = "Write this PIN down and keep it somewhere safe at home. It cannot be recovered."

/** Said when the stored PIN data is unusable: the app stays locked and offers no way to choose a new PIN. */
const val PIN_DAMAGED_TEXT = "The PIN data on this phone is damaged, so the app stays locked. The only way back in is to clear the app's data, which deletes everything."

/** The PIN screen (Doc 2 s6): choose a PIN on a first run, enter it on every start and after the lock time. */
@HiltViewModel
class PinViewModel @Inject constructor(
    private val status: PinStatus,
    private val setPin: SetPin,
    private val verifyPin: VerifyPin,
    private val lock: AppLock,
    private val clock: MonotonicClock,
) : ViewModel() {
    private val _ui = MutableStateFlow(PinUi())
    val ui: StateFlow<PinUi> = _ui.asStateFlow()
    private var countdown: Job? = null

    init {
        viewModelScope.launch {
            val mode = if (status.isSet()) PinMode.ENTRY else PinMode.SETUP
            _ui.update { it.copy(mode = mode) }
        }
    }

    private fun digits(s: String) = s.filter { it in '0'..'9' }.take(PinHasher.MAX_DIGITS)

    fun onPin(value: String) = _ui.update { it.copy(pin = digits(value), message = null) }

    fun onConfirm(value: String) = _ui.update { it.copy(confirm = digits(value), message = null) }

    fun submit() {
        val now = _ui.value
        if (now.busy || now.mode == PinMode.LOADING) return
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (now.mode == PinMode.SETUP) {
                    setPin(now.pin, now.confirm)
                    finish()
                } else {
                    when (val r = verifyPin(now.pin)) {
                        PinResult.Ok -> finish()
                        PinResult.NotSet -> _ui.update { it.copy(busy = false, pin = "", mode = PinMode.SETUP) }
                        PinResult.Damaged -> _ui.update { it.copy(busy = false, pin = "", message = PIN_DAMAGED_TEXT) }
                        is PinResult.Wrong -> _ui.update {
                            it.copy(busy = false, pin = "", message = "Wrong PIN. ${r.triesBeforeDelay} tries left before a wait.")
                        }
                        is PinResult.Locked -> startWait(r.remainingMs)
                    }
                }
            } catch (e: RuleException) {
                _ui.update { it.copy(busy = false, message = e.message) }
            }
        }
    }

    private fun finish() {
        _ui.update { PinUi(mode = PinMode.ENTRY) } // the PIN never stays in memory longer than the screen needs it
        lock.unlock()
    }

    private fun startWait(remainingMs: Long) {
        val until = clock.elapsedMs() + remainingMs
        _ui.update { it.copy(busy = false, pin = "", waitMs = remainingMs, message = "Too many wrong PINs. Please wait ${seconds(remainingMs)} seconds.") }
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val left = until - clock.elapsedMs()
                if (left <= 0) {
                    _ui.update { it.copy(waitMs = 0, message = null) }
                    return@launch
                }
                _ui.update { it.copy(waitMs = left, message = "Too many wrong PINs. Please wait ${seconds(left)} seconds.") }
            }
        }
    }

    private fun seconds(ms: Long) = (ms + 999) / 1000
}
