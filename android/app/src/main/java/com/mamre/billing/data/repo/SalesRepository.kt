package com.mamre.billing.data.repo

import androidx.room.withTransaction
import com.mamre.billing.data.local.CustomerEntity
import com.mamre.billing.data.local.CustomerTypeEntity
import com.mamre.billing.data.local.InvoiceEntity
import com.mamre.billing.data.local.InvoiceItemEntity
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.PaymentEntity
import com.mamre.billing.data.local.ProductEntity
import com.mamre.billing.data.local.ReturnEntity
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.Stored
import com.mamre.billing.domain.worker.CreditEntry
import com.mamre.billing.domain.worker.InvoiceEntry
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.InvoiceStatus
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentEntry
import com.mamre.billing.domain.worker.PaymentRecord
import com.mamre.billing.domain.worker.ReturnRecord
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.domain.worker.SalesState
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

/** The raw rows of the sales tables, as the database holds them. */
class SalesRows(
    val invoices: List<InvoiceEntity>,
    val items: List<InvoiceItemEntity>,
    val payments: List<PaymentEntity>,
    val returns: List<ReturnEntity>,
)

/**
 * Bills, their items, payments and returns (Doc 2 s4.2). Writes are inserts only, plus a bill going active to void
 * (I-4, I-9); the use cases call these inside one transaction. Reads build the [SalesState] the Sales screens use.
 */
class SalesRepository(private val db: MamreDatabase, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    suspend fun invoice(id: String): InvoiceEntity? = db.invoiceDao().get(id)

    suspend fun insertBill(invoice: InvoiceEntity, items: List<InvoiceItemEntity>, payment: PaymentEntity?) {
        db.invoiceDao().insert(invoice)
        db.invoiceItemDao().insertAll(items)
        payment?.let { db.paymentDao().insert(it) }
    }

    /** True when the bill was active and is now void; false when it is missing or already void. */
    suspend fun markVoid(id: String, reason: String, at: Long): Boolean = db.invoiceDao().markVoid(id, reason, at) == 1

    suspend fun itemsOf(invoiceId: String): List<InvoiceItemEntity> = db.invoiceItemDao().forInvoice(invoiceId)

    /** Whether a customer pays cash or on credit: a credit customer's bill shows the month summary (Doc 1 s5.3). */
    suspend fun customerPaymentMode(customerId: String): com.mamre.billing.domain.model.PaymentMode? =
        db.customerDao().get(customerId)?.let {
            if (it.paymentMode == Stored.CREDIT) com.mamre.billing.domain.model.PaymentMode.CREDIT else com.mamre.billing.domain.model.PaymentMode.CASH
        }

    suspend fun payment(id: String): PaymentEntity? = db.paymentDao().get(id)

    suspend fun insertPayment(payment: PaymentEntity) = db.paymentDao().insert(payment)

    suspend fun returnRow(id: String): ReturnEntity? = db.returnDao().get(id)

    suspend fun insertReturn(row: ReturnEntity) = db.returnDao().insert(row)

    suspend fun state(): SalesState = build(rows(), db.customerDao().getAll())

    /**
     * One bill as the Sales screens read it, built from that bill and its customer's records only (a use case returns it
     * right after saving, so it must not read every bill the phone holds).
     */
    suspend fun invoiceRecord(id: String): InvoiceRecord? {
        val inv = db.invoiceDao().get(id) ?: return null
        return build(rowsAround(inv.customerId, inv), customerRows(inv.customerId)).invoices.firstOrNull { it.id == id }
    }

    suspend fun paymentRecord(id: String): PaymentRecord? {
        val p = db.paymentDao().get(id) ?: return null
        return build(rowsAround(p.customerId, null), customerRows(p.customerId)).payments.firstOrNull { it.id == id }
    }

    suspend fun returnRecord(id: String): ReturnRecord? {
        val r = db.returnDao().get(id) ?: return null
        return build(rowsAround(r.customerId, null), customerRows(r.customerId)).returns.firstOrNull { it.id == id }
    }

    private suspend fun customerRows(customerId: String?): List<CustomerEntity> =
        customerId?.let { db.customerDao().get(it) }?.let { listOf(it) }.orEmpty()

    /** The rows of one customer (or of one walk-in bill) that a record needs. */
    private suspend fun rowsAround(customerId: String?, walkIn: InvoiceEntity?): SalesRows {
        val invoices = if (customerId != null) db.invoiceDao().forCustomer(customerId) else listOfNotNull(walkIn)
        val payments = if (customerId != null) db.paymentDao().forCustomer(customerId) else walkIn?.let { db.paymentDao().forInvoice(it.id) }.orEmpty()
        val returns = customerId?.let { db.returnDao().forCustomer(it) }.orEmpty()
        return SalesRows(invoices, db.invoiceItemDao().forInvoices(invoices.map { it.id }), payments, returns)
    }

    /**
     * Emits a new state whenever a customer or any sales row changes. ONE watcher covers every table the state reads, and
     * each emission is built from ONE read transaction: a bill and its lines are written in one transaction, so a state
     * never shows the bill without them. (It used to combine five separately watched tables; the bill row could arrive
     * before its lines, InvoiceRecord refused the total (Doc 2 I-2) and the exception ended the flow for good.)
     * Changes that arrive while a state is being built are merged into the next one (conflate).
     */
    fun observeState(): Flow<SalesState> =
        db.invalidationTracker
            .createFlow("invoices", "invoice_items", "payments", "return_records", "customers", "customer_types", "products", "settings")
            .map { db.withTransaction { build(rows(), db.customerDao().getAll()) } }
            .conflate()

    private suspend fun rows() = SalesRows(
        db.invoiceDao().getAll(), db.invoiceItemDao().getAll(), db.paymentDao().getAll(), db.returnDao().getAll(),
    )

    private suspend fun build(rows: SalesRows, customers: List<CustomerEntity>): SalesState = buildSalesState(
        rows = rows,
        customers = customers,
        types = db.customerTypeDao().getAll(),
        products = db.productDao().getAll(),
        ownerName = db.settingDao().get(SettingKeys.OWNER_NAME).orEmpty().trim(),
        zone = zone(),
    )
}

