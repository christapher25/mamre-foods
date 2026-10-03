package com.mamre.billing.domain.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set C3: a worker may change a line's price only when the customer type's flag allows it. */
class PriceEditTest {
    private val fresh = PricedProduct("p1", "Mamre Fresh Chapathi", 280)
    private val key = PacketKey("p1", 6)

    private fun rejected(allowed: Boolean, text: String, list: Long = 280) =
        (checkPriceEdit(allowed, text, list) as PriceEditResult.Rejected).problem

    @Test fun anEditableTypeAcceptsAPriceAboveZeroUpToTenTimesTheList() {
        assertEquals(PriceEditResult.Ok(250), checkPriceEdit(true, "2.50", 280))
        assertEquals(PriceEditResult.Ok(2800), checkPriceEdit(true, "28", 280))
        assertEquals(PriceEditResult.Ok(1), checkPriceEdit(true, "0.01", 280))
    }

    @Test fun aNonEditableTypeNeverAcceptsAChangeNoMatterWhatIsTyped() {
        assertEquals(PriceEditProblem.NOT_ALLOWED, rejected(false, "2.50"))
        assertEquals(PriceEditProblem.NOT_ALLOWED, rejected(false, "abc"))
        assertEquals(PriceEditProblem.NOT_ALLOWED, rejected(false, "0"))
    }

    @Test fun zeroNegativeOrNotANumberIsRefused() {
        assertEquals(PriceEditProblem.NOT_POSITIVE, rejected(true, "0"))
        assertEquals(PriceEditProblem.NOT_POSITIVE, rejected(true, "0.00"))
        assertEquals(PriceEditProblem.NOT_POSITIVE, rejected(true, "-1"))
        assertEquals(PriceEditProblem.NOT_AN_AMOUNT, rejected(true, ""))
        assertEquals(PriceEditProblem.NOT_AN_AMOUNT, rejected(true, "abc"))
        assertEquals(PriceEditProblem.NOT_AN_AMOUNT, rejected(true, "2.505"))
    }

    @Test fun anAbsurdPriceIsRefused() {
        assertEquals(PriceEditProblem.TOO_HIGH, rejected(true, "28.01")) // more than 10 x $2.80
        assertEquals(PriceEditProblem.TOO_HIGH, rejected(true, "9999999"))
    }

    @Test fun theMessagesNameTheListPrice() {
        assertTrue(priceEditMessage(PriceEditProblem.TOO_HIGH, 280).contains("\$28.00"))
        assertTrue(priceEditMessage(PriceEditProblem.NOT_POSITIVE, 280).contains("above"))
        assertTrue(priceEditMessage(PriceEditProblem.NOT_ALLOWED, 280).isNotBlank())
    }

    // --- the lines ---

    @Test fun aChangedPriceIsAppliedWhenAllowedAndTheLineIsFlagged() {
        val line = buildPacketLines(listOf(fresh), listOf(PacketEntry(key, 2, 250)), priceEditAllowed = true).single()
        assertEquals(250L, line.unitPriceCents)
        assertEquals(280L, line.listPriceCents)
        assertTrue(line.priceOverridden)
        assertEquals(500L, line.lineTotalCents)
    }

    @Test fun aChangedPriceIsIgnoredWhenTheTypeIsNotEditableEvenIfTheScreenWasBypassed() {
        val line = buildPacketLines(listOf(fresh), listOf(PacketEntry(key, 2, 1)), priceEditAllowed = false).single()
        assertEquals(280L, line.unitPriceCents)
        assertFalse(line.priceOverridden)
    }

    @Test fun chargingExactlyTheListPriceIsNotAnOverride() {
        val line = buildPacketLines(listOf(fresh), listOf(PacketEntry(key, 1, 280)), priceEditAllowed = true).single()
        assertFalse(line.priceOverridden)
    }

    @Test fun resetToTheListPriceClearsTheOverride() {
        val edited = buildPacketLines(listOf(fresh), listOf(PacketEntry(key, 1, 200)), priceEditAllowed = true).single()
        assertTrue(edited.priceOverridden)
        val reset = buildPacketLines(listOf(fresh), listOf(PacketEntry(key, 1, null)), priceEditAllowed = true).single()
        assertFalse(reset.priceOverridden)
        assertEquals(280L, reset.unitPriceCents)
    }

    @Test fun aChangedPriceOnACustomPacketKeepsTheCustomListPrice() {
        val custom = PacketKey("p1", 10)
        val line = buildPacketLines(listOf(fresh), listOf(PacketEntry(custom, 1, 400)), priceEditAllowed = true).single()
        assertEquals(467L, line.listPriceCents) // 280 x 10 / 6
        assertEquals(400L, line.unitPriceCents)
        assertTrue(line.priceOverridden && line.isCustomPacket)
    }

    @Test fun aZeroOrNegativeChargedPriceNeverBecomesAPrice() {
        val line = buildPacketLines(listOf(fresh), listOf(PacketEntry(key, 1, 0)), priceEditAllowed = true).single()
        assertEquals(280L, line.unitPriceCents)
    }

    @Test fun whichTypesMayEditFollowsTheFlag() {
        val types = listOf(
            com.mamre.billing.domain.model.CustomerType("t1", "Restaurant", true, false),
            com.mamre.billing.domain.model.CustomerType("t3", "Retail", true, true),
            com.mamre.billing.domain.model.CustomerType("t4", "Catering", true, true),
        )
        assertFalse(typeAllowsPriceEdit(types, "t1", walkIn = false))
        assertTrue(typeAllowsPriceEdit(types, "t4", walkIn = false))
        assertTrue(typeAllowsPriceEdit(types, null, walkIn = true)) // a walk-in follows the Retail flag
        assertFalse(typeAllowsPriceEdit(types.map { if (it.name == "Retail") it.copy(workerCanEditPrice = false) else it }, null, walkIn = true))
        assertFalse(typeAllowsPriceEdit(types, "unknown", walkIn = false))
    }
}
