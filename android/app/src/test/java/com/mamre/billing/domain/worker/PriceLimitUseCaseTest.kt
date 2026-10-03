package com.mamre.billing.domain.worker

import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.InvoiceDraft
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding 2: the price limits (above zero, at most 10 times the list price) are ONE rule, priceLimitProblem,
 * called by checkPriceEdit, buildPacketLines and DemoStore.confirmInvoice. Each use case is called directly here, so a
 * screen or a view model that skips its own check still cannot produce an illegal price.
 */
class PriceLimitUseCaseTest {
    private val list = 250L
    private val cap = list * MAX_PRICE_FACTOR // 2,500 cents, exactly 10 times
    private val fresh = PricedProduct("p1", "Mamre Fresh Chapathi", list)
    private val standard = PacketKey("p1", 12)

    // ------------------------------------------------------------------ the one shared rule

    @Test fun theSharedRuleIsAboveZeroAndAtMostTenTimesTheList() {
        assertEquals(PriceEditProblem.NOT_POSITIVE, priceLimitProblem(0, list))
        assertEquals(PriceEditProblem.NOT_POSITIVE, priceLimitProblem(-1, list))
        assertEquals(PriceEditProblem.NOT_POSITIVE, priceLimitProblem(-cap, list))
        assertNull(priceLimitProblem(1, list))
        assertNull(priceLimitProblem(list, list))
        assertNull(priceLimitProblem(cap, list)) // exactly 10 times is allowed
        assertEquals(PriceEditProblem.TOO_HIGH, priceLimitProblem(cap + 1, list)) // one cent more is refused
    }

    // ------------------------------------------------------------------ checkPriceEdit (typed text)

    @Test fun checkPriceEditUsesTheSameLimits() {
        assertEquals(PriceEditResult.Ok(cap), checkPriceEdit(true, "25.00", list))
        assertEquals(PriceEditResult.Rejected(PriceEditProblem.TOO_HIGH), checkPriceEdit(true, "25.01", list))
        assertEquals(PriceEditResult.Rejected(PriceEditProblem.NOT_POSITIVE), checkPriceEdit(true, "0", list))
        assertEquals(PriceEditResult.Rejected(PriceEditProblem.NOT_POSITIVE), checkPriceEdit(true, "-1.00", list))
    }

    // ------------------------------------------------------------------ buildPacketLines

    private fun lineAt(charged: Long?, allowed: Boolean = true): InvoiceLine =
        buildPacketLines(listOf(fresh), listOf(PacketEntry(standard, 1, charged)), priceEditAllowed = allowed).single()

    @Test fun buildPacketLinesAppliesExactlyTenTimesTheListPrice() {
        val line = lineAt(cap)
        assertEquals(cap, line.unitPriceCents)
        assertEquals(list, line.listPriceCents)
        assertTrue(line.priceOverridden)
    }

    @Test fun buildPacketLinesRefusesOneCentMoreThanTenTimesAndKeepsTheListPrice() {
        val line = lineAt(cap + 1)
        assertEquals(list, line.unitPriceCents)
        assertFalse(line.priceOverridden)
    }

    @Test fun buildPacketLinesRefusesZeroAndNegativePricesAndKeepsTheListPrice() {
        for (bad in listOf(0L, -1L, -list)) {
            val line = lineAt(bad)
            assertEquals("charged $bad", list, line.unitPriceCents)
            assertFalse(line.priceOverridden)
        }
    }

    @Test fun buildPacketLinesTheCapFollowsTheCustomPacketsOwnListPrice() {
        val custom = PacketKey("p1", 10) // 250 x 10 / 12 = 208.33 -> 208
        val customList = customPacketPriceCents(list, 10, 12)
        assertEquals(208L, customList)
        val atCap = buildPacketLines(listOf(fresh), listOf(PacketEntry(custom, 1, customList * 10)), true).single()
        assertEquals(customList * 10, atCap.unitPriceCents)
        val over = buildPacketLines(listOf(fresh), listOf(PacketEntry(custom, 1, customList * 10 + 1)), true).single()
        assertEquals(customList, over.unitPriceCents)
    }

    @Test fun buildPacketLinesStillIgnoresAnyPriceWhenTheTypeIsNotEditable() {
        assertEquals(list, lineAt(cap, allowed = false).unitPriceCents)
    }

    // ------------------------------------------------------------------ DemoStore.confirmInvoice

    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)

    private fun draft(id: String, line: InvoiceLine, allowed: Boolean = true) = InvoiceDraft(
        id, null, "Walk-in", "Retail", "W1", listOf(line), line.lineTotalCents, com.mamre.billing.domain.worker.PaymentMethod.CASH,
        priceEditAllowed = allowed, salesmanName = "Rajesh",
    )

    private fun lineCharging(charged: Long, listCents: Long = list, size: Int = 12) =
        InvoiceLine("p1", "Mamre Fresh Chapathi", 1, charged, size, listCents, isCustomPacket = size != 12)

    @Test fun confirmInvoiceAcceptsExactlyTenTimesTheListPrice() {
        val store = DemoStore(clock)
        val record = store.confirmInvoice(draft("ok", lineCharging(cap)))
        assertEquals(cap, record.lines.single().unitPriceCents)
        assertEquals(1, store.state.value.invoices.size)
    }

    @Test fun confirmInvoiceRefusesOneCentMoreThanTenTimesAndStoresNothing() {
        val store = DemoStore(clock)
        val e = assertThrows(IllegalArgumentException::class.java) { store.confirmInvoice(draft("bad", lineCharging(cap + 1))) }
        assertTrue(e.message!!, e.message!!.contains("10 times"))
        assertTrue(store.state.value.invoices.isEmpty())
        assertEquals(0, store.state.value.pendingCount)
    }

    @Test fun confirmInvoiceRefusesTheCapOfACustomPacketAgainstItsOwnList() {
        val store = DemoStore(clock)
        val customList = customPacketPriceCents(list, 10, 12)
        store.confirmInvoice(draft("custom-ok", lineCharging(customList * 10, customList, size = 10)))
        assertThrows(IllegalArgumentException::class.java) {
            store.confirmInvoice(draft("custom-bad", lineCharging(customList * 10 + 1, customList, size = 10)))
        }
        assertEquals(1, store.state.value.invoices.size)
    }

    @Test fun confirmInvoiceRefusesAZeroOrNegativePriceAndStoresNothing() {
        val store = DemoStore(clock)
        for (bad in listOf(0L, -1L, -list)) {
            // The line cannot even be made with such a price, and the store never sees one.
            assertThrows("charged $bad", IllegalArgumentException::class.java) {
                store.confirmInvoice(draft("zero$bad", lineCharging(bad)))
            }
        }
        assertTrue(store.state.value.invoices.isEmpty())
    }

    @Test fun confirmInvoiceStillRefusesAChangedPriceForANonEditableType() {
        val store = DemoStore(clock)
        assertThrows(IllegalArgumentException::class.java) { store.confirmInvoice(draft("no", lineCharging(200), allowed = false)) }
        // An unchanged price is fine for any type.
        store.confirmInvoice(draft("same", lineCharging(list), allowed = false))
        assertEquals(1, store.state.value.invoices.size)
    }
}
