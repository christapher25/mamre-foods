package com.mamre.billing.domain.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentRulesTest {
    private fun invoice(kind: PayerKind, total: Long, previous: Long, text: String) =
        checkInvoicePayment(kind, total, previous, text)

    private fun rejected(problem: PaymentProblem) = PaymentCheck.Rejected(problem)

    @Test fun creditCustomerMayPayNothing() {
        assertEquals(PaymentCheck.Ok(0, 20100), invoice(PayerKind.CREDIT_CUSTOMER, 8100, 12000, "0"))
    }

    @Test fun balanceAfterIsPreviousPlusTotalMinusAmount() {
        val ok = invoice(PayerKind.CREDIT_CUSTOMER, 8100, 12000, "30") as PaymentCheck.Ok
        assertEquals(3000L, ok.amountCents)
        assertEquals(17100L, ok.balanceAfterCents) // 120.00 + 81.00 - 30.00
        assertEquals(0L, ok.creditOnAccountCents)
    }

    @Test fun anOverpaymentIsCreditOnAccountForACreditCustomer() {
        val ok = invoice(PayerKind.CREDIT_CUSTOMER, 8100, 0, "100") as PaymentCheck.Ok
        assertEquals(-1900L, ok.balanceAfterCents)
        assertEquals(1900L, ok.creditOnAccountCents)
    }

    @Test fun aWalkInMustPayTheExactTotal() {
        assertEquals(PaymentCheck.Ok(8100, 0), invoice(PayerKind.WALK_IN, 8100, 0, "81.00"))
        assertEquals(rejected(PaymentProblem.MUST_PAY_IN_FULL), invoice(PayerKind.WALK_IN, 8100, 0, "80.99"))
        assertEquals(rejected(PaymentProblem.MUST_PAY_IN_FULL), invoice(PayerKind.WALK_IN, 8100, 0, "0"))
        assertEquals(rejected(PaymentProblem.MUST_PAY_IN_FULL), invoice(PayerKind.WALK_IN, 8100, 0, "90"))
    }

    @Test fun aWalkInIgnoresAnyPreviousBalance() {
        assertEquals(PaymentCheck.Ok(8100, 0), invoice(PayerKind.WALK_IN, 8100, 99999, "81"))
    }

    @Test fun aCashCustomerMustPayAtLeastTheTotal() {
        assertEquals(rejected(PaymentProblem.MUST_PAY_IN_FULL), invoice(PayerKind.CASH_CUSTOMER, 8100, 0, "80"))
        assertEquals(PaymentCheck.Ok(8100, 0), invoice(PayerKind.CASH_CUSTOMER, 8100, 0, "81"))
        val over = invoice(PayerKind.CASH_CUSTOMER, 8100, 0, "100") as PaymentCheck.Ok
        assertEquals(1900L, over.creditOnAccountCents)
    }

    @Test fun negativeAmountsAreRejectedForEveryone() {
        for (kind in PayerKind.entries) {
            assertEquals(rejected(PaymentProblem.NEGATIVE), invoice(kind, 8100, 0, "-1"))
        }
    }

    @Test fun textThatIsNotAnAmountIsRejected() {
        for (bad in listOf("", "abc", "12.345", "1,5", "--2", ".")) {
            assertEquals(rejected(PaymentProblem.NOT_AN_AMOUNT), invoice(PayerKind.CREDIT_CUSTOMER, 8100, 0, bad))
        }
    }

    @Test fun twelveFiftyParsesToCentsWithoutFloatingPoint() {
        val ok = invoice(PayerKind.CREDIT_CUSTOMER, 8100, 0, "12.50") as PaymentCheck.Ok
        assertEquals(1250L, ok.amountCents)
        assertEquals(6850L, ok.balanceAfterCents)
    }

    @Test fun aStandalonePaymentMustBeMoreThanZero() {
        assertEquals(rejected(PaymentProblem.ZERO), checkStandalonePayment(12000, "0"))
        assertEquals(rejected(PaymentProblem.ZERO), checkStandalonePayment(12000, "0.00"))
        assertEquals(rejected(PaymentProblem.NEGATIVE), checkStandalonePayment(12000, "-5"))
        assertEquals(rejected(PaymentProblem.NOT_AN_AMOUNT), checkStandalonePayment(12000, ""))
    }

    @Test fun aStandalonePaymentReducesTheBalanceAndMayOverpay() {
        assertEquals(PaymentCheck.Ok(5000, 7000), checkStandalonePayment(12000, "50"))
        val over = checkStandalonePayment(1000, "25") as PaymentCheck.Ok
        assertEquals(-1500L, over.balanceAfterCents)
        assertEquals(1500L, over.creditOnAccountCents)
    }
}
