package com.mamre.billing.print

import com.mamre.billing.domain.model.BusinessHeader
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentRecord

/**
 * Test-only business header. The production code has no address at all (change set E4): it comes from the synced
 * business settings, and the debug seed holds the demo values. The goldens use these same values.
 */
object TestHeaders {
    val full = BusinessHeader(
        name = "MAMRE FOODS",
        addressLines = listOf("1461 E Branch Hollow Dr", "Carrollton , Texas , 75007"),
        phone = "+1 (972) 927-2119",
        footer = "Thank you!",
    )
}

/** Test shorthand: the full header. The real function needs the header as an argument, so nothing can forget it. */
fun invoiceReceiptOf(
    invoice: InvoiceRecord,
    customerLedger: List<LedgerEntry>,
    showMonthSummary: Boolean,
    duplicate: Boolean = false,
): InvoiceReceipt = invoiceReceiptOf(invoice, customerLedger, showMonthSummary, TestHeaders.full, duplicate)

fun paymentReceiptOf(payment: PaymentRecord, balanceAfterCents: Long): PaymentReceipt =
    paymentReceiptOf(payment, balanceAfterCents, TestHeaders.full)
