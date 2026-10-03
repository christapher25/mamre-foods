package com.mamre.billing.data.demo

import com.mamre.billing.domain.worker.CreditEntry
import com.mamre.billing.domain.worker.InvoiceEntry
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentEntry
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentRecord
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnRecord
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.domain.worker.receiptNumber
import com.mamre.billing.domain.worker.receiptSequenceOf
import com.mamre.billing.domain.worker.returnCreditCents
import com.mamre.billing.domain.worker.invoiceNumber
import com.mamre.billing.domain.worker.invoiceTotal
import com.mamre.billing.domain.worker.ledgerBalance
import com.mamre.billing.domain.worker.sequenceOf
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** DEMO DATA: everything in this store is invented and resets when the app restarts. */
const val DEMO_DATA_LABEL = "DEMO DATA"

/** What the worker confirmed at the payment step (Doc 1 s5.1). */
data class InvoiceDraft(
    val id: String,
    val customerId: String?,
    val customerName: String,
    val customerTypeName: String,
    val deviceCode: String,
    val lines: List<InvoiceLine>,
    val paidNowCents: Long,
    val method: PaymentMethod?,
    /** The customer type's "worker can edit price" flag when the invoice was built (change set C3). */
    val priceEditAllowed: Boolean = false,
)

/** Everything the worker screens read. Immutable: a new state replaces the old one. */
data class DemoState(
    val invoices: List<InvoiceRecord> = emptyList(),
    val payments: List<PaymentRecord> = emptyList(),
    val returns: List<ReturnRecord> = emptyList(),
    /** Records made on this device and not yet acknowledged by the (fake) server. */
    val pendingCount: Int = 0,
) {
    /** Dated ledger entries of one customer, built from the records (Doc 1 s6.3). */
    fun ledgerOf(customerId: String): List<LedgerEntry> = buildList {
        invoices.filter { it.customerId == customerId }.forEach {
            add(InvoiceEntry(it.issuedAt.toLocalDate(), it.totalCents, it.isVoid))
            // The payment taken with an invoice counts as a payment, unless the invoice is void.
            if (it.paidNowCents > 0) add(PaymentEntry(it.issuedAt.toLocalDate(), it.paidNowCents, it.method!!))
        }
        payments.filter { it.customerId == customerId }
            .forEach { add(PaymentEntry(it.paidAt.toLocalDate(), it.amountCents, it.method)) }
        // Only a Credit return reaches the ledger; a Replacement never does (Doc 1 s7.1).
        returns.filter { it.customerId == customerId && it.resolution == ReturnResolution.CREDIT }
            .forEach { add(CreditEntry(it.occurredAt.toLocalDate(), it.creditCents)) }
    }

    fun balanceOf(customerId: String): Long = ledgerBalance(OPENING_BALANCE_CENTS, ledgerOf(customerId))

    private companion object {
        const val OPENING_BALANCE_CENTS = 0L
    }
}

/** What the worker confirmed on the Record payment screen (Doc 1 A-16). */
data class PaymentDraft(
    val id: String,
    val customerId: String,
    val customerName: String,
    val deviceCode: String,
    val amountCents: Long,
    val method: PaymentMethod,
    val note: String,
)

/** What the worker confirmed on the Return screen (Doc 1 s7.1). */
data class ReturnDraft(
    val id: String,
    val customerId: String,
    val customerName: String,
    val productId: String,
    val productName: String,
    val qtyPackets: Int,
    val reason: ReturnReason,
    val resolution: ReturnResolution,
    val unitPriceCents: Long,
    val deviceCode: String,
)

/**
 * In-memory stand-in for Room, the outbox and the server (P2 and P3 replace it). Seeded as
 * DEMO DATA. There is no edit and no delete: records are only ever added (Doc 3 N3, Doc 2 I-9),
 * and confirming the same draft id twice stores it once (idempotent writes, Doc 2 I-11).
 */
