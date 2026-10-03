package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.data.admin.DataSpan
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.admin.CustomerMonthSummary
import com.mamre.billing.domain.admin.CustomerProblem
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.PriceCheck
import com.mamre.billing.domain.admin.priceProblemMessage
import com.mamre.billing.domain.admin.validateCustomerForm
import com.mamre.billing.domain.admin.validateNewPrice
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.money.parseCents
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.MonthSelector
import com.mamre.billing.ui.components.OptionChips
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
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

// B3 Customers (Doc 2 s9). The month summary comes from the server; the screen only displays it.

private fun AdminCustomer.modeLabel() = if (paymentMode == PaymentMode.CREDIT) "Credit" else "Cash"

// --- list ---

data class CustomerListUi(
    val loading: Boolean = true,
    val customers: List<AdminCustomer> = emptyList(),
    val balances: Map<String, Long> = emptyMap(),
    val query: String = "",
    val types: List<AdminCustomerType> = emptyList(),
    /** Null shows every type (change set C1: Restaurant, Shop, Retail, Catering). */
    val typeId: String? = null,
)

@HiltViewModel
class CustomerListViewModel @Inject constructor(private val api: AdminApi) : ViewModel() {
    private val _ui = MutableStateFlow(CustomerListUi())
    val ui: StateFlow<CustomerListUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            api.revision.collect {
                val customers = api.customers()
                _ui.update { s -> s.copy(loading = false, types = api.customerTypes(), customers = customers, balances = customers.associate { it.id to api.customerBalance(it.id) }) }
            }
        }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    fun setType(typeId: String?) = _ui.update { it.copy(typeId = typeId) }
}

@Composable
fun CustomerListScreen(onOpen: (String) -> Unit, onAdd: () -> Unit, viewModel: CustomerListViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    CustomerListContent(ui, viewModel::setQuery, onOpen, onAdd, viewModel::setType)
}

