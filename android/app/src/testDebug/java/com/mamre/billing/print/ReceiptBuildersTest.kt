package com.mamre.billing.print

import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.InvoiceDraft
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptBuildersTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T14:20:00Z"), ZoneOffset.UTC)
    private val store = DemoStore(clock, DemoSeed.state())

    private fun draft(customerId: String?, packets: Int, paid: Long, method: PaymentMethod?) = InvoiceDraft(
        "d-$customerId-$packets", customerId, "Test Restaurant", "Restaurant", "W1",
        listOf(InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", packets, 250)), paid, method,
    )

    @Test fun anOctoberInvoiceOpensWithTheSeptemberBalanceBroughtForward() {
        val invoice = store.confirmInvoice(draft(DemoIds.RESTAURANT, 10, 1000, PaymentMethod.CASH))
        val receipt = invoiceReceiptOf(invoice, store.state.value.ledgerOf(DemoIds.RESTAURANT), true)
        val month = receipt.month!!
        assertEquals("Oct 2026", month.monthLabel)
        assertEquals(12000L, month.broughtForwardCents) // Doc 1 s6.5 closing balance, moved to September
        assertEquals(2500L, month.invoicedCents)
        assertEquals(listOf(MonthPayment(invoice.issuedAt.toLocalDate(), PaymentMethod.CASH, 1000)), month.payments)
        assertEquals(13500L, month.totalDueCents)
        assertEquals(invoice.balanceAfterCents, month.totalDueCents) // the receipt agrees with itself
    }


    @Test fun theReceiptCarriesTheSalesmansNameOfTheInvoice() {
        val invoice = store.confirmInvoice(draft(DemoIds.RESTAURANT, 2, 0, null).copy(salesmanName = "Rajesh"))
        assertEquals("Rajesh", invoice.salesmanName)
        val lines = layoutInvoiceReceipt(invoiceReceiptOf(invoice, store.state.value.ledgerOf(DemoIds.RESTAURANT), true))
        assertTrue(lines.contains("Salesman: Rajesh"))
        // The seeded September invoices were made by the demo salesman too.
        assertTrue(store.state.value.invoices.filter { it.deviceCode == "DEMO" }.all { it.salesmanName == "Rajesh" })
    }

    @Test fun theLocationIsKeptOnTheInvoiceAndPrintedUnderTheCustomerName() {
        val invoice = store.confirmInvoice(draft(DemoIds.FRESHMART_DOWNTOWN, 2, 0, null).copy(customerName = "FreshMart", customerLocation = "Downtown"))
        assertEquals("FreshMart - Downtown", invoice.customerDisplay) // what Today's invoices lists
        val lines = layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false))
        val i = lines.indexOf("Customer: FreshMart")
        assertEquals("          Downtown", lines[i + 1])
        // The seeded Spice Garden ledger carries its location too.
        assertTrue(store.state.value.invoices.filter { it.deviceCode == "DEMO" }.all { it.customerDisplay == "Spice Garden - Irving" })
    }
    @Test fun theSeptemberLedgerShowsOnTheSeptemberInvoicesOwnReceipt() {
        val third = store.state.value.invoices.single { it.number == "MAM-DEMO-0003" }
        val month = invoiceReceiptOf(third, store.state.value.ledgerOf(DemoIds.RESTAURANT), true).month!!
        assertEquals("Sep 2026", month.monthLabel)
        assertEquals(0L, month.broughtForwardCents)
        assertEquals(12000L + 9000L + 6000L, month.invoicedCents) // 120 + 90 + 60
        assertEquals(15000L, month.payments.sumOf { it.amountCents })
        assertEquals(12000L, month.totalDueCents) // closing balance $120.00
    }

    @Test fun aReprintLeavesOutWhatHappenedAfterTheInvoice() {
        val first = store.state.value.invoices.single { it.number == "MAM-DEMO-0001" }
        val month = invoiceReceiptOf(first, store.state.value.ledgerOf(DemoIds.RESTAURANT), true).month!!
        assertEquals(12000L, month.invoicedCents)
        assertTrue(month.payments.isEmpty())
        assertEquals(12000L, month.totalDueCents)
    }

    @Test fun aWalkInGetsNeitherBalanceNorMonthSummary() {
        val invoice = store.confirmInvoice(draft(null, 4, 1000, PaymentMethod.CASH))
        val receipt = invoiceReceiptOf(invoice, emptyList(), false)
        assertNull(receipt.balanceAfterCents)
        assertNull(receipt.month)
    }


    @Test fun everyItemCarriesItsChapathisPerPacketSoACustomPacketPrintsLikeAStandardOne() {
        val lines = listOf(
            InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", 2, 250),
            InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", 3, 417, chapathisPerPacket = 10, listPriceCents = 417, isCustomPacket = true),
        )
        val invoice = store.confirmInvoice(
            InvoiceDraft("d-mixed", null, "Walk-in", "Retail", "W1", lines, 1751, PaymentMethod.CASH, salesmanName = "Rajesh"),
        )
        val receipt = invoiceReceiptOf(invoice, emptyList(), false)
        assertEquals(listOf(6, 10), receipt.items.map { it.chapathisPerPacket })
        val text = layoutInvoiceReceipt(receipt)
        assertTrue(text.contains("MAMRE CHAPATHI 6NOS") && text.contains("MAMRE CHAPATHI 10NOS"))
        assertTrue(text.none { it.startsWith("Type") }) // the customer type is never printed
    }
    @Test fun theBuilderCopiesLinesAndFlagsFromTheInvoice() {
        val invoice = store.confirmInvoice(draft(DemoIds.RESTAURANT, 10, 0, null))
        val receipt = invoiceReceiptOf(invoice, store.state.value.ledgerOf(DemoIds.RESTAURANT), false, duplicate = true)
        assertEquals("MAM-W1-0001", receipt.number)
        assertEquals(listOf(ReceiptItem("Mamre Chapathi", 10, 250, 2500, 6)), receipt.items)
        assertTrue(receipt.duplicate)
        assertEquals(BUSINESS_NAME, receipt.businessName)
    }
}
