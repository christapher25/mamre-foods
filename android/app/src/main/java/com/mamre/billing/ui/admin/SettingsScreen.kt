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
import com.mamre.billing.data.export.LastExportViewModel
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.SettingsProblem
import com.mamre.billing.domain.admin.formatWastage
import com.mamre.billing.domain.admin.settingsProblemMessage
import com.mamre.billing.domain.admin.validateSettings
import com.mamre.billing.domain.export.lastExportLabel
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B9 Settings (Doc 1 s13 P-6, P-12; Doc 2 s4.2): business details, device code, wastage and lock time. Every save goes
// through a use case with validation and a change-log entry. Export is added in the next commit.

/** Which block of the screen the last save or refusal belongs to, so the message appears under the right field. */
enum class SettingsSection { BUSINESS, DEVICE_CODE, WASTAGE, LOCK }

data class SettingsUi(
    val loading: Boolean = true,
    val settings: BusinessSettings? = null,
    val deviceCode: String = "",
    val wastageBp: Int = 0,
    val lockMinutes: Int = 5,
    /** The refusal text and the block it belongs to; null when the last save worked. */
    val error: String? = null,
    val errorSection: SettingsSection? = null,
    /** The block that was saved last, shown as "Saved." */
    val savedSection: SettingsSection? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: AdminApi,
    private val appLock: AppLock,
) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            api.revision.collect {
                val settings = api.settings()
                val code = api.deviceCode()
                val wastage = api.wastageBp()
                val lock = api.lockMinutes()
                _ui.update { it.copy(loading = false, settings = settings, deviceCode = code, wastageBp = wastage, lockMinutes = lock) }
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


    fun saveBusiness(settings: BusinessSettings) = save(SettingsSection.BUSINESS) { api.saveSettings(settings) }

    fun saveDeviceCode(code: String) = save(SettingsSection.DEVICE_CODE) { api.setDeviceCode(code) }

    fun saveWastage(basisPoints: Int) = save(SettingsSection.WASTAGE) { api.setWastageBp(basisPoints) }

    fun saveLockMinutes(minutes: Int) = save(SettingsSection.LOCK) { api.setLockMinutes(minutes) }


    /** The "Log out" button: the app locks at once and shows the login (Doc 2 s6). The lock minutes setting is not touched. */
    fun lockNow() = appLock.lockNow()
}

class SettingsActions(
    val saveBusiness: (BusinessSettings) -> Unit,
    val saveDeviceCode: (String) -> Unit,
    val saveWastage: (Int) -> Unit,
    val saveLockMinutes: (Int) -> Unit,
    val export: () -> Unit,
    val changePassword: () -> Unit,
    val users: () -> Unit,
    val lockNow: () -> Unit,
    val printerCheck: () -> Unit,
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onExport: () -> Unit,
    onChangePassword: () -> Unit,
    onUsers: () -> Unit,
    onPrinterCheck: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    lastExportViewModel: LastExportViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val lastExportAt by lastExportViewModel.lastExportAt.collectAsStateWithLifecycle()
    SettingsContent(
        ui = ui,
        onBack = onBack,
        actions = SettingsActions(
            saveBusiness = viewModel::saveBusiness,
            saveDeviceCode = viewModel::saveDeviceCode,
            saveWastage = viewModel::saveWastage,
            saveLockMinutes = viewModel::saveLockMinutes,
            export = onExport,
            changePassword = onChangePassword,
            users = onUsers,
            lockNow = viewModel::lockNow,
            printerCheck = onPrinterCheck,
        ),
        lastExport = lastExportLabel(lastExportAt, Locale.getDefault(), ZoneId.systemDefault()),
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
fun SettingsContent(
    ui: SettingsUi,
    onBack: () -> Unit,
    actions: SettingsActions,
    lastExport: String = lastExportLabel(null, Locale.getDefault(), ZoneId.systemDefault()),
) {
    var editWastage by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Settings", onBack = onBack, actions = { DataLabelChip() })
        val s = ui.settings ?: return@Column
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
            Hint("Add a salesman, reset a salesman password, or switch an account off.")
            SecondaryButton("Users", onClick = actions.users)
            Hint("Your password opens the app. Changing it needs the current password.")
            SecondaryButton("Change my password", onClick = actions.changePassword)
            Hint("Logs you out now. The next person logs in with their own username and password.")
            SecondaryButton("Log out", onClick = actions.lockNow)
            Hint("Shows what this phone offers for printing.")
            SecondaryButton("Printer check", onClick = actions.printerCheck)

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

            SectionHeader("Export")
            Hint("Make plain text files of all your data and share them, for example with your accountant.")
            Text(lastExport, style = MaterialTheme.typography.titleMedium)
            SecondaryButton("Export data", onClick = actions.export)
        }
    }
    if (editWastage) WastageDialog(current = ui.wastageBp, onSave = actions.saveWastage, onDismiss = { editWastage = false })
}
