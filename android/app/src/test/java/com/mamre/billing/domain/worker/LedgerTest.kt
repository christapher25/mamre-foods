package com.mamre.billing.domain.worker

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/** Doc 1 s6.5 worked example. These dates are fixed and do not depend on the demo seed. */
class LedgerTest {
    private val invoice1 = InvoiceEntry(LocalDate.of(2026, 10, 3), 12000)
    private val invoice2 = InvoiceEntry(LocalDate.of(2026, 10, 10), 9000)
    private val payment = PaymentEntry(LocalDate.of(2026, 10, 12), 15000, PaymentMethod.CASH)
    private val invoice3 = InvoiceEntry(LocalDate.of(2026, 10, 20), 6000)

    @Test fun section65BalanceAfterEachEvent() {
        assertEquals(12000L, ledgerBalance(0, listOf(invoice1)))
        assertEquals(21000L, ledgerBalance(0, listOf(invoice1, invoice2)))
        assertEquals(6000L, ledgerBalance(0, listOf(invoice1, invoice2, payment)))
        assertEquals(12000L, ledgerBalance(0, listOf(invoice1, invoice2, payment, invoice3)))
    }

    @Test fun orderOfEntriesDoesNotChangeTheBalance() {
        assertEquals(12000L, ledgerBalance(0, listOf(invoice3, payment, invoice2, invoice1)))
    }

    @Test fun openingBalanceIsAdded() {
        assertEquals(15000L, ledgerBalance(3000, listOf(invoice1)))
    }

    @Test fun aVoidInvoiceIsExcludedAndTheOthersAreUntouched() {
        val voided = invoice2.copy(isVoid = true)
        assertEquals(3000L, ledgerBalance(0, listOf(invoice1, voided, payment, invoice3)))
    }

    @Test fun aReturnCreditReducesTheBalance() {
        assertEquals(11000L, ledgerBalance(0, listOf(invoice1, CreditEntry(LocalDate.of(2026, 10, 4), 1000))))
    }

    @Test fun aReplacementIsNotAnEntryAtAll() {
        // Replacements never reach the ledger (Doc 1 s7.1): nothing to add, nothing changes.
        assertEquals(12000L, ledgerBalance(0, listOf(invoice1)))
    }

    @Test fun anOverpaymentMakesTheBalanceNegativeCreditOnAccount() {
        assertEquals(-3000L, ledgerBalance(0, listOf(invoice1, PaymentEntry(LocalDate.of(2026, 10, 5), 15000, PaymentMethod.ZELLE))))
    }

    @Test fun anEmptyLedgerIsTheOpeningBalance() {
        assertEquals(0L, ledgerBalance(0, emptyList()))
        assertEquals(500L, ledgerBalance(500, emptyList()))
    }

    @Test fun aMonthOpensWithEverythingBeforeItsFirstDay() {
        val all = listOf(invoice1, invoice2, payment, invoice3)
        assertEquals(0L, broughtForward(0, all, YearMonth.of(2026, 10)))
        assertEquals(12000L, broughtForward(0, all, YearMonth.of(2026, 11)))
        assertEquals(12000L, broughtForward(0, all, YearMonth.of(2026, 12)))
    }

    @Test fun anEntryOnTheFirstOfTheMonthBelongsToThatMonth() {
        val first = InvoiceEntry(LocalDate.of(2026, 11, 1), 500)
        assertEquals(0L, broughtForward(0, listOf(first), YearMonth.of(2026, 11)))
    }
}
