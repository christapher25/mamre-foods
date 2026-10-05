package com.mamre.billing.ui.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.usecase.BillDraft
import com.mamre.billing.domain.usecase.MakeBill
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.Product
import com.mamre.billing.domain.money.centsToPlain
import com.mamre.billing.domain.pricing.PriceResult
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.DEFAULT_PACKET_SIZE
import com.mamre.billing.domain.worker.PacketEntry
import com.mamre.billing.domain.worker.PacketKey
import com.mamre.billing.domain.worker.PayerKind
import com.mamre.billing.domain.worker.PriceEditProblem
import com.mamre.billing.domain.worker.PriceEditResult
import com.mamre.billing.domain.worker.checkPriceEdit
import com.mamre.billing.domain.worker.typeAllowsPriceEdit
import com.mamre.billing.domain.worker.PaymentCheck
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentProblem
import com.mamre.billing.domain.worker.PricedProduct
import com.mamre.billing.domain.worker.buildPacketLines
import com.mamre.billing.domain.worker.customPacketPriceCents
import com.mamre.billing.domain.worker.isValidPacketSize
import com.mamre.billing.domain.worker.checkInvoicePayment
import com.mamre.billing.domain.worker.filterCustomers
import com.mamre.billing.domain.worker.invoiceTotal
import com.mamre.billing.domain.worker.payerKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
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
data class CustomerRow(val customer: Customer, val typeName: String, val balanceCents: Long) {
    /** "Balance due" on the card: credit customers only, and never a corporate account (change set D4). */
    val showsBalance: Boolean get() = customer.paymentMode == PaymentMode.CREDIT && !customer.isCorporate
}

/** One kind of packet of a product on the builder: its size, how many are entered and its list price. */
data class PacketRow(
    val key: PacketKey,
    val qty: Int,
    val listPriceCents: Long,
    val isStandard: Boolean,
    /** What the customer pays per packet: the list price unless the worker changed it (change set C3). */
    val chargedCents: Long = listPriceCents,
) {
    val priceChanged: Boolean get() = chargedCents != listPriceCents
}

/** A product with the price this customer pays; [price] decides whether it can be sold (Doc 1 s4.2). */
data class ProductRow(val product: Product, val price: PriceResult, val packets: List<PacketRow> = emptyList()) {
    /** The price of a standard packet. */
    val unitPriceCents: Long? get() = (price as? PriceResult.Found)?.unitPriceCents
    val standardSize: Int get() = product.unitsPerPacket.takeIf { it > 0 } ?: DEFAULT_PACKET_SIZE
}

/** What happened when the worker asked for a custom packet size. */
enum class CustomPacketResult { ADDED, INVALID_SIZE, ALREADY_THERE }

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
    val customerLocation: String = "",
    /** Corporate account (change set D4): the payment step and the confirm screen show no balance. */
    val isCorporate: Boolean = false,
    val typeName: String = "",
    val products: List<ProductRow> = emptyList(),
    val lines: List<InvoiceLine> = emptyList(),
    /** The customer type's "worker can edit price" flag (a walk-in follows Retail). */
    val priceEditAllowed: Boolean = false,
    val totalCents: Long = 0,
    val payerKind: PayerKind = PayerKind.WALK_IN,
    val previousBalanceCents: Long = 0,
    val amountText: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val check: PaymentCheck = PaymentCheck.Rejected(PaymentProblem.NOT_AN_AMOUNT),
) {
    /** "Name - Location" for the top bars, the confirm screen and the bill (change set D2). */
    val customerLabel: String get() = com.mamre.billing.domain.model.customerLabel(customerName, customerLocation)
    val canContinue: Boolean get() = lines.isNotEmpty()
    val canConfirm: Boolean get() = lines.isNotEmpty() && check is PaymentCheck.Ok && deviceCode != null
}

