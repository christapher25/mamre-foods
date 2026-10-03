package com.mamre.billing.ui.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.InvoiceDraft
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.Product
import com.mamre.billing.domain.money.centsToPlain
import com.mamre.billing.domain.pricing.PriceResult
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.PayerKind
import com.mamre.billing.domain.worker.PaymentCheck
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentProblem
import com.mamre.billing.domain.worker.PricedProduct
import com.mamre.billing.domain.worker.buildInvoiceLines
import com.mamre.billing.domain.worker.checkInvoicePayment
import com.mamre.billing.domain.worker.filterCustomers
import com.mamre.billing.domain.worker.invoiceTotal
import com.mamre.billing.domain.worker.payerKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A customer in a pick list, with the balance the ledger gives today (Doc 1 s6.3). */
data class CustomerRow(val customer: Customer, val typeName: String, val balanceCents: Long)

/** A product with the price this customer pays; [price] decides whether it can be sold (Doc 1 s4.2). */
data class ProductRow(val product: Product, val price: PriceResult) {
    val unitPriceCents: Long? get() = (price as? PriceResult.Found)?.unitPriceCents
}

/** Everything W2 to W5 draw. Prices come from resolvePrice and are never editable. */
data class InvoiceUi(
    val loading: Boolean = true,
    val deviceCode: String? = null,
    val query: String = "",
    val customers: List<CustomerRow> = emptyList(),
    /** The active customer types in tile order (Restaurant, Shop, Retail, Catering, then any others). */
    val types: List<CustomerType> = emptyList(),
    val customerChosen: Boolean = false,
    val customer: Customer? = null,
    val customerName: String = "",
    val typeName: String = "",
    val products: List<ProductRow> = emptyList(),
    val quantities: Map<String, Int> = emptyMap(),
    val lines: List<InvoiceLine> = emptyList(),
    val totalCents: Long = 0,
    val payerKind: PayerKind = PayerKind.WALK_IN,
    val previousBalanceCents: Long = 0,
    val amountText: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val check: PaymentCheck = PaymentCheck.Rejected(PaymentProblem.NOT_AN_AMOUNT),
) {
    val canContinue: Boolean get() = lines.isNotEmpty()
    val canConfirm: Boolean get() = lines.isNotEmpty() && check is PaymentCheck.Ok && deviceCode != null
}

private data class Input(
    val query: String = "",
    val chosen: Boolean = false,
    val customer: Customer? = null,
    val quantities: Map<String, Int> = emptyMap(),
    val amountText: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
)

/**
 * One invoice, from choosing a customer to confirming (Doc 1 s5.1). Scoped to the invoice graph,
 * so Back between the steps never loses what was entered. The draft id is made once, so confirming
 * twice stores a single invoice (Doc 2 I-11).
 */
@HiltViewModel
class InvoiceFlowViewModel @Inject constructor(
    private val catalog: WorkerCatalog,
    private val store: DemoStore,
    session: SessionManager,
) : ViewModel() {
    private val draftId = UUID.randomUUID().toString()
    private val deviceCode = session.profile?.deviceCode
    private val input = MutableStateFlow(Input())
    private val snapshot = MutableStateFlow<CatalogSnapshot?>(null)

    val ui: StateFlow<InvoiceUi> = combine(input, snapshot, store.state) { inp, snap, demo ->
        if (snap == null) return@combine InvoiceUi(loading = true, deviceCode = deviceCode)
        val today = store.today()
        val customer = inp.customer
        val rows = snap.products.map { ProductRow(it, snap.priceFor(customer, it, today)) }
        val priced = rows.map { PricedProduct(it.product.id, it.product.name, it.unitPriceCents) }
        val lines = buildInvoiceLines(priced, inp.quantities)
        val total = invoiceTotal(lines)
        val kind = payerKind(customer)
        val previous = customer?.let { demo.balanceOf(it.id) } ?: 0L
        InvoiceUi(
            loading = false,
            deviceCode = deviceCode,
            query = inp.query,
            customers = filterCustomers(snap.customers, inp.query)
                .map { CustomerRow(it, snap.typeName(it), demo.balanceOf(it.id)) },
            types = snap.types,
            customerChosen = inp.chosen,
            customer = customer,
            customerName = customer?.name ?: WALK_IN_NAME,
            typeName = snap.typeName(customer),
            products = rows,
            quantities = inp.quantities,
            lines = lines,
            totalCents = total,
            payerKind = kind,
            previousBalanceCents = previous,
            amountText = inp.amountText,
            method = inp.method,
            check = checkInvoicePayment(kind, total, previous, inp.amountText),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InvoiceUi(deviceCode = deviceCode))

    init {
        viewModelScope.launch { snapshot.value = catalog.load() }
    }

    fun onQuery(text: String) = input.update { it.copy(query = text) }

    fun selectWalkIn() = choose(null)

    fun selectCustomer(customer: Customer) = choose(customer)

    /** A different customer can mean different prices, so packets and the payment start again. */
    private fun choose(customer: Customer?) = input.update {
        if (it.chosen && it.customer?.id == customer?.id) it
        else it.copy(chosen = true, customer = customer, quantities = emptyMap(), amountText = "")
    }

    fun setQuantity(productId: String, qty: Int) =
        input.update { it.copy(quantities = it.quantities + (productId to qty.coerceAtLeast(0))) }

    /** Called on the way to the payment step: walk-in and cash customers start at the full total. */
    fun preparePayment() = input.update {
        val u = ui.value
        it.copy(amountText = if (u.payerKind == PayerKind.CREDIT_CUSTOMER) "" else centsToPlain(u.totalCents))
    }

    fun onAmount(text: String) = input.update { it.copy(amountText = text) }

    fun fullAmount() = input.update { it.copy(amountText = centsToPlain(ui.value.totalCents)) }

    fun noAmount() = input.update { it.copy(amountText = "0") }

    fun onMethod(method: PaymentMethod) = input.update { it.copy(method = method) }

    /** Saves the invoice (number MAM-<device>-<seq>, immutable from now on) and returns it. */
    fun confirm(): InvoiceRecord? {
        val u = ui.value
        val ok = u.check as? PaymentCheck.Ok ?: return null
        val device = u.deviceCode ?: return null
        if (u.lines.isEmpty()) return null
        return store.confirmInvoice(
            InvoiceDraft(
                id = draftId,
                customerId = u.customer?.id,
                customerName = u.customerName,
                customerTypeName = u.typeName,
                deviceCode = device,
                lines = u.lines,
                paidNowCents = ok.amountCents,
                method = if (ok.amountCents > 0) u.method else null,
            ),
        )
    }
}
