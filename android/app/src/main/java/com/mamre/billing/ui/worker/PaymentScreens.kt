package com.mamre.billing.ui.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.PaymentDraft
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.PayerKind
import com.mamre.billing.domain.worker.PaymentCheck
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentProblem
import com.mamre.billing.domain.worker.PaymentRecord
import com.mamre.billing.domain.worker.checkStandalonePayment
import com.mamre.billing.domain.worker.filterCustomers
import com.mamre.billing.domain.worker.noteMissingForOther
import com.mamre.billing.domain.worker.paymentMessage
import com.mamre.billing.ui.components.AmountSize
import com.mamre.billing.ui.components.AmountText
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.OptionChips
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StickyBottomBar
import com.mamre.billing.ui.theme.MamreTheme
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

data class PaymentUi(
    val loading: Boolean = true,
    val deviceCode: String? = null,
    val query: String = "",
    val customers: List<CustomerRow> = emptyList(),
    val customer: Customer? = null,
    val balanceCents: Long = 0,
    val amountText: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val note: String = "",
    val check: PaymentCheck = PaymentCheck.Rejected(PaymentProblem.NOT_AN_AMOUNT),
) {
    val noteMissing: Boolean get() = noteMissingForOther(method, note)
    val canRecord: Boolean
        get() = customer != null && check is PaymentCheck.Ok && !noteMissing && deviceCode != null
}

private data class PaymentInput(
    val query: String = "",
    val customer: Customer? = null,
    val amountText: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val note: String = "",
)

/** W7 Record payment (Doc 1 A-16): a saved customer pays money with no new invoice. */
@HiltViewModel
class PaymentFlowViewModel @Inject constructor(
    private val catalog: WorkerCatalog,
    private val store: DemoStore,
    private val session: SessionManager,
) : ViewModel() {
    private val draftId = UUID.randomUUID().toString()
    private val deviceCode = session.profile?.deviceCode
    private val input = MutableStateFlow(PaymentInput())
    private val snapshot = MutableStateFlow<CatalogSnapshot?>(null)

    val ui: StateFlow<PaymentUi> = combine(input, snapshot, store.state) { inp, snap, demo ->
        if (snap == null) return@combine PaymentUi(deviceCode = deviceCode)
        val balance = inp.customer?.let { demo.balanceOf(it.id) } ?: 0L
        PaymentUi(
            loading = false,
            deviceCode = deviceCode,
            query = inp.query,
            // Saved customers only: a walk-in has no ledger to take a payment against (Doc 1 s4.1).
            customers = filterCustomers(snap.customers, inp.query)
                .map { CustomerRow(it, snap.typeName(it), demo.balanceOf(it.id)) },
            customer = inp.customer,
            balanceCents = balance,
            amountText = inp.amountText,
            method = inp.method,
            note = inp.note,
            check = checkStandalonePayment(balance, inp.amountText),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PaymentUi(deviceCode = deviceCode))

    init {
        viewModelScope.launch { snapshot.value = catalog.load() }
    }

    fun onQuery(text: String) = input.update { it.copy(query = text) }

    fun select(customer: Customer) = input.update {
        if (it.customer?.id == customer.id) it else it.copy(customer = customer, amountText = "", note = "")
    }

    fun onAmount(text: String) = input.update { it.copy(amountText = text) }

    fun onMethod(method: PaymentMethod) = input.update { it.copy(method = method) }

    fun onNote(text: String) = input.update { it.copy(note = text) }

    fun record(): PaymentRecord? {
        val u = ui.value
        val ok = u.check as? PaymentCheck.Ok ?: return null
        val customer = u.customer ?: return null
        val device = u.deviceCode ?: return null
        val salesman = session.profile?.fullName ?: return null
        if (!u.canRecord) return null
        return store.recordPayment(
            PaymentDraft(draftId, customer.id, customer.name, device, ok.amountCents, u.method, u.note, salesman),
        )
    }
}

@Composable
fun PaymentCustomerScreen(vm: PaymentFlowViewModel, onBack: () -> Unit, onChosen: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Record payment", subtitle = "Choose the customer", onBack = onBack)
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
fun PaymentFormScreen(vm: PaymentFlowViewModel, onBack: () -> Unit, onRecorded: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    val ok = ui.check as? PaymentCheck.Ok
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Record payment", subtitle = ui.customer?.name, onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                LabelValueRow(if (ui.balanceCents < 0) "Credit on account" else "Balance due") {
                    AmountText(kotlin.math.abs(ui.balanceCents), size = AmountSize.MEDIUM)
                }
            }
            val rejected = ui.check as? PaymentCheck.Rejected
            LabeledTextField(
                label = "Amount received",
                value = ui.amountText,
                onValueChange = vm::onAmount,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = rejected?.takeIf { ui.amountText.isNotBlank() }
                    ?.let { paymentMessage(it.problem, PayerKind.CREDIT_CUSTOMER, 0) },
            )
            SectionHeader("Payment method")
            OptionChips(PaymentMethod.entries, ui.method, { it.label }, vm::onMethod)
            LabeledTextField(
                label = if (ui.method == PaymentMethod.OTHER) "Note (required for Other)" else "Note (optional)",
                value = ui.note,
                onValueChange = vm::onNote,
                errorText = if (ui.noteMissing && ui.note.isNotEmpty()) "Add a note for Other" else null,
            )
            if (ok != null) {
                AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    if (ok.creditOnAccountCents > 0) {
                        LabelValueRow("Credit on account") {
                            AmountText(ok.creditOnAccountCents, size = AmountSize.MEDIUM, color = MamreTheme.extra.success)
                        }
                    } else {
                        LabelValueRow("Balance after") { AmountText(ok.balanceAfterCents, size = AmountSize.MEDIUM) }
                    }
                }
            }
        }
        StickyBottomBar {
            PrimaryButton("Record payment", onClick = { asking = true }, enabled = ui.canRecord)
        }
    }
    if (asking && ok != null) {
        ConfirmDialog(
            title = "Record payment?",
            message = "${formatCents(ok.amountCents)} by ${ui.method.label} from ${ui.customer?.name}. It cannot be edited afterwards.",
            confirmText = "Record",
            onConfirm = {
                asking = false
                vm.record()?.let { onRecorded(it.id) }
            },
            onDismiss = { asking = false },
        )
    }
}
