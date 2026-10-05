package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.DemoCustomerIds
import com.mamre.billing.data.demo.DemoWorld
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.BillDraft
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.print.invoiceReceiptOf
import com.mamre.billing.print.layoutInvoiceReceipt
import com.mamre.billing.print.layoutPaymentReceipt
import com.mamre.billing.print.paymentReceiptOf
import com.mamre.billing.ui.worker.CustomerRow
import com.mamre.billing.ui.worker.PaymentUi
import com.mamre.billing.ui.worker.WorkerCatalog
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Change set D4 on the real database: the per-customer "Corporate account" flag. Hidden from the Sales area and from bills,
 * still tracked in the Admin area (Doc 1 s4.1, Doc 2 I-16, AT-22).
 */
@RunWith(RobolectricTestRunner::class)
class CorporateAccountTest {
    private lateinit var w: World
    private val admin get() = w.admin
    private val banned = listOf("Balance", "TOTAL DUE", "THIS MONTH", "Brought forward")

    @Before fun open() {
        w = runBlocking { DemoWorld.open() }
    }

    @After fun close() {
        w.db.close()
    }

    private fun form(c: AdminCustomer, corporate: Boolean) = CustomerForm(
        c.name, c.typeId, c.phone, c.address, c.paymentMode, c.notes, c.isActive, location = c.location, isCorporate = corporate,
    )

    private fun customer(id: String, corporate: Boolean) = Customer(id, "Name", "t", "", "", PaymentMode.CREDIT, true, "Loc", corporate)

    private suspend fun salesCatalog() = WorkerCatalog(w.customers, w.prices).load()

    // ------------------------------------------------------------------ the seed and the Admin form

    @Test fun bothFreshMartStoresAreCorporateAndOnCreditAndNobodyElseIs() = runBlocking {
        val rows = admin.customers()
        for (r in rows) assertEquals(r.name, r.name == "FreshMart", r.isCorporate)
        assertTrue(rows.filter { it.isCorporate }.all { it.paymentMode == PaymentMode.CREDIT })
        assertEquals(2, rows.count { it.isCorporate })
    }

    @Test fun theFlagIsOffByDefaultOnANewCustomer() = runBlocking {
        val c = admin.addCustomer(CustomerForm("Green Leaf", ReferenceIds.TYPE_SHOP, "", "", PaymentMode.CREDIT, "", true, location = "Plano"))
        assertFalse(c.isCorporate)
        assertFalse(w.customers.customer(c.id)!!.isCorporate)
    }

    @Test fun theAdminSwitchesTheFlagAndTheChangeIsLogged() = runBlocking {
        val spice = admin.customer(SeedIds.SPICE_GARDEN)!!
        val on = admin.updateCustomer(spice.id, form(spice, corporate = true))
        assertTrue(on.isCorporate)
        val log = w.newLog().single()
        assertTrue(log.before.contains("not corporate") && log.after.contains("corporate account"))
        assertFalse(admin.updateCustomer(spice.id, form(on, corporate = false)).isCorporate)
    }

    // ------------------------------------------------------------------ the Sales area sees the flag at once (there is no sync now)

    @Test fun theFlagIsSeenByTheSalesAreaAtOnceAndBackOffAgain() = runBlocking {
        val spice = admin.customer(SeedIds.SPICE_GARDEN)!!
        assertFalse(salesCatalog().customers.first { it.id == spice.id }.isCorporate)
        admin.updateCustomer(spice.id, form(spice, corporate = true))
        assertTrue(salesCatalog().customers.first { it.id == spice.id }.isCorporate)
        admin.updateCustomer(spice.id, form(admin.customer(spice.id)!!, corporate = false))
        assertFalse(salesCatalog().customers.first { it.id == spice.id }.isCorporate)
    }

    @Test fun theSeededCorporateFlagsAreInTheSalesAreasCatalog() = runBlocking {
        val flagged = salesCatalog().customers.filter { it.isCorporate }
        assertEquals(setOf("FreshMart - Downtown", "FreshMart - Westside"), flagged.map { "${it.name} - ${it.location}" }.toSet())
    }

    // ------------------------------------------------------------------ the Admin side still tracks the balance

    @Test fun theAdminLedgerBalanceOfACorporateCustomerIsNotChangedByTheFlag() = runBlocking {
        val id = DemoCustomerIds.FRESHMART_DOWNTOWN
        val before = Triple(admin.customerBalance(id), admin.balances().first { it.customerId == id }, admin.customerSummary(id, YearMonth.of(2026, 9)))
        assertTrue("FreshMart must owe something for this test to mean anything", before.first > 0)
        val c = admin.customer(id)!!
        admin.updateCustomer(id, form(c, corporate = false))
        admin.updateCustomer(id, form(admin.customer(id)!!, corporate = true))
        assertEquals(before.first, admin.customerBalance(id))
        assertEquals(before.second, admin.balances().first { it.customerId == id })
        assertEquals(before.third, admin.customerSummary(id, YearMonth.of(2026, 9)))
        // The Admin report still counts them: the balances list holds both stores.
        assertTrue(admin.balances().count { it.customerName.startsWith("FreshMart") } == 2)
    }

