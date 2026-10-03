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
import androidx.compose.runtime.LaunchedEffect
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
import com.mamre.billing.data.admin.DataSpan
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.DamageCheck
import com.mamre.billing.domain.admin.DamageProblem
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.ReturnsReport
import com.mamre.billing.domain.admin.damageProblemMessage
import com.mamre.billing.domain.admin.validateProductionDamage
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.MonthSelector
import com.mamre.billing.ui.components.OptionChips
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B7 Returns and damage. Customer returns are read-only (Doc 3 N3). Production damage is add only; it counts
// as material usage but is not a customer return (owner spec 6).

const val REPLACEMENT_COST_LABEL = "Replacement cost (already included in direct expense)"

data class ReturnsUi(
    val loading: Boolean = true,
    val span: DataSpan? = null,
    val month: YearMonth? = null,
    val report: ReturnsReport? = null,
)

@HiltViewModel
class ReturnsViewModel @Inject constructor(private val api: AdminApi) : ViewModel() {
    private val _ui = MutableStateFlow(ReturnsUi())
    val ui: StateFlow<ReturnsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val span = api.span()
            _ui.update { it.copy(span = span, month = span.last) }
            api.revision.collect { load() }
        }
    }

    private suspend fun load() {
        val month = _ui.value.month ?: return
        _ui.update { it.copy(loading = false, report = api.returnsReport(month)) }
    }

    fun selectMonth(month: YearMonth) {
        _ui.update { it.copy(month = month) }
        viewModelScope.launch { load() }
    }
}

@Composable
fun ReturnsScreen(onBack: () -> Unit, onAddDamage: () -> Unit, viewModel: ReturnsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ReturnsContent(ui, onBack, viewModel::selectMonth, onAddDamage)
}

@Composable
fun ReturnsContent(ui: ReturnsUi, onBack: () -> Unit, onMonth: (YearMonth) -> Unit, onAddDamage: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Returns and damage", onBack = onBack, actions = { DemoChip() })
        val report = ui.report
        val span = ui.span
        val month = ui.month
        if (report == null || span == null || month == null) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MonthSelector(month, span.first, span.last, onMonth, inProgress = month == span.last)
            AppCard {
                LabelValueRow("Credits given") { Text(formatCents(report.creditsTotalCents), style = MaterialTheme.typography.titleSmall) }
                LabelValueRow("Replacement packets") { Text("${report.replacementPackets}") }
                LabelValueRow("Production damage packets") { Text("${report.damagedPackets}") }
            }

            SectionHeader("Customer returns")
            Text(
                "Read only. A credit lowers the customer's balance; a replacement does not.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.returns.isEmpty()) {
                AppCard { Text("No customer returns this month.", style = MaterialTheme.typography.bodyMedium) }
            }
            report.returns.forEach { r ->
                AppCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(end = Spacing.sm)) {
                            Text(r.customerName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${formatDate(r.date)} - ${r.qtyPackets} x ${r.productName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(r.reason.label, style = MaterialTheme.typography.bodySmall)
                        }
                        StatusChip(r.resolution.label, kind = if (r.resolution == ReturnResolution.CREDIT) ChipKind.NEUTRAL else ChipKind.ACCENT)
                    }
                    if (r.resolution == ReturnResolution.CREDIT) {
                        LabelValueRow("Credit", Modifier.padding(top = Spacing.xs)) { Text("-" + formatCents(r.creditCents)) }
                    } else {
                        LabelValueRow(REPLACEMENT_COST_LABEL, Modifier.padding(top = Spacing.xs)) {
                            when (val c = r.replacementCost) {
                                is Figure.Known -> Text(formatCents(c.value))
                                is Figure.Incomplete -> StatusChip("INCOMPLETE", kind = ChipKind.WARNING)
                            }
                        }
                    }
                }
            }

            SectionHeader("Production damage")
            Text(
                "Packets spoiled in production. They use materials, are not a customer return, and are never edited.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton("Add production damage", onClick = onAddDamage)
            if (report.damage.isEmpty()) {
                AppCard { Text("No production damage this month.", style = MaterialTheme.typography.bodyMedium) }
            }
            report.damage.forEach { d ->
                AppCard {
                    LabelValueRow("${formatDate(d.date)} - ${d.productName}") {
                        Text("${d.packets} packets", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (d.note.isNotBlank()) Text(d.note, style = MaterialTheme.typography.bodySmall)
                    Text("Entered by ${d.enteredBy}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --- add production damage ---

data class AddDamageUi(
    val loading: Boolean = true,
    val products: List<AdminProduct> = emptyList(),
    val today: LocalDate? = null,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class AddDamageViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(AddDamageUi())
    val ui: StateFlow<AddDamageUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { _ui.update { it.copy(loading = false, products = api.products(), today = api.today) } }
    }

    fun save(productId: String, date: LocalDate, packets: Int, note: String) {
        viewModelScope.launch {
            try {
                api.addProductionDamage(productId, date, packets, note, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null, saved = true) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun AddDamageScreen(onBack: () -> Unit, viewModel: AddDamageViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    if (ui.loading) return
    AddDamageContent(ui, onBack, viewModel::save)
}

@Composable
fun AddDamageContent(ui: AddDamageUi, onBack: () -> Unit, onSave: (String, LocalDate, Int, String) -> Unit) {
    var productId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(ui.today) }
    var packets by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val check = validateProductionDamage(productId, date, packets)
    val problems = (check as? DamageCheck.Invalid)?.problems.orEmpty()
    fun msg(vararg of: DamageProblem) =
        if (tried) problems.filter { it in of }.joinToString { damageProblemMessage(it) }.ifEmpty { null } else null
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Add production damage", onBack = onBack, actions = { DemoChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Product", style = MaterialTheme.typography.titleSmall)
            OptionChips(ui.products, ui.products.firstOrNull { it.id == productId }, { it.name }, { productId = it.id }, perRow = 1)
            msg(DamageProblem.PRODUCT_REQUIRED)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            DateField("Date", date, { date = it }, errorText = msg(DamageProblem.DATE_REQUIRED))
            LabeledTextField(
                "Packets damaged", packets, { packets = it },
                errorText = msg(DamageProblem.PACKETS_INVALID, DamageProblem.PACKETS_NOT_POSITIVE),
                placeholder = "12",
            )
            LabeledTextField("Note (optional)", note, { note = it })
            Text(
                "Damaged packets use materials but no packing. This is not a customer return and is never edited or deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save production damage", onClick = {
                tried = true
                if (check is DamageCheck.Ok) onSave(check.productId, check.date, check.packets, note)
            })
        }
    }
}
