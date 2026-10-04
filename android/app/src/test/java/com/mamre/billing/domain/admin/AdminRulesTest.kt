package com.mamre.billing.domain.admin

import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.money.formatCompactCents
import com.mamre.billing.domain.worker.InvoiceStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminRulesTest {
    private val current = LocalDate.of(2026, 9, 1)

    private fun price(text: String, date: LocalDate?, currentDate: LocalDate? = current) =
        validateNewPrice(text, date, currentDate)

    // --- price edit validation (B4, B5; Doc 2 price history rule) ---

    @Test fun aValidPriceWithALaterDateIsAccepted() {
        val ok = price("2.95", LocalDate.of(2026, 10, 1)) as PriceCheck.Ok
        assertEquals(295L, ok.priceCents)
        assertEquals(LocalDate.of(2026, 10, 1), ok.effectiveFrom)
    }

    @Test fun thePriceMustBeAboveZero() {
        for (bad in listOf("0", "0.00", "-1")) {
            val r = price(bad, LocalDate.of(2026, 10, 1)) as PriceCheck.Invalid
            assertEquals(setOf(PriceProblem.PRICE_NOT_POSITIVE), r.problems)
        }
    }

    @Test fun aMissingOrUnreadablePriceIsRequired() {
        for (bad in listOf("", "abc", "2.999", "1,5")) {
            val r = price(bad, LocalDate.of(2026, 10, 1)) as PriceCheck.Invalid
            assertEquals(setOf(PriceProblem.PRICE_REQUIRED), r.problems)
        }
    }

    @Test fun theNewDateMustBeLaterThanTheCurrentRowsDate() {
        val same = price("2.95", current) as PriceCheck.Invalid
        assertEquals(setOf(PriceProblem.DATE_NOT_LATER), same.problems)
        val earlier = price("2.95", current.minusDays(1)) as PriceCheck.Invalid
        assertEquals(setOf(PriceProblem.DATE_NOT_LATER), earlier.problems)
        assertTrue(price("2.95", current.plusDays(1)) is PriceCheck.Ok)
    }

    @Test fun withNoCurrentRowAnyDateIsFine() {
        assertTrue(price("2.95", LocalDate.of(2020, 1, 1), currentDate = null) is PriceCheck.Ok)
    }

    @Test fun aMissingDateIsRequiredAndBothProblemsAreReportedTogether() {
        assertEquals(setOf(PriceProblem.DATE_REQUIRED), (price("2.95", null) as PriceCheck.Invalid).problems)
        val both = price("0", current) as PriceCheck.Invalid
        assertEquals(setOf(PriceProblem.PRICE_NOT_POSITIVE, PriceProblem.DATE_NOT_LATER), both.problems)
    }

    // --- void needs a reason (B2; Doc 1 s5.4) ---

    @Test fun aVoidNeedsAReasonAndKeepsItTrimmed() {
        assertNull(cleanVoidReason(""))
        assertNull(cleanVoidReason("   "))
        assertEquals("Wrong customer", cleanVoidReason("  Wrong customer "))
    }

    // --- forms ---

    private fun form(name: String = "Spice Garden", type: String = "t1") =
        CustomerForm(name, type, "", "", PaymentMode.CREDIT, "", true)

    @Test fun aCustomerNeedsANameAndAType() {
        assertTrue(validateCustomerForm(form()).isEmpty())
        assertEquals(setOf(CustomerProblem.NAME_REQUIRED), validateCustomerForm(form(name = "  ")))
        assertEquals(setOf(CustomerProblem.TYPE_REQUIRED), validateCustomerForm(form(type = "")))
        assertEquals(setOf(CustomerProblem.OPENING_NEGATIVE), validateCustomerForm(form().copy(openingBalanceCents = -1)))
    }

    @Test fun anExpenseNeedsACategoryADateAndAnAmountAboveZero() {
        val date = LocalDate.of(2026, 9, 5)
        val ok = validateExpense("cat", date, "45.50") as ExpenseCheck.Ok
        assertEquals(4550L, ok.amountCents)
        assertEquals(setOf(ExpenseProblem.CATEGORY_REQUIRED), (validateExpense(null, date, "10") as ExpenseCheck.Invalid).problems)
        assertEquals(setOf(ExpenseProblem.DATE_REQUIRED), (validateExpense("cat", null, "10") as ExpenseCheck.Invalid).problems)
        assertEquals(setOf(ExpenseProblem.AMOUNT_NOT_POSITIVE), (validateExpense("cat", date, "0") as ExpenseCheck.Invalid).problems)
        assertEquals(setOf(ExpenseProblem.AMOUNT_INVALID), (validateExpense("cat", date, "x") as ExpenseCheck.Invalid).problems)
    }

    @Test fun settingsNeedABusinessName() {
        assertTrue(validateSettings(BusinessSettings("Mamre Foods", "", "", "")).isEmpty())
        assertEquals(setOf(SettingsProblem.NAME_REQUIRED), validateSettings(BusinessSettings(" ", "", "", "")))
    }

    // --- invoice filters (B2) ---

    private fun inv(number: String, customer: String, id: String?, at: LocalDateTime, void: Boolean = false) = AdminInvoice(
        id = number, number = number, customerId = id, customerName = customer, typeName = "Restaurant",
        deviceCode = "W1", issuedAt = at, items = emptyList(), totalCents = 100,
        status = if (void) InvoiceStatus.VOID else InvoiceStatus.ACTIVE,
    )

    private val invoices = listOf(
        inv("MAM-W1-0001", "Spice Garden", "c1", LocalDateTime.of(2026, 8, 3, 9, 0)),
        inv("MAM-W1-0002", "Curry House", "c2", LocalDateTime.of(2026, 9, 3, 9, 0), void = true),
        inv("MAM-W1-0003", "Spice Garden", "c1", LocalDateTime.of(2026, 9, 10, 9, 0)),
    )

    @Test fun noFilterListsEverythingNewestFirst() {
        assertEquals(listOf("MAM-W1-0003", "MAM-W1-0002", "MAM-W1-0001"), filterInvoices(invoices, InvoiceFilter()).map { it.number })
    }

    @Test fun searchMatchesNumberOrCustomerIgnoringCase() {
        assertEquals(listOf("MAM-W1-0003", "MAM-W1-0001"), filterInvoices(invoices, InvoiceFilter(query = "spice")).map { it.number })
        assertEquals(listOf("MAM-W1-0002"), filterInvoices(invoices, InvoiceFilter(query = " w1-0002 ")).map { it.number })
        assertTrue(filterInvoices(invoices, InvoiceFilter(query = "zzz")).isEmpty())
    }

    @Test fun monthCustomerAndStatusFiltersCombine() {
        assertEquals(2, filterInvoices(invoices, InvoiceFilter(month = YearMonth.of(2026, 9))).size)
        assertEquals(2, filterInvoices(invoices, InvoiceFilter(customerId = "c1")).size)
        assertEquals(listOf("MAM-W1-0002"), filterInvoices(invoices, InvoiceFilter(status = InvoiceStatusFilter.VOID)).map { it.number })
        assertEquals(1, filterInvoices(invoices, InvoiceFilter(month = YearMonth.of(2026, 9), status = InvoiceStatusFilter.ACTIVE)).size)
    }

    // --- months and compact money (B1) ---

    @Test fun theMonthWindowEndsAtTheSelectedMonthAndStopsAtTheFirstMonthOfData() {
        val first = YearMonth.of(2026, 4)
        assertEquals((4..9).map { YearMonth.of(2026, it) }, monthWindow(YearMonth.of(2026, 9), 6, first))
        assertEquals(listOf(4, 5).map { YearMonth.of(2026, it) }, monthWindow(YearMonth.of(2026, 5), 6, first))
        assertEquals((5..10).map { YearMonth.of(2026, it) }, monthWindow(YearMonth.of(2026, 10), 6, first))
    }

    @Test fun monthLabelsAreReadable() {
        assertEquals("October 2026", monthLabel(YearMonth.of(2026, 10)))
        assertEquals("Oct", monthShort(YearMonth.of(2026, 10)))
    }

    @Test fun compactMoneyUsesIntegerArithmetic() {
        assertEquals("$0", formatCompactCents(0))
        assertEquals("$865", formatCompactCents(86_500))
        assertEquals("$1k", formatCompactCents(100_000))
        assertEquals("$12.3k", formatCompactCents(1_234_567))
        assertEquals("$12.4k", formatCompactCents(1_235_000))
        assertEquals("$16k", formatCompactCents(1_600_000))
        assertEquals("-$1.5k", formatCompactCents(-150_000))
    }
}