private data class Input(
    val query: String = "",
    val chosen: Boolean = false,
    val customer: Customer? = null,
    val packets: Map<PacketKey, Int> = emptyMap(),
    /** Custom packet sizes the worker opened for a product, kept even while their quantity is zero. */
    val customSizes: Map<String, List<Int>> = emptyMap(),
    /** Worker price changes per packet kind, in cents; only used when the customer type allows them. */
    val priceEdits: Map<PacketKey, Long> = emptyMap(),
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
    sales: SalesRepository,
    private val settings: SettingsRepository,
    private val makeBill: MakeBill,
    private val clock: Clock,
) : ViewModel() {
    private val draftId = UUID.randomUUID().toString()
    private val deviceCode = MutableStateFlow<String?>(null)
    private val input = MutableStateFlow(Input())
    private val snapshot = MutableStateFlow<CatalogSnapshot?>(null)
    private val _error = MutableStateFlow<String?>(null)

    /** What the last Confirm was refused for: the use case enforces every rule, so this is the rule's own message. */
    val error: StateFlow<String?> = _error

    val ui: StateFlow<InvoiceUi> = combine(input, snapshot, sales.observeState(), deviceCode) { inp, snap, sold, device ->
        if (snap == null) return@combine InvoiceUi(loading = true, deviceCode = device)
        val today = LocalDate.now(clock)
        val customer = inp.customer
        val editAllowed = typeAllowsPriceEdit(snap.book.customerTypes, customer?.typeId, walkIn = customer == null)
        val rows = snap.products.map { p ->
            val price = snap.priceFor(customer, p, today)
            val unit = (price as? PriceResult.Found)?.unitPriceCents
            val base = ProductRow(p, price)
            val sizes = listOf(base.standardSize) + inp.customSizes[p.id].orEmpty().sorted()
            val packets = if (unit == null) emptyList() else sizes.map { s ->
                val key = PacketKey(p.id, s)
                val list = customPacketPriceCents(unit, s, base.standardSize)
                val charged = inp.priceEdits[key]?.takeIf { editAllowed } ?: list
                PacketRow(key, inp.packets[key] ?: 0, list, s == base.standardSize, charged)
            }
            base.copy(packets = packets)
        }
        val priced = rows.map { PricedProduct(it.product.id, it.product.name, it.unitPriceCents, it.standardSize) }
        val lines = buildPacketLines(priced, inp.packets.map { (k, q) -> PacketEntry(k, q, inp.priceEdits[k]) }, editAllowed)
        val total = invoiceTotal(lines)
        val kind = payerKind(customer)
        // A corporate account's balance is never carried into the Sales area (Doc 1 s4.1, Doc 2 I-16).
        val previous = customer?.let { sold.balanceShownInSales(it.id, it.isCorporate) } ?: 0L
        InvoiceUi(
            loading = false,
            deviceCode = device,
            query = inp.query,
            customers = filterCustomers(snap.customers, inp.query)
                .map { CustomerRow(it, snap.typeName(it), sold.balanceShownInSales(it.id, it.isCorporate) ?: 0L) },
            types = snap.types,
            customerChosen = inp.chosen,
            customer = customer,
            customerName = customer?.name ?: WALK_IN_NAME,
            customerLocation = customer?.location.orEmpty(),
            isCorporate = customer?.isCorporate == true,
            typeName = snap.typeName(customer),
            products = rows,
            lines = lines,
            priceEditAllowed = editAllowed,
            totalCents = total,
            payerKind = kind,
            previousBalanceCents = previous,
            amountText = inp.amountText,
            method = inp.method,
            check = checkInvoicePayment(kind, total, previous, inp.amountText),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InvoiceUi())

    init {
        viewModelScope.launch {
            deviceCode.value = settings.get(SettingKeys.DEVICE_CODE)
            snapshot.value = catalog.load()
        }
    }

    fun onQuery(text: String) = input.update { it.copy(query = text) }

    fun selectWalkIn() = choose(null)

    fun selectCustomer(customer: Customer) = choose(customer)

    /** A different customer can mean different prices, so packets and the payment start again. */
    private fun choose(customer: Customer?) = input.update {
        if (it.chosen && it.customer?.id == customer?.id) it
        else it.copy(chosen = true, customer = customer, packets = emptyMap(), customSizes = emptyMap(), priceEdits = emptyMap(), amountText = "")
    }

    fun setQuantity(key: PacketKey, qty: Int) =
        input.update { it.copy(packets = it.packets + (key to qty.coerceAtLeast(0))) }

    /** Opens a custom packet line of [chapathis] per packet for a product (1 to 200, not one it already has). */
    fun addCustomPacket(productId: String, chapathis: Int): CustomPacketResult {
        if (!isValidPacketSize(chapathis)) return CustomPacketResult.INVALID_SIZE
        val standard = snapshot.value?.products?.firstOrNull { it.id == productId }?.unitsPerPacket ?: DEFAULT_PACKET_SIZE
        val existing = input.value.customSizes[productId].orEmpty()
        if (chapathis == standard || chapathis in existing) return CustomPacketResult.ALREADY_THERE
        input.update { it.copy(customSizes = it.customSizes + (productId to (existing + chapathis))) }
        return CustomPacketResult.ADDED
    }

    /** Drops a custom packet line together with its quantity. */
    fun removeCustomPacket(key: PacketKey) = input.update {
        it.copy(
            customSizes = it.customSizes + (key.productId to it.customSizes[key.productId].orEmpty().filter { s -> s != key.chapathisPerPacket }),
            packets = it.packets - key,
            priceEdits = it.priceEdits - key,
        )
    }

    /**
     * Changes the price per packet of one packet kind, when the customer type allows it. A type that does not
     * allow it is refused here, in the use case, whatever the screen did. Typing the list price clears the change.
     */
    fun editPrice(key: PacketKey, text: String): PriceEditResult {
        val u = ui.value
        val list = u.products.firstOrNull { it.product.id == key.productId }
            ?.packets?.firstOrNull { it.key == key }?.listPriceCents
            ?: return PriceEditResult.Rejected(PriceEditProblem.NOT_AN_AMOUNT)
        val result = checkPriceEdit(u.priceEditAllowed, text, list)
        if (result is PriceEditResult.Ok) {
            input.update { it.copy(priceEdits = if (result.priceCents == list) it.priceEdits - key else it.priceEdits + (key to result.priceCents)) }
        }
        return result
    }

    /** Back to the list price. */
    fun resetPrice(key: PacketKey) = input.update { it.copy(priceEdits = it.priceEdits - key) }

    /** Called on the way to the payment step: walk-in and cash customers start at the full total. */
    fun preparePayment() = input.update {
        val u = ui.value
        it.copy(amountText = if (u.payerKind == PayerKind.CREDIT_CUSTOMER) "" else centsToPlain(u.totalCents))
    }

    fun onAmount(text: String) = input.update { it.copy(amountText = text) }

    fun fullAmount() = input.update { it.copy(amountText = centsToPlain(ui.value.totalCents)) }

    fun noAmount() = input.update { it.copy(amountText = "0") }

    fun onMethod(method: PaymentMethod) = input.update { it.copy(method = method) }

    /**
     * Saves the invoice through the MakeBill use case (number MAM-<device>-<seq>, immutable from now on) and calls
     * [onSaved] with it. The use case checks every rule again; a refusal is shown through [error] and nothing is saved.
     */
    fun confirm(onSaved: (InvoiceRecord) -> Unit) {
        val u = ui.value
        val ok = u.check as? PaymentCheck.Ok ?: return
        if (u.lines.isEmpty()) return
        viewModelScope.launch {
            try {
                val record = makeBill(
                    BillDraft(
                        id = draftId,
                        customerId = u.customer?.id,
                        lines = u.lines,
                        paidNowCents = ok.amountCents,
                        method = if (ok.amountCents > 0) u.method else null,
                    ),
                )
                _error.value = null
                onSaved(record)
            } catch (e: RuleException) {
                _error.value = e.message
            }
        }
    }
}
