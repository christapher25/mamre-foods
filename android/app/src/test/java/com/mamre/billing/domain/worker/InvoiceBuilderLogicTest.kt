package com.mamre.billing.domain.worker

import com.mamre.billing.domain.money.centsToPlain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceBuilderLogicTest {
    private val chapathi = PricedProduct("p1", "Mamre Chapathi", 250)
    private val fresh = PricedProduct("p2", "Mamre Fresh Chapathi", 280)
    private val unpriced = PricedProduct("p3", "New Product", null)

    @Test fun linesAreOnlyTheProductsWithPackets() {
        val lines = buildInvoiceLines(listOf(chapathi, fresh), mapOf("p1" to 10, "p2" to 0))
        assertEquals(listOf(InvoiceLine("p1", "Mamre Chapathi", 10, 250)), lines)
        assertEquals(2500L, invoiceTotal(lines))
    }

    @Test fun theSample81DollarInvoice() {
        val lines = buildInvoiceLines(listOf(chapathi, fresh), mapOf("p1" to 10, "p2" to 20))
        assertEquals(8100L, invoiceTotal(lines))
    }

    @Test fun aProductWithNoPriceNeverBecomesALineNeverZero() {
        val lines = buildInvoiceLines(listOf(chapathi, unpriced), mapOf("p1" to 2, "p3" to 5))
        assertEquals(listOf("p1"), lines.map { it.productId })
        assertEquals(500L, invoiceTotal(lines))
    }

    @Test fun noPacketsMeansNoLinesAndNothingToContinueWith() {
        assertTrue(buildInvoiceLines(listOf(chapathi, fresh), emptyMap()).isEmpty())
        assertEquals(false, canContinueInvoice(emptyList()))
        assertEquals(true, canContinueInvoice(buildInvoiceLines(listOf(chapathi), mapOf("p1" to 1))))
    }

    @Test fun negativeQuantitiesAreIgnored() {
        assertTrue(buildInvoiceLines(listOf(chapathi), mapOf("p1" to -3)).isEmpty())
    }

    @Test fun amountsAreWrittenBackAsPlainDecimalText() {
        assertEquals("81.00", centsToPlain(8100))
        assertEquals("0.05", centsToPlain(5))
        assertEquals("0.00", centsToPlain(0))
        assertEquals("1234.56", centsToPlain(123456))
        assertEquals("-1.50", centsToPlain(-150))
    }

    @Test fun paymentMessagesNameTheRule() {
        assertEquals("Enter an amount like 12.50", paymentMessage(PaymentProblem.NOT_AN_AMOUNT, PayerKind.CREDIT_CUSTOMER, 8100))
        assertEquals("The amount cannot be negative", paymentMessage(PaymentProblem.NEGATIVE, PayerKind.CREDIT_CUSTOMER, 8100))
        assertEquals("The amount must be more than zero", paymentMessage(PaymentProblem.ZERO, PayerKind.CREDIT_CUSTOMER, 0))
        assertEquals("Walk-in sales must be paid in full: $81.00", paymentMessage(PaymentProblem.MUST_PAY_IN_FULL, PayerKind.WALK_IN, 8100))
        assertEquals("Cash customers must pay at least $81.00", paymentMessage(PaymentProblem.MUST_PAY_IN_FULL, PayerKind.CASH_CUSTOMER, 8100))
    }
}
