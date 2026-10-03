package com.mamre.billing.print

import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set C2: the receipt names the size of a custom packet and nothing else changes. */
class ReceiptCustomPacketTest {
    private val base = InvoiceReceipt(
        businessName = "MAMRE FOODS",
        addressLine = null,
        number = "MAM-W1-0043",
        issuedAt = LocalDateTime.of(2026, 10, 10, 14, 20),
        salesmanName = "Rajesh",
        customerName = "Royal Banquets",
        customerTypeName = "Catering",
        items = listOf(ReceiptItem("Mamre Fresh Chapathi", 2, 280, 560)),
        totalCents = 560,
        paidNowCents = 560,
        method = PaymentMethod.CASH,
        balanceAfterCents = null,
        month = null,
    )

    @Test fun aStandardPacketLineHasNoSizeText() {
        val lines = layoutInvoiceReceipt(base)
        assertTrue(lines.none { it.contains("pcs") })
    }

    @Test fun aCustomPacketLineShowsItsSize() {
        val custom = base.copy(items = listOf(ReceiptItem("Mamre Fresh Chapathi", 3, 467, 1401, customPacketSize = 10)))
        val lines = layoutInvoiceReceipt(custom)
        assertTrue(lines.any { it == "Mamre Fresh Chapathi (10 pcs)" })
        assertTrue(lines.any { it.startsWith("  3 x \$4.67") && it.endsWith("\$14.01") })
        assertTrue(lines.all { it.length <= 32 })
    }

    @Test fun aLongNameWithASizeStillWrapsWithin32Columns() {
        val custom = base.copy(items = listOf(ReceiptItem("Mamre Fresh Chapathi Extra Large Family Pack", 3, 467, 1401, customPacketSize = 200)))
        assertTrue(layoutInvoiceReceipt(custom).all { it.length <= 32 })
    }

    @Test fun theReceiptShowsTheChargedPriceOnlyAndNotTheListPrice() {
        val line = InvoiceLine("p", "Mamre Fresh Chapathi", 1, 400, 6, 450)
        val item = ReceiptItem(line.productName, line.qtyPackets, line.unitPriceCents, line.lineTotalCents)
        val text = layoutInvoiceReceipt(base.copy(items = listOf(item), totalCents = 400, paidNowCents = 400)).joinToString("\n")
        assertTrue(text.contains("\$4.00"))
        assertFalse(text.contains("\$4.50"))
        assertFalse(text.contains("List"))
    }
}