class DemoStore(
    private val clock: Clock = Clock.systemDefaultZone(),
    seed: DemoState = DemoSeed.state(),
) {
    private val _state = MutableStateFlow(seed)
    val state: StateFlow<DemoState> = _state.asStateFlow()

    fun today(): LocalDate = LocalDate.now(clock)

    /** The next free invoice number for this device (Doc 1 s5.2): never reused, one higher each time. */
    fun nextInvoiceNumber(deviceCode: String): String {
        val last = _state.value.invoices.mapNotNull { sequenceOf(it.number, deviceCode) }.maxOrNull() ?: 0
        return invoiceNumber(deviceCode, last + 1)
    }

    /**
     * Saves a confirmed invoice with the next number and queues it as pending. Repeating a draft
     * with the same id returns the invoice already stored and changes nothing.
     */
    @Synchronized
    fun confirmInvoice(draft: InvoiceDraft): InvoiceRecord {
        _state.value.invoices.firstOrNull { it.id == draft.id }?.let { return it }
        require(draft.lines.isNotEmpty()) { "an invoice needs at least one packet" }
        // A worker may change a price only for a type whose flag allows it, even if a screen skipped its own check (C3).
        require(draft.priceEditAllowed || draft.lines.none { it.priceOverridden }) {
            "the price of this customer type cannot be changed by a worker"
        }
        val total = invoiceTotal(draft.lines)
        val previous = draft.customerId?.let { _state.value.balanceOf(it) } ?: 0L
        val record = InvoiceRecord(
            id = draft.id,
            number = nextInvoiceNumber(draft.deviceCode),
            customerId = draft.customerId,
            customerName = draft.customerName,
            customerTypeName = draft.customerTypeName,
            deviceCode = draft.deviceCode,
            issuedAt = LocalDateTime.now(clock),
            lines = draft.lines,
            totalCents = total,
            paidNowCents = draft.paidNowCents,
            method = draft.method,
            balanceAfterCents = previous + total - draft.paidNowCents,
        )
        _state.update { it.copy(invoices = it.invoices + record, pendingCount = it.pendingCount + 1) }
        return record
    }

    fun nextReceiptNumber(deviceCode: String): String {
        val last = _state.value.payments.mapNotNull { receiptSequenceOf(it.receiptNumber, deviceCode) }.maxOrNull() ?: 0
        return receiptNumber(deviceCode, last + 1)
    }

    /** Saves a payment with no invoice and queues it. The same draft id stores once (Doc 2 I-11). */
    @Synchronized
    fun recordPayment(draft: PaymentDraft): PaymentRecord {
        _state.value.payments.firstOrNull { it.id == draft.id }?.let { return it }
        val record = PaymentRecord(
            id = draft.id,
            receiptNumber = nextReceiptNumber(draft.deviceCode),
            customerId = draft.customerId,
            customerName = draft.customerName,
            deviceCode = draft.deviceCode,
            paidAt = LocalDateTime.now(clock),
            amountCents = draft.amountCents,
            method = draft.method,
            note = draft.note.trim(),
        )
        _state.update { it.copy(payments = it.payments + record, pendingCount = it.pendingCount + 1) }
        return record
    }

    /** Saves a return and queues it. A Credit lowers the balance; a Replacement does not (AT-9). */
    @Synchronized
    fun recordReturn(draft: ReturnDraft): ReturnRecord {
        _state.value.returns.firstOrNull { it.id == draft.id }?.let { return it }
        val record = ReturnRecord(
            id = draft.id,
            customerId = draft.customerId,
            customerName = draft.customerName,
            productId = draft.productId,
            productName = draft.productName,
            qtyPackets = draft.qtyPackets,
            reason = draft.reason,
            resolution = draft.resolution,
            unitPriceCents = draft.unitPriceCents,
            creditCents = returnCreditCents(draft.resolution, draft.qtyPackets, draft.unitPriceCents),
            deviceCode = draft.deviceCode,
            occurredAt = LocalDateTime.now(clock),
        )
        _state.update { it.copy(returns = it.returns + record, pendingCount = it.pendingCount + 1) }
        return record
    }

    /** "Sync now" (fake): the pretend server acknowledges everything. */
    fun syncNow() {
        _state.update { it.copy(pendingCount = 0) }
    }
}
