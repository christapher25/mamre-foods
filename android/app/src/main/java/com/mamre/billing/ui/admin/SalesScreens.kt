package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.InvoiceDetail
import com.mamre.billing.domain.admin.InvoiceFilter
import com.mamre.billing.domain.admin.InvoiceStatusFilter
import com.mamre.billing.domain.admin.cleanVoidReason
import com.mamre.billing.domain.admin.filterInvoices
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.MonthSelector
import com.mamre.billing.ui.components.OptionChips
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B2 Sales (Doc 2 s9; owner brief). Read-only except "Void invoice", which needs a reason (Doc 1 s5.4).

data class SalesListUi(
    val loading: Boolean = true,
    val invoices: List<AdminInvoice> = emptyList(),
    val customers: List<AdminCustomer> = emptyList(),
    val span: DataSpan? = null,
    val filter: InvoiceFilter = InvoiceFilter(),
)

@HiltViewModel
class SalesListViewModel @Inject constructor(private val api: AdminApi) : ViewModel() {
    private val _ui = MutableStateFlow(SalesListUi())
    val ui: StateFlow<SalesListUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            api.revision.collect {
                _ui.update { s ->
                    s.copy(loading = false, invoices = api.invoices(), customers = api.customers(), span = api.span())
                }
            }
        }
    }

    fun setFilter(filter: InvoiceFilter) = _ui.update { it.copy(filter = filter) }
}

@Composable
fun SalesListScreen(onOpen: (String) -> Unit, viewModel: SalesListViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SalesListContent(ui, viewModel::setFilter, onOpen)
}

@Composable
fun SalesListContent(ui: SalesListUi, onFilter: (InvoiceFilter) -> Unit, onOpen: (String) -> Unit) {
    var pickCustomer by remember { mutableStateOf(false) }
    val shown = remember(ui.invoices, ui.filter) { filterInvoices(ui.invoices, ui.filter) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Sales", actions = { DemoChip() })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    LabeledTextField(
                        label = "Search invoice number or customer",
                        value = ui.filter.query,
                        onValueChange = { onFilter(ui.filter.copy(query = it)) },
                    )
                    val span = ui.span
                    OptionChips(
                        options = listOf(false, true),
                        selected = ui.filter.month != null,
                        label = { if (it) "One month" else "All months" },
                        onSelect = { one ->
                            onFilter(ui.filter.copy(month = if (one) span?.last else null))
                        },
                        perRow = 2,
                    )
                    val month = ui.filter.month
                    if (month != null && span != null) {
                        MonthSelector(month, span.first, span.last, { onFilter(ui.filter.copy(month = it)) })
                    }
                    SecondaryButton(
                        text = "Customer: " + (ui.customers.firstOrNull { it.id == ui.filter.customerId }?.name ?: "All customers"),
                        onClick = { pickCustomer = true },
                    )
                    OptionChips(
                        options = InvoiceStatusFilter.entries,
                        selected = ui.filter.status,
                        label = { it.label },
                        onSelect = { onFilter(ui.filter.copy(status = it)) },
                    )
                    Text(
                        "${shown.size} invoice${if (shown.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (shown.isEmpty() && !ui.loading) {
                item { EmptyState("No invoices match", message = "Change the search or the filters.") }
            }
            items(shown, key = { it.id }) { InvoiceRow(it, onOpen) }
        }
    }
    if (pickCustomer) {
        CustomerPickerDialog(
            customers = ui.customers,
            onPick = {
                onFilter(ui.filter.copy(customerId = it?.id))
                pickCustomer = false
            },
            onDismiss = { pickCustomer = false },
        )
    }
}

