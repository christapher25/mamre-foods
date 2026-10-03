package com.mamre.billing.print

import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change sets C2, C3 and D3: the item line names the chapathis per packet (standard or custom) and prints the charged price only. */
class ReceiptCustomPacketTest {
    private val base = InvoiceReceipt(
        businessName = "MAMRE FOODS",
        addressLines = BUSINESS_ADDRESS_LINES,
        number = "MAM-W1-0043",
        issuedAt = LocalDateTime.of(2026, 10, 10, 14, 20),
        salesmanName = "Rajesh",
        customerName = "Royal Banquets",
        customerLocation = "Addison",
        items = listOf(ReceiptItem("Mamre Fresh Chapathi", 2, 280, 560, 6)),
        totalCents = 560,
        paidNowCents = 560,
        method = PaymentMethod.CASH,
        balanceAfterCents = null,
        month = null,
    )

    @Test fun aStandardPacketLineShowsItsSizeInTheSameStyleAsACustomOne() {
        val lines = layoutInvoiceReceipt(base)
        assertTrue(lines.contains("MAMRE FRESH CHAPATHI 6NOS"))
        assertTrue(lines.none { it.contains("pcs") })
    }

    @Test fun aCustomPacketLineShowsItsSize() {
        val custom = base.copy(items = listOf(ReceiptItem("Mamre Fresh Chapathi", 3, 467, 1401, 10)), totalCents = 1401, paidNowCents = 1401)
        val lines = layoutInvoiceReceipt(custom)
        assertTrue(lines.contains("MAMRE FRESH CHAPATHI 10NOS"))
        assertEquals("                3   4.67   14.01", lines[lines.indexOf("MAMRE FRESH CHAPATHI 10NOS") + 1])
        assertTrue(lines.all { it.length <= 32 })
    }

    @Test fun aLongNameWithASizeStillWrapsWithin32Columns() {
        val custom = base.copy(items = listOf(ReceiptItem("Mamre Fresh Chapathi Extra Large Family Pack", 3, 467, 1401, 200)))
        val lines = layoutInvoiceReceipt(custom)
        assertTrue(lines.all { it.length <= 32 })
        assertTrue(lines.contains("MAMRE FRESH CHAPATHI EXTRA LARGE"))
        assertTrue(lines.contains("FAMILY PACK 200NOS"))
    }

    @Test fun theReceiptShowsTheChargedPriceOnlyAndNotTheListPrice() {
        val line = InvoiceLine("p", "Mamre Fresh Chapathi", 1, 400, 6, 450)
        val item = ReceiptItem(line.productName, line.qtyPackets, line.unitPriceCents, line.lineTotalCents, line.chapathisPerPacket)
        val text = layoutInvoiceReceipt(base.copy(items = listOf(item), totalCents = 400, paidNowCents = 400)).joinToString("\n")
        assertTrue(text.contains("4.00"))
        assertFalse(text.contains("4.50"))
        assertFalse(text.contains("List"))
    }
}
