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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.SettingsProblem
import com.mamre.billing.domain.admin.formatWastage
import com.mamre.billing.domain.admin.settingsProblemMessage
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

// B9 Settings (Doc 1 s13 P-6, P-12; Doc 2 s4.2): owner name, business details, device code, wastage and lock time. Every save goes
// through a use case with validation and a change-log entry. The PIN screens are in ui/lock; Export is added in the next commit.

/** Which block of the screen the last save or refusal belongs to, so the message appears under the right field. */
enum class SettingsSection { OWNER, BUSINESS, DEVICE_CODE, WASTAGE, LOCK }

data class SettingsUi(
    val loading: Boolean = true,
    val settings: BusinessSettings? = null,
    val ownerName: String = "",
    val deviceCode: String = "",
    val wastageBp: Int = 0,
    val lockMinutes: Int = 5,
    /** The refusal text and the block it belongs to; null when the last save worked. */
    val error: String? = null,
    val errorSection: SettingsSection? = null,
    /** The block that was saved last, shown as "Saved." */
    val savedSection: SettingsSection? = null,
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
                val settings = api.settings()
                val owner = api.ownerName()
                val code = api.deviceCode()
                val wastage = api.wastageBp()
                val lock = api.lockMinutes()
                _ui.update { it.copy(loading = false, settings = settings, ownerName = owner, deviceCode = code, wastageBp = wastage, lockMinutes = lock) }
            }
        }
    }

    private fun save(section: SettingsSection, write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
                _ui.update { it.copy(error = null, errorSection = null, savedSection = section) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message, errorSection = section, savedSection = null) }
            }
        }
    }

    fun saveOwnerName(name: String) = save(SettingsSection.OWNER) { api.setOwnerName(name) }

    fun saveBusiness(settings: BusinessSettings) = save(SettingsSection.BUSINESS) { api.saveSettings(settings) }

    fun saveDeviceCode(code: String) = save(SettingsSection.DEVICE_CODE) { api.setDeviceCode(code) }

    fun saveWastage(basisPoints: Int) = save(SettingsSection.WASTAGE) { api.setWastageBp(basisPoints) }

    fun saveLockMinutes(minutes: Int) = save(SettingsSection.LOCK) { api.setLockMinutes(minutes) }

    fun logout() = entryGate?.close()
}

class SettingsActions(
    val saveOwnerName: (String) -> Unit,
    val saveBusiness: (BusinessSettings) -> Unit,
    val saveDeviceCode: (String) -> Unit,
    val saveWastage: (Int) -> Unit,
    val saveLockMinutes: (Int) -> Unit,
    val logout: () -> Unit,
)

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SettingsContent(
        ui = ui,
        onBack = onBack,
        actions = SettingsActions(
            saveOwnerName = viewModel::saveOwnerName,
            saveBusiness = viewModel::saveBusiness,
            saveDeviceCode = viewModel::saveDeviceCode,
            saveWastage = viewModel::saveWastage,
            saveLockMinutes = viewModel::saveLockMinutes,
            logout = viewModel::logout,
        ),
    )
}

@Composable
private fun SectionMessage(ui: SettingsUi, section: SettingsSection) {
    if (ui.errorSection == section) {
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    } else if (ui.savedSection == section) {
        Text("Saved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The problems that belong under the business name field; the others are shown under the group. */
private val NAME_PROBLEMS = setOf(SettingsProblem.NAME_REQUIRED, SettingsProblem.NAME_TOO_LONG)

@Composable
fun SettingsContent(ui: SettingsUi, onBack: () -> Unit, actions: SettingsActions) {
    var confirmLogout by remember { mutableStateOf(false) }
    var editWastage by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Settings", onBack = onBack, actions = { DataLabelChip() })
        val s = ui.settings ?: return@Column
        var owner by remember(ui.ownerName) { mutableStateOf(ui.ownerName) }
        var name by remember(s) { mutableStateOf(s.businessName) }
        var address by remember(s) { mutableStateOf(s.address) }
        var phone by remember(s) { mutableStateOf(s.phone) }
        var footer by remember(s) { mutableStateOf(s.footerText) }
        var code by remember(ui.deviceCode) { mutableStateOf(ui.deviceCode) }
        var lock by remember(ui.lockMinutes) { mutableStateOf(ui.lockMinutes.toString()) }
        var tried by remember { mutableStateOf(false) }
        val draft = BusinessSettings(name, address, phone, footer)
        val problems = validateSettings(draft)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionHeader("Owner")
            Hint("Printed after \"Salesman:\" on every bill. 2 to 40 characters.")
            LabeledTextField("Owner name", owner, { owner = it })
            SectionMessage(ui, SettingsSection.OWNER)
            PrimaryButton("Save owner name", onClick = { actions.saveOwnerName(owner) })

            SectionHeader("Business details")
            Hint("Printed on bills: name, address (one line per text line, up to 4), phone and footer.")
            LabeledTextField(
                "Business name", name, { name = it },
                errorText = if (tried) problems.firstOrNull { it in NAME_PROBLEMS }?.let(::settingsProblemMessage) else null,
            )
            LabeledTextField("Address", address, { address = it }, singleLine = false)
            LabeledTextField("Phone", phone, { phone = it })
            LabeledTextField("Receipt footer text", footer, { footer = it }, singleLine = false)
            if (tried) problems.firstOrNull { it !in NAME_PROBLEMS }?.let {
                Text(settingsProblemMessage(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            SectionMessage(ui, SettingsSection.BUSINESS)
            PrimaryButton("Save business details", onClick = {
                tried = true
                if (problems.isEmpty()) actions.saveBusiness(draft)
            })

            SectionHeader("Bill numbers")
            Hint("The device code in new bill and receipt numbers, like MAM-W1-0001. Capital letters and digits, 1 to 8. Bills already made keep their numbers.")
            LabeledTextField("Device code", code, { code = it })
            SectionMessage(ui, SettingsSection.DEVICE_CODE)
            PrimaryButton("Save device code", onClick = { actions.saveDeviceCode(code) })

            SectionHeader("Security")
            Hint("The app locks after this many minutes without a touch, and when it was in the background that long. 1 to 30.")
            LabeledTextField(
                "Lock after (minutes)", lock, { lock = it.filter(Char::isDigit).take(2) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            SectionMessage(ui, SettingsSection.LOCK)
            PrimaryButton("Save lock time", onClick = { actions.saveLockMinutes(lock.toIntOrNull() ?: 0) })

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
            SectionMessage(ui, SettingsSection.WASTAGE)

            if (ui.canLogOut) SecondaryButton("Log out", onClick = { confirmLogout = true })
        }
    }
    if (editWastage) WastageDialog(current = ui.wastageBp, onSave = actions.saveWastage, onDismiss = { editWastage = false })
    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out?",
            message = "You will need to sign in again.",
            confirmText = "Log out",
            onConfirm = {
                confirmLogout = false
                actions.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}
