package com.mamre.billing.print

import com.mamre.billing.domain.worker.CreditEntry
import com.mamre.billing.domain.worker.InvoiceEntry
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentEntry
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentRecord
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding 3: the receipt BUILDERS must not build a balance or a month summary at all for a corporate customer.
 * These tests look at the data the builders produce, not at the layout text, so the guard cannot hide behind the
 * layout's own check.
 */
class ReceiptBuilderCorporateTest {
    private val day = LocalDate.of(2026, 10, 3)
    private val at = LocalDateTime.of(2026, 10, 3, 7, 32)
    private val line = InvoiceLine("p1", "Mamre Chapathi", 70, 250, 12, 250)

    /** A saved customer on credit with history, so a balance and a month summary WOULD be built for a normal one. */
    private fun invoice(corporate: Boolean, customerId: String? = "c1") = InvoiceRecord(
        id = "i1", number = "MAM-W1-0042", customerId = customerId, customerName = "FreshMart", customerTypeName = "Shop",
        deviceCode = "W1", issuedAt = at, lines = listOf(line), totalCents = line.lineTotalCents, paidNowCents = 0, method = null,
        balanceAfterCents = 24_500, salesmanName = "Rajesh", customerLocation = "Downtown", isCorporate = corporate,
    )

    private val ledger: List<LedgerEntry> = listOf(
        InvoiceEntry(LocalDate.of(2026, 9, 12), 7_000, false),
        PaymentEntry(LocalDate.of(2026, 9, 20), 2_000, PaymentMethod.CASH),
        CreditEntry(LocalDate.of(2026, 10, 1), 500),
        InvoiceEntry(day, 17_500, false),
    )

    // ------------------------------------------------------------------ the invoice builder

    @Test fun aCorporateInvoiceReceiptCarriesNoBalanceAndNoMonthSummaryEvenWhenAskedForOne() {
        val receipt = invoiceReceiptOf(invoice(corporate = true), ledger, showMonthSummary = true)
        assertNull("balance must not be built", receipt.balanceAfterCents)
        assertNull("month summary must not be built", receipt.month)
        assertTrue(receipt.isCorporate)
    }

    @Test fun theSameInvoiceForANormalCustomerDoesCarryBoth() {
        val receipt = invoiceReceiptOf(invoice(corporate = false), ledger, showMonthSummary = true)
        assertEquals(24_500L, receipt.balanceAfterCents)
        assertNotNull(receipt.month)
        assertEquals(17_500L, receipt.month!!.invoicedCents) // only the October invoice is in the month
        assertFalse(receipt.isCorporate)
    }

    @Test fun theCorporateFlagCanAlsoBeGivenToTheBuilderDirectly() {
        val flaggedByCaller = invoiceReceiptOf(invoice(corporate = false), ledger, true, TestHeaders.full, isCorporate = true)
        assertNull(flaggedByCaller.balanceAfterCents)
        assertNull(flaggedByCaller.month)
        val notFlagged = invoiceReceiptOf(invoice(corporate = true), ledger, true, TestHeaders.full, isCorporate = false)
        assertEquals(24_500L, notFlagged.balanceAfterCents) // the explicit argument wins; the view model passes the record's flag
    }

    @Test fun aCorporateReceiptKeepsEverythingElseTheBillNeeds() {
        val receipt = invoiceReceiptOf(invoice(corporate = true), ledger, true)
        assertEquals("MAM-W1-0042", receipt.number)
        assertEquals("Rajesh", receipt.salesmanName)
        assertEquals("Downtown", receipt.customerLocation)
        assertEquals(line.lineTotalCents, receipt.totalCents)
        assertEquals(1, receipt.items.size)
    }

    @Test fun aWalkInHasNoBalanceAndNoMonthSummaryToo() {
        val receipt = invoiceReceiptOf(invoice(corporate = false, customerId = null), emptyList(), showMonthSummary = false)
        assertNull(receipt.balanceAfterCents)
        assertNull(receipt.month)
    }

    // ------------------------------------------------------------------ the payment builder

    private fun payment(corporate: Boolean) = PaymentRecord(
        id = "p1", receiptNumber = "RCP-W1-0001", customerId = "c1", customerName = "FreshMart", deviceCode = "W1",
        paidAt = at, amountCents = 5_000, method = PaymentMethod.CHECK, note = "", salesmanName = "Rajesh",
        customerLocation = "Downtown", isCorporate = corporate,
    )

    @Test fun aCorporatePaymentReceiptCarriesNoBalance() {
        val receipt = paymentReceiptOf(payment(corporate = true), balanceAfterCents = 19_500)
        assertNull("balance must not be built", receipt.balanceAfterCents)
        assertTrue(receipt.isCorporate)
        assertEquals(5_000L, receipt.amountCents)
    }

    @Test fun aNormalPaymentReceiptStillCarriesTheBalance() {
        val receipt = paymentReceiptOf(payment(corporate = false), balanceAfterCents = 19_500)
        assertEquals(19_500L, receipt.balanceAfterCents)
        assertFalse(receipt.isCorporate)
    }

    @Test fun theCorporateFlagCanBeGivenToThePaymentBuilderDirectly() {
        val receipt = paymentReceiptOf(payment(corporate = false), 19_500, TestHeaders.full, isCorporate = true)
        assertNull(receipt.balanceAfterCents)
    }
}
