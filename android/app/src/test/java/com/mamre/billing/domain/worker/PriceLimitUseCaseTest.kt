package com.mamre.billing.domain.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding 2: the price limits (above zero, at most 10 times the list price) are ONE rule, priceLimitProblem,
 * called by checkPriceEdit, buildPacketLines and the MakeBill use case. The first two are called directly here; MakeBill is
 * tested on the real database in data/local/PriceLimitMakeBillTest, so a screen or a view model that skips its own check
 * still cannot produce an illegal price.
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
}
