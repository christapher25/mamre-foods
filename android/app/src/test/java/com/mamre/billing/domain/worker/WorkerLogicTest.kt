package com.mamre.billing.domain.worker

import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.PaymentMode
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerLogicTest {
    private fun customer(name: String, mode: PaymentMode = PaymentMode.CREDIT, active: Boolean = true) =
        Customer(name.lowercase(), name, "type", "", "", mode, active)

    private val spice = customer("Spice Garden")
    private val corner = customer("Corner Shop", PaymentMode.CASH)
    private val anil = customer("anil's store")

    @Test fun searchMatchesAnyPartOfTheNameIgnoringCase() {
        val all = listOf(spice, corner, anil)
        assertEquals(listOf(spice), filterCustomers(all, "spice"))
        assertEquals(listOf(spice), filterCustomers(all, "  GARDEN "))
        assertEquals(listOf(corner), filterCustomers(all, "sho"))
        assertEquals(listOf(anil), filterCustomers(all, "'s"))
    }

    @Test fun anEmptySearchListsEveryoneByName() {
        assertEquals(listOf(anil, corner, spice), filterCustomers(listOf(spice, corner, anil), ""))
    }

    @Test fun noMatchGivesAnEmptyList() {
        assertTrue(filterCustomers(listOf(spice), "zzz").isEmpty())
    }

    @Test fun payerKindFollowsTheCustomerAndWalkIn() {
        assertEquals(PayerKind.WALK_IN, payerKind(null))
        assertEquals(PayerKind.CASH_CUSTOMER, payerKind(corner))
        assertEquals(PayerKind.CREDIT_CUSTOMER, payerKind(spice))
    }

    @Test fun receiptNumbersFollowTheirOwnSequence() {
        assertEquals("RCP-W1-0001", receiptNumber("W1", 1))
        assertEquals("RCP-W1-0042", receiptNumber("W1", 42))
        assertEquals(7, receiptSequenceOf("RCP-W1-0007", "W1"))
        assertNull(receiptSequenceOf("RCP-W2-0007", "W1"))
        assertNull(receiptSequenceOf("MAM-W1-0007", "W1"))
        assertThrows(IllegalArgumentException::class.java) { receiptNumber("w1", 1) }
    }

    @Test fun aCreditReturnIsPacketsTimesPriceAndAReplacementIsZero() {
        assertEquals(1000L, returnCreditCents(ReturnResolution.CREDIT, 4, 250))
        assertEquals(0L, returnCreditCents(ReturnResolution.REPLACEMENT, 4, 250))
    }

    private fun returnRecord(resolution: ReturnResolution, credit: Long, qty: Int = 4) = ReturnRecord(
        "r", "c", "Spice Garden", "p", "Mamre Chapathi", qty, ReturnReason.DAMAGED, resolution, 250, credit,
        "W1", LocalDateTime.of(2026, 10, 2, 9, 0),
    )

    @Test fun aReturnRecordRefusesACreditThatDoesNotMatchItsResolution() {
        returnRecord(ReturnResolution.CREDIT, 1000)
        returnRecord(ReturnResolution.REPLACEMENT, 0)
        assertThrows(IllegalArgumentException::class.java) { returnRecord(ReturnResolution.CREDIT, 999) }
        assertThrows(IllegalArgumentException::class.java) { returnRecord(ReturnResolution.REPLACEMENT, 1000) }
        assertThrows(IllegalArgumentException::class.java) { returnRecord(ReturnResolution.CREDIT, 0, qty = 0) }
    }

    @Test fun theReasonsAreExactlyTheDoc1Section71List() {
        assertEquals(
            listOf("Damaged", "Quality complaint", "Wrong item", "Expired", "Other"),
            ReturnReason.entries.map { it.label },
        )
        assertEquals(listOf("Credit", "Replacement"), ReturnResolution.entries.map { it.label })
    }
}
