package com.mamre.billing.domain.worker

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceMathTest {
    private val chapathi = InvoiceLine("p1", "Mamre Chapathi", 10, 250)
    private val fresh = InvoiceLine("p2", "Mamre Fresh Chapathi", 20, 280)

    @Test fun lineTotalIsQuantityTimesPrice() {
        assertEquals(2500L, chapathi.lineTotalCents)
        assertEquals(5600L, fresh.lineTotalCents)
        assertEquals(250L, lineTotal(1, 250))
    }

    @Test fun invoiceTotalIsTheSumOfTheLinesAsInTheDoc2Sample() {
        assertEquals(8100L, invoiceTotal(listOf(chapathi, fresh)))
    }

    @Test fun anEmptyInvoiceTotalsZero() {
        assertEquals(0L, invoiceTotal(emptyList()))
    }

    @Test fun largeQuantitiesDoNotOverflowIntArithmetic() {
        assertEquals(9999L * 100_000L, lineTotal(9999, 100_000))
        assertEquals(3_000_000_000L, lineTotal(30_000, 100_000))
    }

    @Test fun aLineNeedsAtLeastOnePacketAndARealPrice() {
        assertThrows(IllegalArgumentException::class.java) { InvoiceLine("p", "x", 0, 250) }
        assertThrows(IllegalArgumentException::class.java) { InvoiceLine("p", "x", 1, 0) }
    }

    @Test fun anInvoiceRecordRefusesATotalThatIsNotTheSumOfItsLines() {
        val now = LocalDateTime.of(2026, 10, 2, 9, 0)
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceRecord("i", "MAM-W1-0001", null, "Walk-in", "Retail", "W1", now, listOf(chapathi), 1L, 0, null, 0)
        }
    }

    @Test fun aPaymentNeedsAMethodAndNoPaymentHasNone() {
        val now = LocalDateTime.of(2026, 10, 2, 9, 0)
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceRecord("i", "MAM-W1-0001", null, "W", "Retail", "W1", now, listOf(chapathi), 2500, 2500, null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceRecord("i", "MAM-W1-0001", null, "W", "Retail", "W1", now, listOf(chapathi), 2500, 0, PaymentMethod.CASH, 2500)
        }
    }

    @Test fun invoiceNumbersFollowTheDoc2Pattern() {
        assertEquals("MAM-W1-0001", invoiceNumber("W1", 1))
        assertEquals("MAM-W1-0042", invoiceNumber("W1", 42))
        assertEquals("MAM-W2-9999", invoiceNumber("W2", 9999))
        assertEquals("MAM-W1-10000", invoiceNumber("W1", 10_000))
        assertTrue(isValidInvoiceNumber(invoiceNumber("W1", 7)))
    }

    @Test fun invoiceNumbersRefuseABadDeviceCodeOrSequence() {
        assertThrows(IllegalArgumentException::class.java) { invoiceNumber("w1", 1) }
        assertThrows(IllegalArgumentException::class.java) { invoiceNumber("", 1) }
        assertThrows(IllegalArgumentException::class.java) { invoiceNumber("W-1", 1) }
        assertThrows(IllegalArgumentException::class.java) { invoiceNumber("W1", 0) }
    }

    @Test fun theValidatorAcceptsOnlyTheDoc2Pattern() {
        assertTrue(isValidInvoiceNumber("MAM-W1-0042"))
        assertFalse(isValidInvoiceNumber("MAM-W1-42"))
        assertFalse(isValidInvoiceNumber("MAM-w1-0042"))
        assertFalse(isValidInvoiceNumber("INV-W1-0042"))
    }

    @Test fun sequenceIsReadOnlyForTheSameDevice() {
        assertEquals(42, sequenceOf("MAM-W1-0042", "W1"))
        assertNull(sequenceOf("MAM-W2-0042", "W1"))
        assertNull(sequenceOf("garbage", "W1"))
    }
}
