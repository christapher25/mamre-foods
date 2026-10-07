package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.PinHasher
import com.mamre.billing.domain.usecase.ChangePin
import com.mamre.billing.domain.usecase.PinDelay
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.WrongCurrentPin
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.lock.SETUP_WRITE_DOWN_TEXT
import com.mamre.billing.ui.theme.Spacing
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

// Admin > Settings > Change PIN (Doc 1 A-25, A-26; Doc 2 s6): the CURRENT PIN once, the NEW PIN twice. It uses the ChangePin use
// case, so a wrong current PIN is counted by the same counter and delay as the PIN screen. No PIN is logged or shown.

/** The same reminder as at setup. */
const val CHANGE_PIN_WRITE_DOWN_TEXT = SETUP_WRITE_DOWN_TEXT

data class ChangePinUi(
    val current: String = "",
    val newPin: String = "",
    val confirm: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    /** Milliseconds still to wait after too many wrong PINs; 0 when none. */
    val waitMs: Long = 0,
    /** The PIN was changed: the screen returns to Settings. */
    val done: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !busy && !done && waitMs == 0L && listOf(current, newPin, confirm).all { it.length >= PinHasher.MIN_DIGITS }
}

@HiltViewModel
class ChangePinViewModel @Inject constructor(
    private val changePin: ChangePin,
    private val lock: AppLock,
    private val clock: MonotonicClock,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChangePinUi())
    val ui: StateFlow<ChangePinUi> = _ui.asStateFlow()
    private var countdown: Job? = null

    private fun digits(s: String) = s.filter { it in '0'..'9' }.take(PinHasher.MAX_DIGITS)

    fun onCurrent(value: String) = _ui.update { it.copy(current = digits(value), message = null) }

    fun onNew(value: String) = _ui.update { it.copy(newPin = digits(value), message = null) }

    fun onConfirm(value: String) = _ui.update { it.copy(confirm = digits(value), message = null) }

    fun submit() {
        val now = _ui.value
        if (!now.canSubmit) return
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                changePin(now.current, now.newPin, now.confirm)
                lock.noteActivity() // the change is activity: the lock timer starts again
                _ui.update { ChangePinUi(done = true) } // no PIN stays in memory
            } catch (e: WrongCurrentPin) {
                _ui.update {
                    ChangePinUi(message = "Wrong current PIN. ${e.triesBeforeDelay} tries left before a wait.")
                }
            } catch (e: PinDelay) {
                startWait(e.remainingMs)
            } catch (e: RuleException) {
                // The current PIN was right; keep it so the Owner only fixes the new entries.
                _ui.update { it.copy(busy = false, newPin = "", confirm = "", message = e.message) }
            }
        }
    }

    private fun startWait(remainingMs: Long) {
        val until = clock.elapsedMs() + remainingMs
        _ui.update { ChangePinUi(waitMs = remainingMs, message = waitText(remainingMs)) }
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val left = until - clock.elapsedMs()
                if (left <= 0) {
                    _ui.update { it.copy(waitMs = 0, message = null) }
                    return@launch
                }
                _ui.update { it.copy(waitMs = left, message = waitText(left)) }
            }
        }
    }

    private fun waitText(ms: Long) = "Too many wrong PINs. Please wait ${(ms + 999) / 1000} seconds."
}

@Composable
fun ChangePinScreen(onBack: () -> Unit, viewModel: ChangePinViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) onBack() }
    ChangePinContent(ui, onBack, viewModel::onCurrent, viewModel::onNew, viewModel::onConfirm, viewModel::submit)
}

@Composable
fun ChangePinContent(
    ui: ChangePinUi,
    onBack: () -> Unit,
    onCurrent: (String) -> Unit,
    onNew: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val keys = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Change PIN", onBack = onBack, actions = { DataLabelChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Enter your current PIN once and the new PIN twice. A PIN is 4 to 6 digits. The new PIN must be different.", style = MaterialTheme.typography.bodyLarge)
            Text(CHANGE_PIN_WRITE_DOWN_TEXT, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            LabeledTextField("Current PIN", ui.current, onCurrent, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation(), enabled = !ui.busy)
            LabeledTextField("New PIN", ui.newPin, onNew, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation(), enabled = !ui.busy)
            LabeledTextField("New PIN again", ui.confirm, onConfirm, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation(), enabled = !ui.busy)
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Change PIN", onClick = onSubmit, enabled = ui.canSubmit)
        }
    }
}
