package com.mamre.billing.ui.worker

import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.usecase.RecordReturn
import com.mamre.billing.domain.usecase.ReturnDraft
import com.mamre.billing.domain.usecase.RuleException
import java.time.Clock
import java.time.LocalDate
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.label
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnRecord
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.domain.worker.canRecordReturn
import com.mamre.billing.domain.worker.filterCustomers
import com.mamre.billing.domain.worker.returnCreditCents
import com.mamre.billing.ui.components.AmountSize
import com.mamre.billing.ui.components.AmountText
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.OptionChips
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.QuantityStepper
import com.mamre.billing.ui.components.StickyBottomBar
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReturnUi(
    val loading: Boolean = true,
    val deviceCode: String? = null,
    val query: String = "",
    val customers: List<CustomerRow> = emptyList(),
    val customer: Customer? = null,
    val products: List<ProductRow> = emptyList(),
    val productId: String? = null,
    val qty: Int = 1,
    val reason: ReturnReason? = null,
    val resolution: ReturnResolution? = null,
    val saved: ReturnRecord? = null,
) {
    val product: ProductRow? get() = products.firstOrNull { it.product.id == productId }
    val unitPriceCents: Long? get() = product?.unitPriceCents
    val creditCents: Long
        get() = unitPriceCents?.let { returnCreditCents(resolution ?: ReturnResolution.REPLACEMENT, qty, it) } ?: 0L
    val canRecord: Boolean
        get() = deviceCode != null && customer != null && canRecordReturn(product != null, qty, unitPriceCents, reason, resolution)
}

private data class ReturnInput(
    val query: String = "",
    val customer: Customer? = null,
    val productId: String? = null,
    val qty: Int = 1,
    val reason: ReturnReason? = null,
    val resolution: ReturnResolution? = null,
    val saved: ReturnRecord? = null,
)

/**
 * W8 Return (Doc 1 s7.1): customer, product, packets, reason and resolution. The price is the
 * customer's current price and cannot be changed. A Credit lowers the balance, a Replacement
 * changes nothing (AT-9).
 */
@HiltViewModel
class ReturnFlowViewModel @Inject constructor(
    private val catalog: WorkerCatalog,
    sales: SalesRepository,
    private val settings: SettingsRepository,
    private val recordReturn: RecordReturn,
    private val clock: Clock,
) : ViewModel() {
    private val draftId = UUID.randomUUID().toString()
    private val deviceCode = MutableStateFlow<String?>(null)
    private val input = MutableStateFlow(ReturnInput())
    private val snapshot = MutableStateFlow<CatalogSnapshot?>(null)
    private val _error = MutableStateFlow<String?>(null)

    /** What the last Record was refused for (the use case enforces the rules). */
    val error: StateFlow<String?> = _error

    val ui: StateFlow<ReturnUi> = combine(input, snapshot, sales.observeState(), deviceCode) { inp, snap, demo, device ->
        if (snap == null) return@combine ReturnUi(deviceCode = device)
        val today = LocalDate.now(clock)
        ReturnUi(
            loading = false,
            deviceCode = device,
            query = inp.query,
            customers = filterCustomers(snap.customers, inp.query)
                .map { CustomerRow(it, snap.typeName(it), demo.balanceShownInSales(it.id, it.isCorporate) ?: 0L) },
            customer = inp.customer,
            products = snap.products.map { ProductRow(it, snap.priceFor(inp.customer, it, today)) },
            productId = inp.productId,
            qty = inp.qty,
            reason = inp.reason,
            resolution = inp.resolution,
            saved = inp.saved,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReturnUi())

    init {
        viewModelScope.launch {
            deviceCode.value = settings.get(SettingKeys.DEVICE_CODE)
            snapshot.value = catalog.load()
        }
    }

    fun onQuery(text: String) = input.update { it.copy(query = text) }

    fun select(customer: Customer) = input.update {
        if (it.customer?.id == customer.id) it else it.copy(customer = customer, productId = null, qty = 1)
    }

    fun onProduct(id: String) = input.update { it.copy(productId = id) }

    fun onQty(qty: Int) = input.update { it.copy(qty = qty.coerceAtLeast(0)) }

    fun onReason(reason: ReturnReason) = input.update { it.copy(reason = reason) }

    fun onResolution(resolution: ReturnResolution) = input.update { it.copy(resolution = resolution) }

    /** Saves the return through the RecordReturn use case and calls [onSaved]. */
    fun record(onSaved: (ReturnRecord) -> Unit) {
        val u = ui.value
        if (!u.canRecord) return
        val customer = u.customer ?: return
        val product = u.product ?: return
        val reason = u.reason ?: return
        val resolution = u.resolution ?: return
        viewModelScope.launch {
            try {
                val record = recordReturn(
                    ReturnDraft(draftId, customer.id, product.product.id, u.qty, product.standardSize, reason, resolution),
                )
                _error.value = null
                input.update { it.copy(saved = record) }
                onSaved(record)
            } catch (e: RuleException) {
                _error.value = e.message
            }
        }
    }
}

@Composable
fun ReturnCustomerScreen(vm: ReturnFlowViewModel, onBack: () -> Unit, onChosen: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Return", subtitle = "Choose the customer", onBack = onBack)
        CustomerList(
            rows = ui.customers,
            query = ui.query,
            onQuery = vm::onQuery,
            loading = ui.loading,
            onPick = {
                vm.select(it.customer)
                onChosen()
            },
        )
    }
}

