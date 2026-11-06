package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.domain.accounts.Account
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.usecase.AddSalesman
import com.mamre.billing.domain.usecase.ResetPassword
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.SetActive
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Admin > Settings > Users (B8 item f): the list of accounts, add a salesman, reset a salesman's password, switch an account off or on.
// The admin row has no Switch off (L1) and no Reset password (the admin changes their own password with Change my password).

data class UsersUi(
    val accounts: List<Account> = emptyList(),
    /** The sentence after the last action: a refusal in plain words, or "Saved."; null when nothing happened yet. */
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class UsersViewModel @Inject constructor(
    users: UsersRepository,
    private val addSalesman: AddSalesman,
    private val resetPassword: ResetPassword,
    private val setActive: SetActive,
) : ViewModel() {
    private val accounts = users.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _ui = MutableStateFlow(UsersUi())
    val ui: StateFlow<UsersUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { accounts.collect { list -> _ui.update { it.copy(accounts = list) } } }
    }

    private fun run(done: String, block: suspend () -> Unit, onOk: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                block()
                _ui.update { it.copy(message = done, isError = false) }
                onOk()
            } catch (e: RuleException) {
                _ui.update { it.copy(message = e.message, isError = true) }
            }
        }
    }

    fun add(displayName: String, username: String, password: String, confirm: String, onOk: () -> Unit) =
        run("Salesman added.", { addSalesman(displayName, username, password, confirm) }, onOk)

    fun reset(userId: String, password: String, confirm: String, onOk: () -> Unit) =
        run("Password changed.", { resetPassword(userId, password, confirm) }, onOk)

    fun switchTo(userId: String, active: Boolean) = run(if (active) "Switched on." else "Switched off.", { setActive(userId, active) })
}

@Composable
fun UsersScreen(onBack: () -> Unit, viewModel: UsersViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    UsersContent(ui, onBack, viewModel::add, viewModel::reset, viewModel::switchTo)
}

@Composable
fun UsersContent(
    ui: UsersUi,
    onBack: () -> Unit,
    onAdd: (String, String, String, String, () -> Unit) -> Unit,
    onReset: (String, String, String, () -> Unit) -> Unit,
    onSwitch: (String, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Users", onBack = onBack, actions = { DataLabelChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ui.message?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = if (ui.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            ui.accounts.forEach { account -> AccountRow(account, onReset, onSwitch) }
            AddSalesmanForm(onAdd)
        }
    }
}

@Composable
private fun AccountRow(account: Account, onReset: (String, String, String, () -> Unit) -> Unit, onSwitch: (String, Boolean) -> Unit) {
    var resetting by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val keys = KeyboardOptions(keyboardType = KeyboardType.Password)
    AppCard(Modifier.fillMaxWidth()) {
        Text(account.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            "${account.username} - ${if (account.role == Role.ADMIN) "Admin" else "Salesman"} - ${if (account.active) "Active" else "Switched off"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (account.role == Role.SALESMAN) {
            SecondaryButton(if (resetting) "Cancel" else "Reset password", onClick = { resetting = !resetting; password = ""; confirm = "" })
            SecondaryButton(if (account.active) "Switch off" else "Switch on", onClick = { onSwitch(account.id, !account.active) })
            if (resetting) {
                LabeledTextField("New password", password, { password = it }, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation())
                LabeledTextField("New password again", confirm, { confirm = it }, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation())
                PrimaryButton("Save password", onClick = {
                    onReset(account.id, password, confirm) { resetting = false; password = ""; confirm = "" }
                }, enabled = password.isNotEmpty() && confirm.isNotEmpty())
            }
        }
    }
}

@Composable
private fun AddSalesmanForm(onAdd: (String, String, String, String, () -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val keys = KeyboardOptions(keyboardType = KeyboardType.Password)
    AppCard(Modifier.fillMaxWidth()) {
        Text("Add salesman", style = MaterialTheme.typography.titleMedium)
        LabeledTextField("Name (printed on bills)", name, { name = it })
        LabeledTextField("Username", username, { username = it })
        LabeledTextField("Password", password, { password = it }, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation())
        LabeledTextField("Password again", confirm, { confirm = it }, keyboardOptions = keys, visualTransformation = PasswordVisualTransformation())
        PrimaryButton("Add salesman", onClick = {
            onAdd(name, username, password, confirm) { name = ""; username = ""; password = ""; confirm = "" }
        }, enabled = name.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && confirm.isNotEmpty())
    }
}
