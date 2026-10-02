package com.mamre.billing.domain.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnFormLogicTest {
    private fun can(
        qty: Int = 4,
        price: Long? = 250,
        reason: ReturnReason? = ReturnReason.DAMAGED,
        resolution: ReturnResolution? = ReturnResolution.CREDIT,
        product: Boolean = true,
    ) = canRecordReturn(product, qty, price, reason, resolution)

    @Test fun aCompleteFormCanBeRecorded() {
        assertTrue(can())
        assertTrue(can(resolution = ReturnResolution.REPLACEMENT))
    }

    @Test fun everyPartIsRequired() {
        assertFalse(can(product = false))
        assertFalse(can(qty = 0))
        assertFalse(can(reason = null))
        assertFalse(can(resolution = null))
    }

    @Test fun aProductWithNoPriceCannotBeReturnedNotEvenAsAReplacement() {
        assertFalse(can(price = null))
        assertFalse(can(price = null, resolution = ReturnResolution.REPLACEMENT))
    }

    @Test fun otherNeedsANoteAndTheRestDoNot() {
        assertTrue(noteMissingForOther(PaymentMethod.OTHER, ""))
        assertTrue(noteMissingForOther(PaymentMethod.OTHER, "   "))
        assertFalse(noteMissingForOther(PaymentMethod.OTHER, "money order"))
        for (m in PaymentMethod.entries.filter { it != PaymentMethod.OTHER }) {
            assertFalse(noteMissingForOther(m, ""))
        }
    }

    @Test fun theCreditShownBeforeConfirmingMatchesWhatIsStored() {
        assertEquals(1000L, returnCreditCents(ReturnResolution.CREDIT, 4, 250))
        assertEquals(0L, returnCreditCents(ReturnResolution.REPLACEMENT, 4, 250))
    }
}