    @Test fun aNormalCustomersAdminBalanceIsAlsoUnchangedByTurningTheFlagOn() = runBlocking {
        val id = SeedIds.SPICE_GARDEN
        val before = admin.customerBalance(id)
        admin.updateCustomer(id, form(admin.customer(id)!!, corporate = true))
        assertEquals(before, admin.customerBalance(id))
    }

    @Test fun theSalesAreasBalanceIsNeverOfferedForACorporateAccountButTheAdminDataHoldsIt() = runBlocking {
        val state = w.sales.state()
        val id = DemoCustomerIds.FRESHMART_DOWNTOWN
        assertEquals(null, state.balanceShownInSales(id, isCorporate = true))
        assertTrue(admin.customerBalance(id) > 0)
        assertEquals(admin.customerBalance(id), state.balanceOf(id)) // the same ledger, shown only in the Admin area
    }

    // ------------------------------------------------------------------ the bill and the receipt

    private suspend fun corporateBill(paid: Long = 0, method: PaymentMethod? = null) = w.makeBill(
        BillDraft("d-corp", DemoCustomerIds.FRESHMART_DOWNTOWN, listOf(InvoiceLine(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", 70, 270)), paid, method),
    )

    @Test fun aCorporateBillContainsNoBalanceTotalDueMonthSummaryOrBroughtForward() = runBlocking {
        val invoice = corporateBill()
        assertTrue(invoice.isCorporate) // the flag is a snapshot on the invoice
        val ledger = w.sales.state().ledgerOf(DemoCustomerIds.FRESHMART_DOWNTOWN)
        // Even when the caller asks for the month summary of a credit customer:
        val text = layoutInvoiceReceipt(invoiceReceiptOf(invoice, ledger, showMonthSummary = true, header = com.mamre.billing.domain.model.BusinessHeader())).joinToString("\n")
        for (word in banned) assertFalse("$word was printed on a corporate bill", text.contains(word, ignoreCase = true))
        assertTrue(text.contains("CREDIT") && text.contains("Received by:") && text.contains("Sign and stamp"))
    }

    @Test fun aReprintOfACorporateBillStillHasNoBalanceEvenIfTheFlagIsLaterSwitchedOff() = runBlocking {
        val invoice = corporateBill()
        val c = admin.customer(DemoCustomerIds.FRESHMART_DOWNTOWN)!!
        admin.updateCustomer(c.id, form(c, corporate = false))
        assertFalse(salesCatalog().customers.first { it.id == c.id }.isCorporate) // the catalog now says normal...
        val saved = w.sales.state().invoices.first { it.id == invoice.id }
        assertTrue(saved.isCorporate) // ...the bill as it was does not change
        val reprint = layoutInvoiceReceipt(invoiceReceiptOf(saved, w.sales.state().ledgerOf(c.id), true, com.mamre.billing.domain.model.BusinessHeader(), duplicate = true))
        assertTrue(reprint.none { it.contains("Balance") || it.contains("THIS MONTH") })
    }

    @Test fun aNormalCreditCustomerIsUnaffected() = runBlocking {
        val invoice = w.makeBill(
            BillDraft("d-normal", DemoCustomerIds.SPICE_GARDEN, listOf(InvoiceLine(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", 10, 240)), 1000, PaymentMethod.CASH),
        )
        assertFalse(invoice.isCorporate)
        val state = w.sales.state()
        val text = layoutInvoiceReceipt(
            invoiceReceiptOf(invoice, state.ledgerOf(DemoCustomerIds.SPICE_GARDEN), true, com.mamre.billing.domain.model.BusinessHeader(), openingBalanceCents = state.openingOf(DemoCustomerIds.SPICE_GARDEN)),
        ).joinToString("\n")
        for (word in listOf("Balance after", "THIS MONTH", "Brought forward", "TOTAL DUE")) assertTrue("$word is missing", text.contains(word))
        assertFalse(text.contains("Received by:"))
    }

    @Test fun aCorporatePaymentReceiptHasNoBalance() = runBlocking {
        val id = DemoCustomerIds.FRESHMART_DOWNTOWN
        val before = w.sales.state().balanceOf(id)
        val payment = w.recordPayment(w.payment(id, 5000, PaymentMethod.CHECK))
        assertTrue(payment.isCorporate)
        val state = w.sales.state()
        val lines = layoutPaymentReceipt(paymentReceiptOf(payment, state.balanceOf(id), com.mamre.billing.domain.model.BusinessHeader()))
        assertTrue(lines.none { it.contains("Balance") })
        // The ledger still tracks it even though nothing in the Sales area shows it.
        assertEquals(before - 5000L, state.balanceOf(id))
    }

    // ------------------------------------------------------------------ what the Sales screens decide to show

    @Test fun theCustomerCardShowsABalanceOnlyForANormalCreditCustomer() {
        fun row(c: Customer) = CustomerRow(c, "Shop", 12_000)
        assertTrue(row(customer("a", corporate = false)).showsBalance)
        assertFalse(row(customer("b", corporate = true)).showsBalance)
        assertFalse(row(customer("c", corporate = false).copy(paymentMode = PaymentMode.CASH)).showsBalance)
    }

    @Test fun theRecordPaymentFormKnowsACorporateCustomerAndHidesItsBalance() {
        assertTrue(PaymentUi(customer = customer("a", corporate = true)).isCorporate)
        assertFalse(PaymentUi(customer = customer("b", corporate = false)).isCorporate)
        assertFalse(PaymentUi().isCorporate)
    }
}