@Composable
private fun InvoiceRow(inv: AdminInvoice, onOpen: (String) -> Unit) {
    AppCard(onClick = { onOpen(inv.id) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = Spacing.sm)) {
                Text(inv.number, style = MaterialTheme.typography.titleMedium)
                Text(inv.customerName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${formatDate(inv.issuedAt.toLocalDate())} - ${inv.typeName} - ${inv.packets} packets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatCents(inv.totalCents), style = MaterialTheme.typography.titleMedium)
                if (inv.isVoid) StatusChip("VOID", kind = ChipKind.ERROR, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
    }
}

// --- detail ---

data class SalesDetailUi(
    val loading: Boolean = true,
    val detail: InvoiceDetail? = null,
    val error: String? = null,
)

@HiltViewModel
class SalesDetailViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[AdminRoutes.ARG_ID])
    private val _ui = MutableStateFlow(SalesDetailUi())
    val ui: StateFlow<SalesDetailUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            api.revision.collect { _ui.update { it.copy(loading = false, detail = api.invoiceDetail(id)) } }
        }
    }

    fun voidInvoice(reason: String) {
        viewModelScope.launch {
            try {
                api.voidInvoice(id, reason, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun SalesDetailScreen(onBack: () -> Unit, viewModel: SalesDetailViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SalesDetailContent(ui, onBack, viewModel::voidInvoice)
}

@Composable
fun SalesDetailContent(ui: SalesDetailUi, onBack: () -> Unit, onVoid: (String) -> Unit) {
    var askReason by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val d = ui.detail
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Invoice", subtitle = d?.invoice?.number, onBack = onBack, actions = { DemoChip() })
        if (d == null) {
            if (!ui.loading) EmptyState("Invoice not found")
            return@Column
        }
        val inv = d.invoice
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(inv.customerName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (inv.isVoid) StatusChip("VOID", kind = ChipKind.ERROR)
                }
                DetailLine("Date", "${formatDate(inv.issuedAt.toLocalDate())} ${inv.issuedAt.toLocalTime().withSecond(0).withNano(0)}")
                DetailLine("Customer type", inv.typeName)
                DetailLine("Device", inv.deviceCode)
                if (inv.isVoid) {
                    DetailLine("Voided", "${inv.voidedAt?.let { formatDate(it.toLocalDate()) } ?: ""} by ${inv.voidedBy ?: "-"}")
                    DetailLine("Reason", inv.voidReason ?: "-")
                }
            }
            SectionHeader("Items")
            AppCard {
                inv.items.forEachIndexed { i, item ->
                    if (i > 0) androidx.compose.foundation.layout.Spacer(Modifier.padding(top = Spacing.sm))
                    Text(item.productName, style = MaterialTheme.typography.titleSmall)
                    LabelValueRow("${item.qtyPackets} x ${formatCents(item.unitPriceCents)}") {
                        Text(formatCents(item.lineTotalCents), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                LabelValueRow("Total", Modifier.padding(top = Spacing.md)) {
                    Text(formatCents(inv.totalCents), style = MaterialTheme.typography.titleMedium)
                }
            }
            SectionHeader("Payments applied")
            AppCard {
                if (d.payments.isEmpty()) {
                    Text("No payment applied yet.", style = MaterialTheme.typography.bodyMedium)
                }
                d.payments.forEach {
                    LabelValueRow("${it.receiptNumber} - ${formatDate(it.date)} - ${it.method.label}") {
                        Text(formatCents(it.amountCents), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            SectionHeader("Returns and credits")
            AppCard {
                if (d.credits.isEmpty()) {
                    Text("No credits on this invoice.", style = MaterialTheme.typography.bodyMedium)
                }
                d.credits.forEach {
                    LabelValueRow("${formatDate(it.date)} - ${it.qtyPackets} x ${it.productName} (${it.reason.label})") {
                        Text("-" + formatCents(it.creditCents), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            AppCard {
                LabelValueRow("Amount due") {
                    Text(formatCents(d.amountDueCents), style = MaterialTheme.typography.titleLarge)
                }
            }
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (!inv.isVoid) {
                SecondaryButton("Void invoice", onClick = {
                    reason = ""
                    askReason = true
                })
                Text(
                    "A confirmed invoice is never edited or deleted. Voiding keeps its number and removes it from the balance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
        if (askReason) {
            ReasonDialog(
                title = "Void ${inv.number}",
                message = "Say why this invoice is void. The reason is kept in the change log.",
                reason = reason,
                onReason = { reason = it },
                confirmText = "Continue",
                onConfirm = {
                    askReason = false
                    confirm = true
                },
                onDismiss = { askReason = false },
            )
        }
        if (confirm) {
            ConfirmDialog(
                title = "Void invoice?",
                message = "${inv.number} for ${formatCents(inv.totalCents)} will be voided. This cannot be undone.\n\nReason: ${reason.trim()}",
                confirmText = "Void invoice",
                onConfirm = {
                    confirm = false
                    onVoid(reason)
                },
                onDismiss = { confirm = false },
            )
        }
    }
}

/** A dialog with one required reason field; the confirm button stays off while the reason is blank. */
@Composable
fun ReasonDialog(
    title: String,
    message: String,
    reason: String,
    onReason: (String) -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                LabeledTextField(label = "Reason (required)", value = reason, onValueChange = onReason, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = cleanVoidReason(reason) != null) {
                Text(confirmText, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.titleMedium) } },
    )
}
