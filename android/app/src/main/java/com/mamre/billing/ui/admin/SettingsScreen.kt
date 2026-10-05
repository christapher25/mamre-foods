package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.SettingsProblem
import com.mamre.billing.domain.admin.formatWastage
import com.mamre.billing.domain.admin.validateSettings
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.EntryGate
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B9 Settings: business details and wastage %. Every save goes to the change log. The PIN, the lock time, the owner name and
// Export come in the next step (Doc 3 L2, L3).

data class SettingsUi(
    val loading: Boolean = true,
    val settings: BusinessSettings? = null,
    val wastageBp: Int = 0,
    val error: String? = null,
    val savedNotice: Boolean = false,
    /** True when a build has put an entry gate in front of the app (the debug login): then there is a Log out. */
    val canLogOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: AdminApi,
    gate: Optional<EntryGate>,
) : ViewModel() {
    private val entryGate: EntryGate? = gate.orElse(null)
    private val _ui = MutableStateFlow(SettingsUi(canLogOut = entryGate != null))
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            api.revision.collect {
                _ui.update { it.copy(loading = false, settings = api.settings(), wastageBp = api.wastageBp()) }
            }
        }
    }

    fun saveBusiness(settings: BusinessSettings) {
        viewModelScope.launch {
            try {
                api.saveSettings(settings)
                _ui.update { it.copy(error = null, savedNotice = true) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message, savedNotice = false) }
            }
        }
    }

    fun saveWastage(basisPoints: Int) {
        viewModelScope.launch {
            try {
                api.setWastageBp(basisPoints)
                _ui.update { it.copy(error = null) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun logout() = entryGate?.close()
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SettingsContent(ui, onBack, viewModel::saveBusiness, viewModel::saveWastage, viewModel::logout)
}

@Composable
fun SettingsContent(
    ui: SettingsUi,
    onBack: () -> Unit,
    onSaveBusiness: (BusinessSettings) -> Unit,
    onSaveWastage: (Int) -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var editWastage by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Settings", onBack = onBack, actions = { DataLabelChip() })
        val s = ui.settings ?: return@Column
        var name by remember(s) { mutableStateOf(s.businessName) }
        var address by remember(s) { mutableStateOf(s.address) }
        var phone by remember(s) { mutableStateOf(s.phone) }
        var footer by remember(s) { mutableStateOf(s.footerText) }
        var tried by remember { mutableStateOf(false) }
        val draft = BusinessSettings(name, address, phone, footer)
        val problems = validateSettings(draft)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionHeader("Business details")
            Text(
                "Printed on bills: name, address (one line per text line), phone and footer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledTextField(
                "Business name", name, { name = it },
                errorText = if (tried && SettingsProblem.NAME_REQUIRED in problems) "The business needs a name" else null,
            )
            LabeledTextField("Address", address, { address = it }, singleLine = false)
            LabeledTextField("Phone", phone, { phone = it })
            LabeledTextField("Receipt footer text", footer, { footer = it }, singleLine = false)
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (ui.savedNotice) Text("Saved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton("Save business details", onClick = {
                tried = true
                if (problems.isEmpty()) onSaveBusiness(draft)
            })

            SectionHeader("Costing")
            AppCard {
                LabelValueRow("Wastage on ingredients") { Text("${formatWastage(ui.wastageBp)}%", style = MaterialTheme.typography.titleMedium) }
                Text(
                    "Default 2%, between 0% and 5%. Packing has no wastage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton("Edit wastage", onClick = { editWastage = true }, modifier = Modifier.padding(top = Spacing.sm))
            }

            if (ui.canLogOut) SecondaryButton("Log out", onClick = { confirmLogout = true })
        }
    }
    if (editWastage) WastageDialog(current = ui.wastageBp, onSave = onSaveWastage, onDismiss = { editWastage = false })
    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out?",
            message = "You will need to sign in again.",
            confirmText = "Log out",
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}
