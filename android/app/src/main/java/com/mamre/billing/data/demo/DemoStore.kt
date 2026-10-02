package com.mamre.billing.data.demo

import com.mamre.billing.domain.worker.CreditEntry
import com.mamre.billing.domain.worker.InvoiceEntry
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentEntry
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentRecord
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
)

/** Everything the worker screens read. Immutable: a new state replaces the old one. */
data class DemoState(
    val invoices: List<InvoiceRecord> = emptyList(),
    val payments: List<PaymentRecord> = emptyList(),
    val credits: List<StoredCredit> = emptyList(),
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
        credits.filter { it.customerId == customerId }
            .forEach { add(CreditEntry(it.date, it.creditCents)) }
    }

    fun balanceOf(customerId: String): Long = ledgerBalance(OPENING_BALANCE_CENTS, ledgerOf(customerId))

    private companion object {
        const val OPENING_BALANCE_CENTS = 0L
    }
}

/** A return credit stored with the customer it reduces. Replacements never reach the ledger. */
data class StoredCredit(val customerId: String, val date: LocalDate, val creditCents: Long)

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

    /** "Sync now" (fake): the pretend server acknowledges everything. */
    fun syncNow() {
        _state.update { it.copy(pendingCount = 0) }
    }
}