private const val WALK_IN_TYPE_LABEL = "Retail"

/** Builds the Sales read model from raw rows. Pure, so it is tested without a database. */
fun buildSalesState(
    rows: SalesRows,
    customers: List<CustomerEntity>,
    types: List<CustomerTypeEntity>,
    products: List<ProductEntity>,
    ownerName: String,
    zone: ZoneId,
): SalesState {
    val customerById = customers.associateBy { it.id }
    val typeName = types.associate { it.id to it.name }
    val productById = products.associateBy { it.id }
    val itemsByInvoice = rows.items.groupBy { it.invoiceId }
    val paymentsByInvoice = rows.payments.filter { it.invoiceId != null }.groupBy { it.invoiceId!! }

    // Ledger entries per customer (Doc 1 s6.3): bills, every payment the customer made, and Credit returns only.
    val ledgers = mutableMapOf<String, MutableList<LedgerEntry>>()
    fun ledger(id: String) = ledgers.getOrPut(id) { mutableListOf() }
    rows.invoices.forEach { inv ->
        inv.customerId?.let { ledger(it) += InvoiceEntry(inv.issuedAt.toLocalDateTime(zone).toLocalDate(), inv.totalCents, inv.status == Stored.VOID) }
    }
    rows.payments.forEach { p ->
        p.customerId?.let { ledger(it) += PaymentEntry(p.paidAt.toLocalDateTime(zone).toLocalDate(), p.amountCents, paymentMethodOf(p.method)) }
    }
    rows.returns.filter { returnResolutionOf(it.resolution) == ReturnResolution.CREDIT }.forEach { r ->
        ledger(r.customerId) += CreditEntry(r.occurredAt.toLocalDateTime(zone).toLocalDate(), r.creditCents)
    }
    val opening = customers.associate { it.id to it.openingBalanceCents }

    val invoicesOf = rows.invoices.filter { it.customerId != null }.groupBy { it.customerId!! }
    val paymentsOf = rows.payments.filter { it.customerId != null }.groupBy { it.customerId!! }
    val creditsOf = rows.returns.filter { it.resolution == ReturnResolution.CREDIT.stored() }.groupBy { it.customerId }

    /** The balance as of this bill (Doc 1 s6.3): the records of the customer made up to this bill's time. */
    fun balanceAfter(inv: InvoiceEntity): Long {
        val id = inv.customerId ?: return 0L
        var total = opening[id] ?: 0L
        invoicesOf[id].orEmpty()
            .filter { it.status != Stored.VOID && (it.issuedAt < inv.issuedAt || (it.issuedAt == inv.issuedAt && it.number <= inv.number)) }
            .forEach { total += it.totalCents }
        paymentsOf[id].orEmpty().filter { it.paidAt <= inv.issuedAt }.forEach { total -= it.amountCents }
        creditsOf[id].orEmpty().filter { it.occurredAt <= inv.issuedAt }.forEach { total -= it.creditCents }
        return total
    }

    val invoices = rows.invoices.map { inv ->
        val customer = inv.customerId?.let { customerById[it] }
        val taken = paymentsByInvoice[inv.id].orEmpty()
        val lines = itemsByInvoice[inv.id].orEmpty().map { item ->
            val standard = productById[item.productId]?.standardPacketSize
            InvoiceLine(
                productId = item.productId,
                productName = item.productName,
                qtyPackets = item.qtyPackets,
                unitPriceCents = item.unitPriceCents,
                chapathisPerPacket = item.chapathisPerPacket,
                listPriceCents = item.listPriceCents,
                isCustomPacket = standard != null && item.chapathisPerPacket != standard,
            )
        }
        InvoiceRecord(
            id = inv.id,
            number = inv.number,
            customerId = inv.customerId,
            customerName = inv.customerName,
            customerTypeName = customer?.let { typeName[it.typeId] } ?: WALK_IN_TYPE_LABEL,
            deviceCode = deviceCodeOf(inv.number),
            issuedAt = inv.issuedAt.toLocalDateTime(zone),
            lines = lines,
            totalCents = inv.totalCents,
            paidNowCents = taken.sumOf { it.amountCents },
            method = taken.firstOrNull()?.let { paymentMethodOf(it.method) },
            balanceAfterCents = balanceAfter(inv),
            status = if (inv.status == Stored.VOID) InvoiceStatus.VOID else InvoiceStatus.ACTIVE,
            voidReason = inv.voidReason,
            salesmanName = inv.salesmanName,
            customerLocation = inv.customerLocation,
            isCorporate = inv.isCorporate,
        )
    }

    // Payments taken with no new bill. The name, location and corporate flag are the customer's now (Doc 1 s5.4).
    val payments = rows.payments.filter { it.invoiceId == null && it.customerId != null }.map { p ->
        val customer = customerById.getValue(p.customerId!!)
        PaymentRecord(
            id = p.id,
            receiptNumber = p.receiptNumber,
            customerId = customer.id,
            customerName = customer.name,
            deviceCode = deviceCodeOf(p.receiptNumber),
            paidAt = p.paidAt.toLocalDateTime(zone),
            amountCents = p.amountCents,
            method = paymentMethodOf(p.method),
            note = p.note,
            salesmanName = ownerName,
            customerLocation = customer.location,
            isCorporate = customer.isCorporate,
        )
    }

    val returns = rows.returns.map { r ->
        val customer = customerById.getValue(r.customerId)
        ReturnRecord(
            id = r.id,
            customerId = r.customerId,
            customerName = customer.name,
            productId = r.productId,
            productName = productById[r.productId]?.name.orEmpty(),
            qtyPackets = r.qtyPackets,
            reason = returnReasonOf(r.reason),
            resolution = returnResolutionOf(r.resolution),
            unitPriceCents = r.unitPriceCents,
            creditCents = r.creditCents,
            deviceCode = "",
            occurredAt = r.occurredAt.toLocalDateTime(zone),
            customerLocation = customer.location,
        )
    }

    return SalesState(
        invoices = invoices,
        payments = payments,
        returns = returns,
        ledgers = ledgers.mapValues { (_, v) -> v.toList() },
        openingBalances = opening,
    )
}
