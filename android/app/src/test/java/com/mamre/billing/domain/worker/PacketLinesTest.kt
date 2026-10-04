package com.mamre.billing.domain.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set C2: standard packets of 12 chapathis and custom packets of 1 to 200 (integer arithmetic only). */
class PacketLinesTest {
    private val fresh = PricedProduct("p1", "Mamre Fresh Chapathi", 280)
    private val chapathi = PricedProduct("p2", "Mamre Chapathi", 250)
    private val unpriced = PricedProduct("p3", "New Product", null)

    private fun std(product: String, qty: Int) = PacketEntry(PacketKey(product, 12), qty)
    private fun custom(product: String, size: Int, qty: Int) = PacketEntry(PacketKey(product, size), qty)

    @Test fun aCustomPacketPriceIsTheStandardPriceTimesChapathisOverTheStandardSizeRoundedHalfUp() {
        assertEquals(208L, customPacketPriceCents(250, 10, 12)) // 250 per 12 -> 10 chapathis: 208.33 -> 208
        assertEquals(188L, customPacketPriceCents(250, 9, 12)) // 187.5 -> 188, a half rounds up
        assertEquals(192L, customPacketPriceCents(230, 10, 12)) // 191.67 -> 192
        assertEquals(21L, customPacketPriceCents(250, 1, 12)) // 20.83 -> 21
        assertEquals(250L, customPacketPriceCents(250, 12, 12)) // the standard packet itself is exact
        assertEquals(500L, customPacketPriceCents(250, 24, 12))
        assertEquals(4167L, customPacketPriceCents(250, 200, 12)) // 4166.67 -> 4167
    }

    @Test fun halfCentsRoundUp() {
        assertEquals(3L, customPacketPriceCents(5, 6, 12)) // 2.5 -> 3, not banker rounding to 2
        assertEquals(2L, customPacketPriceCents(5, 4, 12)) // 1.67 -> 2
        assertEquals(117L, customPacketPriceCents(7, 200, 12)) // 116.67 -> 117
    }

    @Test fun aStandardAndACustomLineOfOneProductAreTwoLines() {
        val lines = buildPacketLines(listOf(fresh), listOf(custom("p1", 10, 3), std("p1", 2)))
        assertEquals(2, lines.size)
        val s = lines[0]
        val c = lines[1]
        assertEquals(12, s.chapathisPerPacket)
        assertEquals(280L, s.unitPriceCents)
        assertFalse(s.isCustomPacket)
        assertEquals(10, c.chapathisPerPacket)
        assertEquals(233L, c.unitPriceCents) // 280 x 10 / 12 = 233.33 -> 233
        assertEquals(233L, c.listPriceCents)
        assertTrue(c.isCustomPacket)
    }

    @Test fun mixedSizeInvoiceTotalsAndChapathisSold() {
        val lines = buildPacketLines(
            listOf(fresh, chapathi),
            listOf(std("p1", 2), custom("p1", 10, 3), custom("p2", 24, 1), std("p2", 4)),
        )
        // Fresh: 2 x 280 + 3 x 233 ; Chapathi: 4 x 250 + 1 x 500 (24 chapathis = two standard packets)
        assertEquals(560L + 699L + 1000L + 500L, invoiceTotal(lines))
        assertEquals(2 * 12 + 3 * 10 + 4 * 12 + 1 * 24, chapathisSold(lines))
        assertEquals(2 + 3 + 4 + 1, lines.sumOf { it.qtyPackets })
    }

    @Test fun anInvalidSizeOrAnUnpricedProductNeverBecomesALine() {
        val lines = buildPacketLines(
            listOf(fresh, unpriced),
            listOf(custom("p1", 0, 2), custom("p1", 201, 2), custom("p1", -4, 1), std("p3", 5), custom("p3", 10, 1), std("p1", 0)),
        )
        assertTrue(lines.isEmpty())
    }

    @Test fun theStandardSizeFollowsTheProduct() {
        val eight = PricedProduct("p1", "Mamre Fresh Chapathi", 280, standardPacketSize = 8)
        val lines = buildPacketLines(listOf(eight), listOf(PacketEntry(PacketKey("p1", 8), 1), PacketEntry(PacketKey("p1", 6), 1)))
        assertFalse(lines[0].isCustomPacket) // 8 is this product's standard packet
        assertEquals(280L, lines[0].unitPriceCents)
        assertTrue(lines[1].isCustomPacket)
        assertEquals(210L, lines[1].unitPriceCents) // 280 x 6 / 8
    }

    @Test fun anInvoiceLineKnowsItsChapathisAndListPrice() {
        val line = InvoiceLine("p1", "x", 3, 467, chapathisPerPacket = 10, listPriceCents = 467)
        assertEquals(30, line.chapathis)
        assertEquals(1401L, line.lineTotalCents)
        assertFalse(line.priceOverridden)
        assertTrue(InvoiceLine("p1", "x", 1, 400, 12, 450).priceOverridden)
        assertEquals(12, InvoiceLine("p1", "x", 1, 400).chapathisPerPacket) // the old 4-argument form is a standard packet
        assertEquals(400L, InvoiceLine("p1", "x", 1, 400).listPriceCents)
    }

    @Test fun theCustomPacketSizeRange() {
        assertTrue(isValidPacketSize(1))
        assertTrue(isValidPacketSize(200))
        assertFalse(isValidPacketSize(0))
        assertFalse(isValidPacketSize(201))
    }
}
