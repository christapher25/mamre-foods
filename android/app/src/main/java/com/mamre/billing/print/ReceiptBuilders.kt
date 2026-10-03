package com.mamre.billing.print

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

/** The business name for the receipt header; address and phone come with Doc 1 P-6. */
const val BUSINESS_NAME = "MAMRE FOODS"

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
    duplicate: Boolean = false,
    addressLine: String? = null,
): InvoiceReceipt {
    val month = if (showMonthSummary) monthSummaryAt(invoice, customerLedger) else null
    return InvoiceReceipt(
        businessName = BUSINESS_NAME,
        addressLine = addressLine,
        number = invoice.number,
        issuedAt = invoice.issuedAt,
        salesmanName = invoice.salesmanName,
        customerName = invoice.customerName,
        customerLocation = invoice.customerLocation,
        customerTypeName = invoice.customerTypeName,
        items = invoice.lines.map { ReceiptItem(
                it.productName, it.qtyPackets, it.unitPriceCents, it.lineTotalCents,
                customPacketSize = if (it.isCustomPacket) it.chapathisPerPacket else null,
            ) },
        totalCents = invoice.totalCents,
        paidNowCents = invoice.paidNowCents,
        method = invoice.method,
        balanceAfterCents = if (invoice.customerId == null) null else invoice.balanceAfterCents,
        month = month,
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
    customerTypeName: String,
    balanceAfterCents: Long,
    duplicate: Boolean = false,
    addressLine: String? = null,
): PaymentReceipt = PaymentReceipt(
    businessName = BUSINESS_NAME,
    addressLine = addressLine,
    receiptNumber = payment.receiptNumber,
    paidAt = payment.paidAt,
    salesmanName = payment.salesmanName,
    customerName = payment.customerName,
    customerLocation = payment.customerLocation,
    customerTypeName = customerTypeName,
    amountCents = payment.amountCents,
    method = payment.method,
    balanceAfterCents = balanceAfterCents,
    note = payment.note,
    duplicate = duplicate,
)
