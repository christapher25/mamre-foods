package com.mamre.billing.data.admin

import com.mamre.billing.data.FakeCustomerDao
import com.mamre.billing.data.FakeCustomerTypeDao
import com.mamre.billing.data.FakePriceDefaultDao
import com.mamre.billing.data.FakePriceOverrideDao
import com.mamre.billing.data.FakeProductDao
import com.mamre.billing.data.FakeSettingDao
import com.mamre.billing.data.FakeSyncStateDao
import com.mamre.billing.data.FakeTransactionRunner
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.InvoiceDraft
import com.mamre.billing.data.demo.PaymentDraft
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.print.invoiceReceiptOf
import com.mamre.billing.print.layoutInvoiceReceipt
import com.mamre.billing.print.layoutPaymentReceipt
import com.mamre.billing.print.paymentReceiptOf
import com.mamre.billing.ui.worker.CustomerRow
import com.mamre.billing.ui.worker.PaymentUi
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set D4: the per-customer "Corporate account" flag. Hidden from the salesman side and from bills, still tracked. */
class CorporateAccountTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val api = FakeApi(table)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeSettingDao(), FakeTransactionRunner(),
        CatalogRemote { cursor -> api.catalog(api.login("user1", "user1").access, cursor) },
    )
    private val store = DemoStore(clock, DemoSeed.state())
    private val banned = listOf("Balance", "TOTAL DUE", "THIS MONTH", "Brought forward")

    private fun form(c: com.mamre.billing.domain.admin.AdminCustomer, corporate: Boolean) = CustomerForm(
        c.name, c.typeId, c.phone, c.address, c.paymentMode, c.notes, c.isActive, location = c.location, isCorporate = corporate,
    )

    private fun customer(id: String, corporate: Boolean) = Customer(
        id, "Name", "t", "", "", PaymentMode.CREDIT, true, "Loc", corporate,
    )

    // ------------------------------------------------------------------ the seed and the Admin form

    @Test fun bothFreshMartStoresAreCorporateAndOnCreditAndNobodyElseIs() = runTest {
        val rows = table.customers()
        for (r in rows) assertEquals(r.name, r.name == "FreshMart", r.isCorporate)
        assertTrue(rows.filter { it.isCorporate }.all { it.paymentMode == PaymentMode.CREDIT })
        assertEquals(2, rows.count { it.isCorporate })
        assertEquals(rows.filter { it.isCorporate }.map { it.id }.toSet(), admin.customers().filter { it.isCorporate }.map { it.id }.toSet())
    }

    @Test fun theFlagIsOffByDefaultOnANewCustomer() = runTest {
        val c = admin.addCustomer(CustomerForm("Green Leaf", DemoIds.SHOP_TYPE, "", "", PaymentMode.CREDIT, "", true, location = "Plano"), "Test Admin")
        assertFalse(c.isCorporate)
        assertFalse(table.customers().first { it.id == c.id }.isCorporate)
    }

    @Test fun theAdminSwitchesTheFlagAndTheChangeIsLogged() = runTest {
        val spice = admin.customer(SeedIds.SPICE_GARDEN)!!
        val on = admin.updateCustomer(spice.id, form(spice, corporate = true), "Test Admin")
        assertTrue(on.isCorporate)
        val log = admin.changeLog.value.single()
        assertTrue(log.before.contains("not corporate") && log.after.contains("corporate account"))
        assertFalse(admin.updateCustomer(spice.id, form(on, corporate = false), "Test Admin").isCorporate)
    }

    // ------------------------------------------------------------------ the catalog and Sync now

    @Test fun theFlagReachesTheSalesmanOnlyAfterSyncNow() = runTest {
        repo.refresh()
        val spice = admin.customer(SeedIds.SPICE_GARDEN)!!
        assertFalse(repo.customer(spice.id)!!.isCorporate)
        admin.updateCustomer(spice.id, form(spice, corporate = true), "Test Admin")
        assertFalse(repo.customer(spice.id)!!.isCorporate) // not before the sync
        repo.refresh()
        assertTrue(repo.customer(spice.id)!!.isCorporate)
        // And back off, again only at the next sync.
        admin.updateCustomer(spice.id, form(admin.customer(spice.id)!!, corporate = false), "Test Admin")
        assertTrue(repo.customer(spice.id)!!.isCorporate)
        repo.refresh()
        assertFalse(repo.customer(spice.id)!!.isCorporate)
    }

    @Test fun theSeededCorporateFlagsAreInTheSalesmansCatalogAfterTheFirstSync() = runTest {
        repo.refresh()
        val flagged = repo.activeCustomers().filter { it.isCorporate }
        assertEquals(setOf("FreshMart - Downtown", "FreshMart - Westside"), flagged.map { "${it.name} - ${it.location}" }.toSet())
    }

    // ------------------------------------------------------------------ the Admin side still tracks the balance

    @Test fun theAdminLedgerBalanceOfACorporateCustomerIsNotChangedByTheFlag() = runTest {
        val id = DemoIds.FRESHMART_DOWNTOWN
        val before = Triple(admin.customerBalance(id), admin.balances().first { it.customerId == id }, admin.customerSummary(id, java.time.YearMonth.of(2026, 9)))
        assertTrue("FreshMart must owe something for this test to mean anything", before.first > 0)
        val c = admin.customer(id)!!
        admin.updateCustomer(id, form(c, corporate = false), "Test Admin")
        admin.updateCustomer(id, form(admin.customer(id)!!, corporate = true), "Test Admin")
        assertEquals(before.first, admin.customerBalance(id))
        assertEquals(before.second, admin.balances().first { it.customerId == id })
        assertEquals(before.third, admin.customerSummary(id, java.time.YearMonth.of(2026, 9)))
        // The Admin report still counts them: the balances list holds both stores.
        assertTrue(admin.balances().count { it.customerName.startsWith("FreshMart") } == 2)
    }

    @Test fun aNormalCustomersAdminBalanceIsAlsoUnchangedByTurningTheFlagOn() = runTest {
        val id = SeedIds.SPICE_GARDEN
        val before = admin.customerBalance(id)
        admin.updateCustomer(id, form(admin.customer(id)!!, corporate = true), "Test Admin")
        assertEquals(before, admin.customerBalance(id))
    }

    // ------------------------------------------------------------------ the bill and the receipt

    private fun corporateDraft(paid: Long = 0, method: PaymentMethod? = null) = InvoiceDraft(
        "d-corp", DemoIds.FRESHMART_DOWNTOWN, "FreshMart", "Shop", "W1",
        listOf(InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", 70, 250)), paid, method,
        salesmanName = "Rajesh", customerLocation = "Downtown", isCorporate = true,
    )

    @Test fun aCorporateBillContainsNoBalanceTotalDueMonthSummaryOrBroughtForward() {
        val invoice = store.confirmInvoice(corporateDraft())
        assertTrue(invoice.isCorporate) // the flag is a snapshot on the invoice
        val ledger = store.state.value.ledgerOf(DemoIds.FRESHMART_DOWNTOWN)
        // Even when the caller asks for the month summary of a credit customer:
        val text = layoutInvoiceReceipt(invoiceReceiptOf(invoice, ledger, showMonthSummary = true)).joinToString("\n")
        for (word in banned) assertFalse("$word was printed on a corporate bill", text.contains(word, ignoreCase = true))
        assertTrue(text.contains("CREDIT") && text.contains("Received by:") && text.contains("Sign and stamp"))
    }

    @Test fun aReprintOfACorporateBillStillHasNoBalanceEvenIfTheFlagIsLaterSwitchedOff() = runTest {
        val invoice = store.confirmInvoice(corporateDraft())
        repo.refresh()
        val c = admin.customer(DemoIds.FRESHMART_DOWNTOWN)!!
        admin.updateCustomer(c.id, form(c, corporate = false), "Test Admin")
        repo.refresh()
        assertFalse(repo.customer(c.id)!!.isCorporate) // the catalog now says normal...
        val reprint = layoutInvoiceReceipt(invoiceReceiptOf(invoice, store.state.value.ledgerOf(c.id), true, duplicate = true))
        assertTrue(reprint.none { it.contains("Balance") || it.contains("THIS MONTH") }) // ...the bill as it was does not change
    }

    @Test fun aNormalCreditCustomerIsUnaffected() {
        val invoice = store.confirmInvoice(
            InvoiceDraft("d-normal", DemoIds.RESTAURANT, "Spice Garden", "Restaurant", "W1",
                listOf(InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", 10, 250)), 1000, PaymentMethod.CASH,
                salesmanName = "Rajesh", customerLocation = "Irving"),
        )
        assertFalse(invoice.isCorporate)
        val text = layoutInvoiceReceipt(invoiceReceiptOf(invoice, store.state.value.ledgerOf(DemoIds.RESTAURANT), true)).joinToString("\n")
        for (word in listOf("Balance after", "THIS MONTH", "Brought forward", "TOTAL DUE")) assertTrue("$word is missing", text.contains(word))
        assertFalse(text.contains("Received by:"))
    }

    @Test fun aCorporatePaymentReceiptHasNoBalance() {
        val payment = store.recordPayment(
            PaymentDraft("p-corp", DemoIds.FRESHMART_DOWNTOWN, "FreshMart", "W1", 5000, PaymentMethod.CHECK, "", "Rajesh", "Downtown", isCorporate = true),
        )
        val lines = layoutPaymentReceipt(paymentReceiptOf(payment, store.state.value.balanceOf(DemoIds.FRESHMART_DOWNTOWN)))
        assertTrue(lines.none { it.contains("Balance") })
        // The salesman side still tracks it: the worker ledger holds the balance even though nothing shows it.
        assertEquals(-5000L, store.state.value.balanceOf(DemoIds.FRESHMART_DOWNTOWN))
    }

    // ------------------------------------------------------------------ what the salesman's screens decide to show

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
