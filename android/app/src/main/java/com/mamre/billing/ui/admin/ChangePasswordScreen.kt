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
import com.mamre.billing.domain.accounts.MIN_PASSWORD
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.CurrentUser
import com.mamre.billing.domain.security.MonotonicClock
import com.mamre.billing.domain.usecase.ChangeOwnPassword
import com.mamre.billing.domain.usecase.PasswordDelay
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.WrongCurrentPassword
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.lock.WaitCountdown
import com.mamre.billing.ui.lock.waitMessage
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Admin > Settings > Change my password (B8 item f; replaces Change PIN): the CURRENT password once, the new one twice. It uses the
// ChangeOwnPassword use case, so a wrong current password is counted by the same counter and delay as the login screen.

/** The reminder shown with the form (no recovery in version 1). */
const val CHANGE_PASSWORD_NO_RECOVERY_TEXT = "Write the new password down and keep it somewhere safe at home. It cannot be recovered."

const val WRONG_CURRENT_PASSWORD_TEXT = "Wrong current password."


data class ChangePasswordUi(
    val current: String = "",
    val newPassword: String = "",
    val confirm: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    /** Milliseconds still to wait after too many wrong passwords; 0 when none. */
    val waitMs: Long = 0,
    /** The password was changed: the screen returns to Settings. */
    val done: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !busy && !done && waitMs == 0L && current.isNotEmpty() && newPassword.length >= MIN_PASSWORD && confirm.isNotEmpty()
}

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val changePassword: ChangeOwnPassword,
    private val currentUser: CurrentUser,
    private val lock: AppLock,
    private val clock: MonotonicClock,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChangePasswordUi())
    val ui: StateFlow<ChangePasswordUi> = _ui.asStateFlow()
    private val countdown = WaitCountdown(viewModelScope, clock) { left ->
        _ui.update { it.copy(waitMs = left, message = if (left > 0) waitMessage(left) else null) }
    }

    fun onCurrent(value: String) = _ui.update { it.copy(current = value, message = null) }

    fun onNew(value: String) = _ui.update { it.copy(newPassword = value, message = null) }

    fun onConfirm(value: String) = _ui.update { it.copy(confirm = value, message = null) }

    fun submit() {
        val now = _ui.value
        if (!now.canSubmit) return
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                changePassword(currentUser.require().id, now.current, now.newPassword, now.confirm)
                lock.noteActivity() // the change is activity: the lock timer starts again
                _ui.update { ChangePasswordUi(done = true) } // no password stays in memory
            } catch (e: WrongCurrentPassword) {
                _ui.update { ChangePasswordUi(message = "$WRONG_CURRENT_PASSWORD_TEXT ${e.triesBeforeDelay} tries left before a wait.") }
            } catch (e: PasswordDelay) {
                _ui.update { ChangePasswordUi() } // the fields are cleared, as for any refused try
                countdown.start(e.remainingMs)
            } catch (e: RuleException) {
                // The current password was right; keep it so the admin only fixes the new entries.
                _ui.update { it.copy(busy = false, newPassword = "", confirm = "", message = e.message) }
            }
        }
    }
}

@Composable
fun ChangePasswordScreen(onBack: () -> Unit, viewModel: ChangePasswordViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) onBack() }
    ChangePasswordContent(ui, onBack, viewModel::onCurrent, viewModel::onNew, viewModel::onConfirm, viewModel::submit)
}

@Composable
fun ChangePasswordContent(
    ui: ChangePasswordUi,
    onBack: () -> Unit,
    onCurrent: (String) -> Unit,
    onNew: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val keys = KeyboardOptions(keyboardType = KeyboardType.Password)
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Change my password", onBack = onBack, actions = { DataLabelChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "Enter your current password once and the new password twice. A password is at least $MIN_PASSWORD characters. The new password must be different.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(CHANGE_PASSWORD_NO_RECOVERY_TEXT, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            LabeledTextField("Current password", ui.current, onCurrent, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation(), enabled = !ui.busy)
            LabeledTextField("New password", ui.newPassword, onNew, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation(), enabled = !ui.busy)
            LabeledTextField("New password again", ui.confirm, onConfirm, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation(), enabled = !ui.busy)
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Change password", onClick = onSubmit, enabled = ui.canSubmit)
        }
    }
}