@Composable
fun CustomerListContent(
    ui: CustomerListUi,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onType: (String?) -> Unit = {},
) {
    val q = ui.query.trim().lowercase()
    val shown = ui.customers.filter {
        (ui.typeId == null || it.typeId == ui.typeId) && (q.isEmpty() || it.name.lowercase().contains(q) || it.phone.contains(q))
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Customers", actions = { DemoChip() })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PrimaryButton("Add customer", onClick = onAdd)
                    LabeledTextField(label = "Search by name or phone", value = ui.query, onValueChange = onQuery)
                    OptionChips(
                        options = listOf<AdminCustomerType?>(null) + ui.types,
                        selected = ui.types.firstOrNull { it.id == ui.typeId },
                        label = { it?.name ?: "All" },
                        onSelect = { onType(it?.id) },
                        perRow = 2,
                    )
                }
            }
            if (shown.isEmpty() && !ui.loading) item { EmptyState("No customers match") }
            items(shown, key = { it.id }) { c ->
                AppCard(onClick = { onOpen(c.id) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(end = Spacing.sm)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${c.typeName} - ${c.modeLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!c.isActive) StatusChip("Inactive", modifier = Modifier.padding(top = Spacing.xs))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Balance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatCents(ui.balances[c.id] ?: 0L), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

// --- detail ---

data class CustomerDetailUi(
    val loading: Boolean = true,
    val customer: AdminCustomer? = null,
    val balance: Long = 0,
    val span: DataSpan? = null,
    val month: YearMonth? = null,
    val summary: CustomerMonthSummary? = null,
    val products: List<AdminProduct> = emptyList(),
    val overrides: List<OverridePrice> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[AdminRoutes.ARG_ID])
    private val _ui = MutableStateFlow(CustomerDetailUi())
    val ui: StateFlow<CustomerDetailUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val span = api.span()
            _ui.update { it.copy(span = span, month = span.last) }
            api.revision.collect { load() }
        }
    }

    private suspend fun load() {
        val month = _ui.value.month ?: return
        val customer = api.customer(id)
        _ui.update {
            it.copy(
                loading = false,
                customer = customer,
                balance = api.customerBalance(id),
                summary = api.customerSummary(id, month),
                products = api.products(),
                overrides = api.overrides(id),
            )
        }
    }

    fun selectMonth(month: YearMonth) {
        _ui.update { it.copy(month = month) }
        viewModelScope.launch { load() }
    }

    fun clearOverride(productId: String) {
        viewModelScope.launch {
            try {
                api.clearOverride(id, productId, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun CustomerDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOverride: (String, String) -> Unit,
    viewModel: CustomerDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    CustomerDetailContent(ui, onBack, onEdit, viewModel::selectMonth, onOverride, viewModel::clearOverride)
}

@Composable
fun CustomerDetailContent(
    ui: CustomerDetailUi,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onOverride: (String, String) -> Unit,
    onClear: (String) -> Unit,
) {
    var clearing by remember { mutableStateOf<AdminProduct?>(null) }
    val c = ui.customer
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = c?.name ?: "Customer", onBack = onBack, actions = { DemoChip() })
        if (c == null) {
            if (!ui.loading) EmptyState("Customer not found")
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatusChip(c.typeName)
                    StatusChip(c.modeLabel())
                    if (!c.isActive) StatusChip("Inactive", kind = ChipKind.ERROR)
                }
                DetailLine("Phone", c.phone.ifBlank { "-" })
                DetailLine("Address", c.address.ifBlank { "-" })
                DetailLine("Notes", c.notes.ifBlank { "-" })
                LabelValueRow("Balance now", Modifier.padding(top = Spacing.md)) {
                    Text(formatCents(ui.balance), style = MaterialTheme.typography.titleLarge)
                }
                SecondaryButton("Edit customer", onClick = { onEdit(c.id) }, modifier = Modifier.padding(top = Spacing.md))
            }

            SectionHeader("Month summary")
            val span = ui.span
            val month = ui.month
            if (span != null && month != null) MonthSelector(month, span.first, span.last, onMonth)
            ui.summary?.let { s ->
                AppCard {
                    LabelValueRow("Opening balance") { Text(formatCents(s.openingCents)) }
                    LabelValueRow("Invoiced") { Text(formatCents(s.invoicedCents)) }
                    LabelValueRow("Credits") { Text("-" + formatCents(s.creditsCents)) }
                    LabelValueRow("Payments") { Text("-" + formatCents(s.payments.sumOf { it.amountCents })) }
                    LabelValueRow("Closing balance", Modifier.padding(top = Spacing.sm)) {
                        Text(formatCents(s.closingCents), style = MaterialTheme.typography.titleMedium)
                    }
                }
                SectionHeader("Payments in the month")
                AppCard {
                    if (s.payments.isEmpty()) Text("No payments this month.", style = MaterialTheme.typography.bodyMedium)
                    s.payments.forEach {
                        LabelValueRow("${formatDate(it.date)} - ${it.method.label}") {
                            Text(formatCents(it.amountCents), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            SectionHeader("Override prices")
            Text(
                "An override beats the customer type price for this customer only. Workers receive it at their next sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.products.forEach { p ->
                val active = ui.overrides.filter { it.productId == p.id && it.isActive }.maxByOrNull { it.effectiveFrom }
                AppCard {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    if (active == null) {
                        Text("No override: the ${c.typeName} price applies.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LabelValueRow("From ${formatDate(active.effectiveFrom)}") {
                            Text(formatCents(active.unitPriceCents), style = MaterialTheme.typography.titleMedium)
                        }
                        if (active.note.isNotBlank()) Text(active.note, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton(if (active == null) "Set override" else "Change", onClick = { onOverride(c.id, p.id) }, modifier = Modifier.weight(1f))
                        if (active != null) SecondaryButton("Clear", onClick = { clearing = p }, modifier = Modifier.weight(1f))
                    }
                }
            }
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
    clearing?.let { p ->
        ConfirmDialog(
            title = "Clear override?",
            message = "${p.name} goes back to the ${ui.customer?.typeName} price. The old override stays in the history.",
            confirmText = "Clear override",
            onConfirm = {
                onClear(p.id)
                clearing = null
            },
            onDismiss = { clearing = null },
        )
    }
}

// --- add and edit form ---

data class CustomerFormUi(
    val loading: Boolean = true,
    val types: List<AdminCustomerType> = emptyList(),
    val existing: AdminCustomer? = null,
    val error: String? = null,
    val savedId: String? = null,
)

@HiltViewModel
class CustomerFormViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: String? = savedState[AdminRoutes.ARG_ID]
    private val _ui = MutableStateFlow(CustomerFormUi())
    val ui: StateFlow<CustomerFormUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = id?.let { api.customer(it) }
            _ui.update { it.copy(loading = false, types = api.customerTypes(), existing = existing) }
        }
    }

    fun save(form: CustomerForm) {
        viewModelScope.launch {
            val by = session.profile?.fullName ?: DEFAULT_ADMIN_NAME
            try {
                val saved = if (id == null) api.addCustomer(form, by) else api.updateCustomer(id, form, by)
                _ui.update { it.copy(error = null, savedId = saved.id) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun CustomerFormScreen(onBack: () -> Unit, onSaved: () -> Unit, viewModel: CustomerFormViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(ui.savedId) { if (ui.savedId != null) onSaved() }
    if (ui.loading) return
    CustomerFormContent(ui, onBack, viewModel::save)
}

@Composable
fun CustomerFormContent(ui: CustomerFormUi, onBack: () -> Unit, onSave: (CustomerForm) -> Unit) {
    val e = ui.existing
    var name by remember { mutableStateOf(e?.name.orEmpty()) }
    var typeId by remember { mutableStateOf(e?.typeId) }
    var phone by remember { mutableStateOf(e?.phone.orEmpty()) }
    var address by remember { mutableStateOf(e?.address.orEmpty()) }
    var mode by remember { mutableStateOf(e?.paymentMode ?: PaymentMode.CREDIT) }
    var notes by remember { mutableStateOf(e?.notes.orEmpty()) }
    var active by remember { mutableStateOf(e?.isActive ?: true) }
    var opening by remember { mutableStateOf("0.00") }
    var tried by remember { mutableStateOf(false) }

    val openingCents = parseCents(opening)
    val form = CustomerForm(name, typeId.orEmpty(), phone, address, mode, notes, active, openingCents ?: 0L)
    val problems = validateCustomerForm(form)
    val openingBad = e == null && openingCents == null

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = if (e == null) "Add customer" else "Edit customer", onBack = onBack, actions = { DemoChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            LabeledTextField(
                "Name", name, { name = it },
                errorText = if (tried && CustomerProblem.NAME_REQUIRED in problems) "Enter the customer's name" else null,
            )
            Text("Customer type", style = MaterialTheme.typography.titleSmall)
            OptionChips(ui.types, ui.types.firstOrNull { it.id == typeId }, { it.name }, { typeId = it.id })
            if (tried && CustomerProblem.TYPE_REQUIRED in problems) {
                Text("Choose a customer type", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("Payment", style = MaterialTheme.typography.titleSmall)
            OptionChips(PaymentMode.entries, mode, { if (it == PaymentMode.CREDIT) "Credit" else "Cash" }, { mode = it }, perRow = 2)
            LabeledTextField("Phone", phone, { phone = it })
            LabeledTextField("Address", address, { address = it }, singleLine = false)
            LabeledTextField("Notes", notes, { notes = it }, singleLine = false)
            if (e == null) {
                LabeledTextField(
                    "Opening balance (what they already owe)", opening, { opening = it },
                    errorText = if (tried && (openingBad || CustomerProblem.OPENING_NEGATIVE in problems)) "Enter an amount like 150.00" else null,
                )
            } else {
                Text(
                    "The balance is calculated from invoices, credits and payments. It is not edited here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Active", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = active, onCheckedChange = { active = it })
                }
            }
            Text(
                "Workers receive this at their next sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save customer", onClick = {
                tried = true
                if (problems.isEmpty() && !openingBad) onSave(form)
            })
        }
    }
}

// --- override price ---

data class OverrideUi(
    val loading: Boolean = true,
    val customer: AdminCustomer? = null,
    val product: AdminProduct? = null,
    val current: OverridePrice? = null,
    val history: List<OverridePrice> = emptyList(),
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class OverrideViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val customerId: String = checkNotNull(savedState[AdminRoutes.ARG_ID])
    private val productId: String = checkNotNull(savedState[AdminRoutes.ARG_PRODUCT])
    private val _ui = MutableStateFlow(OverrideUi())
    val ui: StateFlow<OverrideUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val all = api.overrides(customerId).filter { it.productId == productId }
            _ui.update {
                it.copy(
                    loading = false,
                    customer = api.customer(customerId),
                    product = api.products().firstOrNull { p -> p.id == productId },
                    current = all.filter { o -> o.isActive }.maxByOrNull { o -> o.effectiveFrom },
                    history = all.sortedByDescending { o -> o.effectiveFrom },
                )
            }
        }
    }

    fun save(priceCents: Long, from: LocalDate, note: String) {
        viewModelScope.launch {
            try {
                api.setOverride(customerId, productId, priceCents, from, note, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null, saved = true) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun OverrideScreen(onBack: () -> Unit, viewModel: OverrideViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    OverrideContent(ui, onBack, viewModel::save)
}

@Composable
fun OverrideContent(ui: OverrideUi, onBack: () -> Unit, onSave: (Long, LocalDate, String) -> Unit) {
    var price by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var note by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val check = validateNewPrice(price, date, ui.current?.effectiveFrom)
    val problems = (check as? PriceCheck.Invalid)?.problems.orEmpty()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Override price", onBack = onBack, actions = { DemoChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("${ui.customer?.name.orEmpty()} - ${ui.product?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium)
            ui.current?.let {
                Text(
                    "Current override: ${formatCents(it.unitPriceCents)} from ${formatDate(it.effectiveFrom)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LabeledTextField(
                "New price per packet", price, { price = it },
                errorText = if (tried) problems.filter { it.name.startsWith("PRICE") }.joinToString { priceProblemMessage(it, ui.current?.effectiveFrom) }.ifEmpty { null } else null,
                placeholder = "2.95",
            )
            DateField(
                "Effective from", date, { date = it },
                errorText = if (tried) problems.filter { it.name.startsWith("DATE") }.joinToString { priceProblemMessage(it, ui.current?.effectiveFrom) }.ifEmpty { null } else null,
            )
            LabeledTextField("Note (optional)", note, { note = it })
            Text(
                "Workers receive this at their next sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save override", onClick = {
                tried = true
                if (check is PriceCheck.Ok) onSave(check.priceCents, check.effectiveFrom, note)
            })
            if (ui.history.isNotEmpty()) {
                SectionHeader("History")
                AppCard {
                    ui.history.forEach {
                        LabelValueRow("From ${formatDate(it.effectiveFrom)}" + if (it.isActive) "" else " (cleared)") {
                            Text(formatCents(it.unitPriceCents), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
