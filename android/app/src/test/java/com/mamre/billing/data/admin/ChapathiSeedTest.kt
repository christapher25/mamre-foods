package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.StockRow
import com.mamre.billing.domain.worker.customPacketPriceCents
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set C2 on the demo data: standard packets of 6, custom packets, chapathi based usage. */
class ChapathiSeedTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val state = AdminSeed.build(today, SharedPriceTable.seeded(today, baseVersion = 10))

    @Test fun theStandardPacketIsSixChapathisAndTheYieldIs32() {
        assertTrue(state.products.all { it.unitsPerPacket == 6 && it.yieldPerKg == 32 })
    }

    @Test fun everyInvoiceLineAddsUpAndHoldsOneToTwoHundredChapathis() {
        for (item in state.invoices.flatMap { it.items }) {
            assertEquals(item.qtyPackets * item.unitPriceCents, item.lineTotalCents)
            assertTrue(item.chapathisPerPacket in 1..200)
            assertEquals(item.isCustomPacket, item.chapathisPerPacket != 6)
        }
        for (inv in state.invoices) assertEquals(inv.items.sumOf { it.lineTotalCents }, inv.totalCents)
    }

    @Test fun thereAreCustomPacketsAndTheirListPriceFollowsTheStandardPrice() {
        val custom = state.invoices.flatMap { it.items }.filter { it.isCustomPacket }
        assertTrue(custom.isNotEmpty())
        for (item in custom) {
            // The list price of a custom packet is the standard list price x N / 6, rounded half up.
            val standard = state.invoices.flatMap { it.items }
                .first { !it.isCustomPacket && it.productId == item.productId && it.listPriceCents * item.chapathisPerPacket / 6 in
                    (item.listPriceCents - 6)..(item.listPriceCents + 6) }
            assertEquals(customPacketPriceCents(standard.listPriceCents, item.chapathisPerPacket, 6), item.listPriceCents)
        }
    }

    @Test fun theSeedBuysEnoughMaterialsSoWheatNeverRunsOutInAFullMonth() {
        for (m in listOf(YearMonth.of(2026, 5), YearMonth.of(2026, 7), YearMonth.of(2026, 9))) {
            val wheat: StockRow = ServerLogic.stock(state, m).rows.first { it.material.id == SeedIds.WHEAT }
            val closing = wheat.closingQtyMb as Figure.Known
            assertFalse("closing wheat in $m", closing.value < 0)
            assertFalse(wheat.negativeStock)
        }
    }

    @Test fun damageInTheSeedIsAMultipleOfNothingInParticularButAtLeastOnePacketOfChapathis() {
        assertTrue(state.damage.all { it.chapathis >= 6 })
    }

    @Test fun chapathisSoldIsPacketsTimesSizeOnTheDashboard() {
        val sep = YearMonth.of(2026, 9)
        val d = ServerLogic.dashboard(state, sep, YearMonth.of(2026, 4))
        val items = state.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == sep }.flatMap { it.items }
        assertEquals(items.sumOf { it.qtyPackets }, d.packetsSold)
        assertEquals(items.sumOf { it.qtyPackets.toLong() * it.chapathisPerPacket }, d.chapathisSold)
        assertTrue(d.chapathisSold > d.packetsSold)
    }
}
