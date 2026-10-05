package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.DemoWorld
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.admin.InvoiceFilter
import com.mamre.billing.domain.admin.filterInvoices
import com.mamre.billing.ui.worker.WorkerCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Change set C3 on the real database: the per-type "Salesman can edit price" switch, its log, and the flags in the sample data. */
@RunWith(RobolectricTestRunner::class)
class PriceRulesTest {
    private lateinit var w: World
    private val admin get() = w.admin

    @Before fun open() {
        w = runBlocking { DemoWorld.open() }
    }

    @After fun close() {
        w.db.close()
    }

    private suspend fun salesFlags() = WorkerCatalog(w.customers, w.prices).load().types.associate { it.name to it.workerCanEditPrice }

    @Test fun theAdminSeesTheFlagsInThePriceMatrix() = runBlocking {
        val types = admin.priceMatrix().types.associate { it.name to it.workerCanEditPrice }
        assertEquals(mapOf("Restaurant" to false, "Shop" to false, "Retail" to true, "Catering" to true), types)
    }

    @Test fun switchingTheFlagIsLoggedAndTheSalesAreaSeesItAtOnce() = runBlocking {
        assertEquals(true, salesFlags()["Catering"])
        admin.setWorkerCanEditPrice(ReferenceIds.TYPE_CATERING, false)
        assertEquals(false, salesFlags()["Catering"])
        admin.setWorkerCanEditPrice(ReferenceIds.TYPE_RESTAURANT, true)
        assertEquals(true, salesFlags()["Restaurant"])
        val log = w.newLog() // newest first
        assertEquals(2, log.size)
        assertTrue(log.last().what.contains("Catering") && log.last().before.contains("can edit") && log.last().after.contains("cannot"))
        assertEquals(false, admin.priceMatrix().types.first { it.name == "Catering" }.workerCanEditPrice)
    }

    @Test fun anUnknownTypeOrNoChangeIsRefusedAndLeavesNoTrace() = runBlocking {
        try {
            admin.setWorkerCanEditPrice("nope", true)
            fail("unknown type accepted")
        } catch (_: AdminRuleException) {
        }
        try {
            admin.setWorkerCanEditPrice(ReferenceIds.TYPE_RETAIL, true) // already on
            fail("no change accepted")
        } catch (_: AdminRuleException) {
        }
        assertTrue(w.newLog().isEmpty())
    }

    @Test fun inTheDemoDataOnlyTypesThatMayEditEverChargedAChangedPrice() = runBlocking {
        val flags = admin.priceMatrix().types.associate { it.name to it.workerCanEditPrice }
        val changed = admin.invoices().filter { it.hasChangedPrice }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all { flags.getValue(it.typeName) })
        for (inv in admin.invoices().filter { flags[it.typeName] == false }) {
            assertTrue(inv.items.none { it.priceOverridden })
        }
        assertTrue(changed.any { it.typeName == "Catering" })
    }

    @Test fun theListPriceIsKeptNextToTheChargedPrice() = runBlocking {
        val item = admin.invoices().flatMap { it.items }.first { it.priceOverridden }
        assertTrue(item.listPriceCents > item.unitPriceCents) // the demo discounts are 5% off the list
        assertEquals(item.qtyPackets * item.unitPriceCents, item.lineTotalCents)
    }

    @Test fun theSalesFilterShowsOnlyInvoicesWithAChangedPrice() = runBlocking {
        val all = admin.invoices()
        val changed = filterInvoices(all, InvoiceFilter(priceChangedOnly = true))
        assertTrue(changed.isNotEmpty() && changed.all { it.hasChangedPrice })
        assertEquals(all.count { it.hasChangedPrice }, changed.size)
        assertTrue(filterInvoices(all, InvoiceFilter()).size == all.size)
        assertFalse(changed.size == all.size)
    }

    /** Replaces SharedPricesTest (an Admin price reaching the worker catalog at the next pull): one database, so the next bill uses it. */
    @Test fun aPriceSetByTheAdminIsUsedByTheNextBillAtOnce() = runBlocking {
        val corner = com.mamre.billing.data.demo.DemoCustomerIds.CORNER_SHOP // a Shop customer with no override on Fresh
        admin.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_SHOP, 333, w.today)
        val bill = w.makeBill(w.draft(corner, listOf(w.line(qty = 2, unit = 333))))
        assertEquals(666L, bill.totalCents)
    }

    @Test fun aRefusedAdminPriceDoesNotChangeWhatTheNextBillCosts() = runBlocking {
        val corner = com.mamre.billing.data.demo.DemoCustomerIds.CORNER_SHOP
        try {
            admin.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_SHOP, 333, w.today.minusYears(2))
            fail("expected the date rule")
        } catch (_: AdminRuleException) {
        }
        // The price list is unchanged: a bill at the old Shop price (3.00) is still the right one.
        assertEquals(300L, w.makeBill(w.draft(corner, listOf(w.line(qty = 1, unit = 300)))).totalCents)
    }
}
