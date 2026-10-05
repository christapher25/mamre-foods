package com.mamre.billing.print

import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.World
import com.mamre.billing.data.local.section65World
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The receipt builders on bills made through the real use cases (moved from the demo store, L1 step 1): the Doc 1 s6.5
 * ledger in September, the bill's month summary, a reprint, a walk-in, and the chapathis per packet.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiptBuildersTest {
    private suspend fun World.openingOf(id: String) = sales.state().openingOf(id)

    @Test fun anOctoberInvoiceOpensWithTheSeptemberBalanceBroughtForward() = runBlocking {
        val (w, id) = section65World()
        w.clock.set("2026-10-02T14:20:00Z")
        val invoice = w.makeBill(w.draft(id, listOf(w.line(qty = 25, unit = 100)), paid = 1000, method = PaymentMethod.CASH))
        val receipt = invoiceReceiptOf(invoice, w.sales.state().ledgerOf(id), true)
        val month = receipt.month!!
        assertEquals("Oct 2026", month.monthLabel)
        assertEquals(12000L, month.broughtForwardCents) // Doc 1 s6.5 closing balance, made in September
        assertEquals(2500L, month.invoicedCents)
        assertEquals(listOf(MonthPayment(invoice.issuedAt.toLocalDate(), PaymentMethod.CASH, 1000)), month.payments)
        assertEquals(13500L, month.totalDueCents)
        assertEquals(invoice.balanceAfterCents, month.totalDueCents) // the receipt agrees with itself
    }

    @Test fun theReceiptCarriesTheSalesmansNameOfTheInvoice() = runBlocking {
        val (w, id) = section65World()
        val invoice = w.makeBill(w.draft(id, listOf(w.line(qty = 2, unit = 100))))
        assertEquals("Rajesh", invoice.salesmanName)
        val lines = layoutInvoiceReceipt(invoiceReceiptOf(invoice, w.sales.state().ledgerOf(id), true))
        assertTrue(lines.contains("Salesman: Rajesh"))
        // The September invoices were made under the same Owner name.
        assertTrue(w.sales.state().invoices.all { it.salesmanName == "Rajesh" })
    }

    @Test fun theLocationIsKeptOnTheInvoiceAndPrintedUnderTheCustomerName() = runBlocking {
        val (w, id) = section65World()
        val store = w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP, corporate = true)
        val invoice = w.makeBill(w.draft(store.id, listOf(w.line(qty = 2, unit = 100))))
        assertEquals("FreshMart - Downtown", invoice.customerDisplay) // what Today's invoices lists
        val lines = layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false))
        val i = lines.indexOf("Customer: FreshMart")
        assertEquals("          Downtown", lines[i + 1])
        // Spice Garden's September bills carry its location too.
        assertTrue(w.sales.state().invoices.filter { it.customerId == id }.all { it.customerDisplay == "Spice Garden - Irving" })
    }

    @Test fun theSeptemberLedgerShowsOnTheSeptemberInvoicesOwnReceipt() = runBlocking {
        val (w, id) = section65World()
        val state = w.sales.state()
        val third = state.invoices.single { it.number == "MAM-W1-0003" }
        val month = invoiceReceiptOf(third, state.ledgerOf(id), true).month!!
        assertEquals("Sep 2026", month.monthLabel)
        assertEquals(0L, month.broughtForwardCents)
        assertEquals(12000L + 9000L + 6000L, month.invoicedCents) // 120 + 90 + 60
        assertEquals(15000L, month.payments.sumOf { it.amountCents })
        assertEquals(12000L, month.totalDueCents) // closing balance $120.00
    }

    @Test fun aReprintLeavesOutWhatHappenedAfterTheInvoice() = runBlocking {
        val (w, id) = section65World()
        val state = w.sales.state()
        val first = state.invoices.single { it.number == "MAM-W1-0001" }
        val month = invoiceReceiptOf(first, state.ledgerOf(id), true).month!!
        assertEquals(12000L, month.invoicedCents)
        assertTrue(month.payments.isEmpty())
        assertEquals(12000L, month.totalDueCents)
    }

    @Test fun aWalkInGetsNeitherBalanceNorMonthSummary() = runBlocking {
        val (w, _) = section65World()
        val invoice = w.makeBill(w.draft(null, listOf(w.line(qty = 10, unit = 100)), paid = 1000, method = PaymentMethod.CASH))
        val receipt = invoiceReceiptOf(invoice, emptyList(), false)
        assertNull(receipt.balanceAfterCents)
        assertNull(receipt.month)
    }

    @Test fun anOpeningBalanceIsBroughtForwardOnTheBillsMonthSummary() = runBlocking {
        val (w, _) = section65World()
        val c = w.customer("Curry House", "Plano", ReferenceIds.TYPE_RESTAURANT, opening = 15_000)
        val invoice = w.makeBill(w.draft(c.id, listOf(w.line(qty = 10, unit = 100))))
        val state = w.sales.state()
        val month = invoiceReceiptOf(invoice, state.ledgerOf(c.id), true, TestHeaders.full, openingBalanceCents = w.openingOf(c.id)).month!!
        assertEquals(15_000L, month.broughtForwardCents)
        assertEquals(16_000L, month.totalDueCents)
        assertEquals(invoice.balanceAfterCents, month.totalDueCents)
    }

    @Test fun everyItemCarriesItsChapathisPerPacketSoACustomPacketPrintsLikeAStandardOne() = runBlocking {
        val (w, _) = section65World()
        val lines = listOf(
            w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = 2, unit = 100),
            w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = 3, unit = 83, size = 10), // 100 x 10 / 12 = 83.33, half up
        )
        val invoice = w.makeBill(w.draft(null, lines, paid = 449, method = PaymentMethod.CASH))
        val receipt = invoiceReceiptOf(invoice, emptyList(), false)
        assertEquals(listOf(12, 10), receipt.items.map { it.chapathisPerPacket })
        val text = layoutInvoiceReceipt(receipt)
        assertTrue(text.contains("MAMRE CHAPATHI 12NOS") && text.contains("MAMRE CHAPATHI 10NOS"))
        assertTrue(text.none { it.startsWith("Type") }) // the customer type is never printed
    }

    @Test fun theBuilderCopiesLinesAndFlagsFromTheInvoice() = runBlocking {
        val (w, id) = section65World()
        val invoice = w.makeBill(w.draft(id, listOf(w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = 10, unit = 100))))
        val receipt = invoiceReceiptOf(invoice, w.sales.state().ledgerOf(id), false, duplicate = true)
        assertEquals("MAM-W1-0004", receipt.number)
        assertEquals(listOf(ReceiptItem("Mamre Chapathi", 10, 100, 1000, 12)), receipt.items)
        assertTrue(receipt.duplicate)
        assertEquals("MAMRE FOODS", receipt.header.name) // the test header; the real one comes from Settings
        assertEquals(LocalDate.of(2026, 10, 2), receipt.issuedAt.toLocalDate())
    }
}
