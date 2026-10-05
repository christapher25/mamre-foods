package com.mamre.billing.domain.books

import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.InvoiceItem
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.InvoiceStatus
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AT-1, AT-7 (Doc 1 s6.5, s5.4): the ledger and the oldest-first allocation are derived from bills and payments, because
 * there is no allocation table in version 1 (owner decision). The numbers are the Doc 1 s6.5 worked example.
 */
class BooksLogicTest {
    private val today = LocalDate.of(2026, 10, 25)
    private val customerId = "c1"

    private fun customer(opening: Long = 0) = AdminCustomer(
        id = customerId, name = "Spice Garden", typeId = "t1", typeName = "Restaurant", phone = "", address = "",
        paymentMode = PaymentMode.CREDIT, notes = "", isActive = true, openingBalanceCents = opening, location = "Irving",
    )

    private fun bill(id: String, day: Int, cents: Long, void: Boolean = false) = AdminInvoice(
        id = id, number = "MAM-W1-000$id", customerId = customerId, customerName = "Spice Garden", typeName = "Restaurant",
        deviceCode = "W1", issuedAt = LocalDateTime.of(2026, 10, day, 9, 0),
        items = listOf(InvoiceItem("p1", "Mamre Chapathi", 1, cents, cents)), totalCents = cents,
        status = if (void) InvoiceStatus.VOID else InvoiceStatus.ACTIVE, voidReason = if (void) "typo" else null,
    )

    private fun payment(id: String, day: Int, cents: Long) = AdminPayment(
        id = id, receiptNumber = "RCP-W1-000$id", customerId = customerId, customerName = "Spice Garden",
        date = LocalDate.of(2026, 10, day), amountCents = cents, method = PaymentMethod.CASH, note = "",
    )

    private fun books(invoices: List<AdminInvoice>, payments: List<AdminPayment>, opening: Long = 0) = BooksSnapshot(
        types = listOf(AdminCustomerType("t1", "Restaurant")),
        products = emptyList(),
        customers = listOf(customer(opening)),
        invoices = invoices,
        payments = payments,
        paymentInvoiceIds = emptyMap(),
        returns = emptyList(),
        categories = emptyList(),
        expenses = emptyList(),
        overrideNotes = emptyMap(),
        materials = emptyList(),
        recipes = emptyMap(),
        purchases = emptyList(),
        damage = emptyList(),
        openingStock = emptyMap(),
        wastageBp = 200,
        settings = BusinessSettings("", "", "", ""),
        today = today,
    )

    private val example = books(
        invoices = listOf(bill("1", 3, 12_000), bill("2", 10, 9_000), bill("3", 20, 6_000)),
        payments = listOf(payment("1", 12, 15_000)),
    )

    @Test fun theDoc1Section65ExampleClosesAt120Dollars() {
        assertEquals(12_000L, BooksLogic.balance(example, customerId))
    }

    @Test fun theAllocationIsOldestFirstBillOneIsPaidAndBillTwoOwes60Dollars() {
        val a = BooksLogic.allocate(example, customerId)
        assertEquals(0L, a.dueByInvoice.getValue("1"))
        assertEquals(6_000L, a.dueByInvoice.getValue("2"))
        assertEquals(6_000L, a.dueByInvoice.getValue("3"))
        assertEquals(listOf(12_000L, 3_000L), listOf("1", "2").map { id -> a.appliedByInvoice.getValue(id).sumOf { it.amountCents } })
        assertEquals(0L, a.creditOnAccountCents)
    }

    @Test fun theBillDetailShowsTheAmountStillDue() {
        assertEquals(0L, BooksLogic.invoiceDetail(example, "1")!!.amountDueCents)
        assertEquals(6_000L, BooksLogic.invoiceDetail(example, "2")!!.amountDueCents)
    }

    @Test fun aVoidedBillIsLeftOutOfTheBalanceAndTheAllocation() {
        val s = books(
            invoices = listOf(bill("1", 3, 12_000), bill("2", 10, 9_000, void = true), bill("3", 20, 6_000)),
            payments = listOf(payment("1", 12, 15_000)),
        )
        assertEquals(3_000L, BooksLogic.balance(s, customerId))
        val a = BooksLogic.allocate(s, customerId)
        assertEquals(setOf("1", "3"), a.dueByInvoice.keys)
        assertEquals(0L, a.dueByInvoice.getValue("1"))
        assertEquals(3_000L, a.dueByInvoice.getValue("3"))
    }

    @Test fun anOverpaymentBecomesCreditOnAccountAndTheBalanceGoesNegative() {
        val s = books(listOf(bill("1", 3, 5_000)), listOf(payment("1", 4, 8_000)))
        assertEquals(-3_000L, BooksLogic.balance(s, customerId))
        assertEquals(3_000L, BooksLogic.allocate(s, customerId).creditOnAccountCents)
    }

    @Test fun anOpeningBalanceIsThePayableOldestThingAndAgesAsOver60Days() {
        val s = books(listOf(bill("1", 20, 4_000)), listOf(payment("1", 21, 1_000)), opening = 2_500)
        val row = BooksLogic.balances(s).single()
        assertEquals(5_500L, row.balanceCents)
        // The payment goes to the opening balance first, then the bill (oldest first).
        assertEquals(1_500L, row.over60Cents)
        assertEquals(4_000L, row.currentCents)
    }

    @Test fun ageingPutsOldBillsInTheirBuckets() {
        val s = books(
            invoices = listOf(
                AdminInvoice("o", "MAM-W1-0001", customerId, "Spice Garden", "Restaurant", "W1", LocalDateTime.of(2026, 7, 1, 9, 0), emptyList(), 1_000, InvoiceStatus.ACTIVE),
                AdminInvoice("m", "MAM-W1-0002", customerId, "Spice Garden", "Restaurant", "W1", LocalDateTime.of(2026, 9, 5, 9, 0), emptyList(), 2_000, InvoiceStatus.ACTIVE),
                bill("n", 20, 4_000),
            ),
            payments = emptyList(),
        )
        val row = BooksLogic.balances(s).single()
        assertEquals(1_000L, row.over60Cents)
        assertEquals(2_000L, row.over30Cents)
        assertEquals(4_000L, row.currentCents)
    }
}
