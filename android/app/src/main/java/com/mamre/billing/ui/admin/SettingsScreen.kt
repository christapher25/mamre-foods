package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.SettingsProblem
import com.mamre.billing.domain.admin.WorkerAccount
import com.mamre.billing.domain.admin.formatWastage
import com.mamre.billing.domain.admin.validateSettings
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B9 Settings: business details, wastage %, read-only workers, Log out. Every save goes to the change log.

data class SettingsUi(
    val loading: Boolean = true,
    val settings: BusinessSettings? = null,
    val wastageBp: Int = 0,
    val workers: List<WorkerAccount> = emptyList(),
    val error: String? = null,
    val savedNotice: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()
    private val by get() = session.profile?.fullName ?: DEFAULT_ADMIN_NAME

    init {
        viewModelScope.launch {
            api.revision.collect {
                _ui.update { it.copy(loading = false, settings = api.settings(), wastageBp = api.wastageBp(), workers = api.workers()) }
            }
        }
    }

    fun saveBusiness(settings: BusinessSettings) {
        viewModelScope.launch {
            try {
                api.saveSettings(settings, by)
                _ui.update { it.copy(error = null, savedNotice = true) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message, savedNotice = false) }
            }
        }
    }

    fun saveWastage(basisPoints: Int) {
        viewModelScope.launch {
            try {
                api.setWastageBp(basisPoints, by)
                _ui.update { it.copy(error = null) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    /** Logging out clears the back stack and the stored role (Doc 2 s6). */
    fun logout() = session.logout()
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
        AppTopBar(title = "Settings", onBack = onBack, actions = { DemoChip() })
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
                "Printed on receipts.",
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

            SectionHeader("Workers")
            AppCard {
                Text("Read only. Workers are managed on the server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.workers.forEach { w ->
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.fullName, style = MaterialTheme.typography.bodyLarge)
                            Text("${w.username} - device ${w.deviceCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusChip(if (w.isActive) "Active" else "Inactive", kind = if (w.isActive) ChipKind.SUCCESS else ChipKind.NEUTRAL)
                    }
                }
            }

            SecondaryButton("Log out", onClick = { confirmLogout = true })
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