@Composable
fun ReturnFormScreen(vm: ReturnFlowViewModel, onBack: () -> Unit, onRecorded: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Return", subtitle = ui.customer?.label, onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionHeader("Product")
            val sellable = ui.products.filter { it.unitPriceCents != null }
            OptionChips(
                options = sellable,
                selected = ui.product,
                label = { "${it.product.name} - ${formatCents(it.unitPriceCents ?: 0)}" },
                onSelect = { vm.onProduct(it.product.id) },
                perRow = 1,
            )
            ui.products.filter { it.unitPriceCents == null }.forEach {
                Text(
                    "${it.product.name}: $NO_PRICE_MESSAGE",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SectionHeader("Packets")
            QuantityStepper(value = ui.qty, onValueChange = vm::onQty)
            SectionHeader("Reason")
            OptionChips(ReturnReason.entries, ui.reason, { it.label }, vm::onReason, perRow = 2)
            SectionHeader("Resolution")
            OptionChips(ReturnResolution.entries, ui.resolution, { it.label }, vm::onResolution, perRow = 2)
            AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                when (ui.resolution) {
                    ReturnResolution.CREDIT -> LabelValueRow("Credit to the customer") {
                        AmountText(ui.creditCents, size = AmountSize.MEDIUM)
                    }
                    ReturnResolution.REPLACEMENT -> Text(
                        "Free replacement packets. The balance does not change.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    null -> Text(
                        "Choose Credit or Replacement.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val refused by vm.error.collectAsStateWithLifecycle()
            refused?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        StickyBottomBar {
            PrimaryButton("Record return", onClick = { asking = true }, enabled = ui.canRecord)
        }
    }
    if (asking) {
        ConfirmDialog(
            title = "Record return?",
            message = "${ui.qty} x ${ui.product?.product?.name} from ${ui.customer?.label}. It cannot be edited afterwards.",
            confirmText = "Record",
            onConfirm = {
                asking = false
                vm.record { onRecorded() }
            },
            onDismiss = { asking = false },
        )
    }
}

/** Shown after a return is saved. */
@Composable
fun ReturnDoneScreen(vm: ReturnFlowViewModel, onDone: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // The return is saved: Back leaves the flow like Done, never back into the form.
    BackHandler(onBack = onDone)
    val r = ui.saved
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Return recorded")
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (r != null) {
                AppCard {
                    Text(r.customerDisplay, style = MaterialTheme.typography.titleMedium)
                    Text("${r.qtyPackets} x ${r.productName}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${r.reason.label} - ${r.resolution.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    if (r.resolution == ReturnResolution.CREDIT) {
                        LabelValueRow("Credit") { AmountText(r.creditCents, size = AmountSize.MEDIUM) }
                    } else {
                        Text("Replacement: the balance did not change.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Saved on this device and waiting to sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StickyBottomBar { PrimaryButton("Done", onClick = onDone) }
    }
}
