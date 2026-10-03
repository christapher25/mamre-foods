package com.mamre.billing.print

import com.mamre.billing.domain.model.BusinessHeader
import com.mamre.billing.domain.worker.CreditEntry
import com.mamre.billing.domain.worker.InvoiceEntry
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentEntry
import com.mamre.billing.domain.worker.PaymentRecord
import com.mamre.billing.domain.worker.broughtForward
import com.mamre.billing.domain.worker.ledgerBalance
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
private const val NO_OPENING_BALANCE = 0L

/**
 * Turns a saved invoice into receipt data. For a credit customer it adds the month summary
 * (Doc 1 s5.3): brought forward, invoiced, credits, each payment of the month, and what is due.
 * Entries dated after the invoice are left out, so a reprint shows the receipt as it was.
 */
fun invoiceReceiptOf(
    invoice: InvoiceRecord,
    customerLedger: List<LedgerEntry>,
    showMonthSummary: Boolean,
    header: BusinessHeader,
    duplicate: Boolean = false,
    isCorporate: Boolean = invoice.isCorporate,
): InvoiceReceipt {
    // A corporate account never gets a balance or a month summary on paper (change set D4): not even built.
    val month = if (showMonthSummary && !isCorporate) monthSummaryAt(invoice, customerLedger) else null
    return InvoiceReceipt(
        header = header,
        number = invoice.number,
        issuedAt = invoice.issuedAt,
        salesmanName = invoice.salesmanName,
        customerName = invoice.customerName,
        customerLocation = invoice.customerLocation,
        items = invoice.lines.map {
            ReceiptItem(it.productName, it.qtyPackets, it.unitPriceCents, it.lineTotalCents, it.chapathisPerPacket)
        },
        totalCents = invoice.totalCents,
        paidNowCents = invoice.paidNowCents,
        method = invoice.method,
        balanceAfterCents = if (invoice.customerId == null || isCorporate) null else invoice.balanceAfterCents,
        month = month,
        isCorporate = isCorporate,
        duplicate = duplicate,
        isVoid = invoice.isVoid,
    )
}

private fun monthSummaryAt(invoice: InvoiceRecord, ledger: List<LedgerEntry>): MonthSummary {
    val day = invoice.issuedAt.toLocalDate()
    val month = YearMonth.from(day)
    val upToInvoice = ledger.filter { !it.date.isAfter(day) }
    val inMonth = upToInvoice.filter { YearMonth.from(it.date) == month }
    return MonthSummary(
        monthLabel = month.format(MONTH_LABEL),
        broughtForwardCents = broughtForward(NO_OPENING_BALANCE, upToInvoice, month),
        invoicedCents = inMonth.filterIsInstance<InvoiceEntry>().filter { !it.isVoid }.sumOf { it.totalCents },
        creditsCents = inMonth.filterIsInstance<CreditEntry>().sumOf { it.creditCents },
        payments = inMonth.filterIsInstance<PaymentEntry>()
            .sortedBy { it.date }
            .map { MonthPayment(it.date, it.method, it.amountCents) },
        totalDueCents = ledgerBalance(NO_OPENING_BALANCE, upToInvoice),
    )
}

fun paymentReceiptOf(
    payment: PaymentRecord,
    balanceAfterCents: Long,
    header: BusinessHeader,
    duplicate: Boolean = false,
    isCorporate: Boolean = payment.isCorporate,
): PaymentReceipt = PaymentReceipt(
    header = header,
    receiptNumber = payment.receiptNumber,
    paidAt = payment.paidAt,
    salesmanName = payment.salesmanName,
    customerName = payment.customerName,
    customerLocation = payment.customerLocation,
    amountCents = payment.amountCents,
    method = payment.method,
    balanceAfterCents = balanceAfterCents,
    note = payment.note,
    isCorporate = isCorporate,
    duplicate = duplicate,
)
