package com.mamre.billing.data.admin

import com.mamre.billing.data.FakeCustomerDao
import com.mamre.billing.data.FakeCustomerTypeDao
import com.mamre.billing.data.FakePriceDefaultDao
import com.mamre.billing.data.FakePriceOverrideDao
import com.mamre.billing.data.FakeProductDao
import com.mamre.billing.data.FakeSyncStateDao
import com.mamre.billing.data.FakeTransactionRunner
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.data.repository.WorkerSync
import com.mamre.billing.domain.admin.InvoiceFilter
import com.mamre.billing.domain.admin.filterInvoices
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Change set C3 on the Admin side: the per-type "Worker can edit price" flag, its sync, its log, and the flags in the data. */
class PriceRulesTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val api = FakeApi(table)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeTransactionRunner(),
        CatalogRemote { cursor -> api.catalog(api.login("user1", "user1").access, cursor) },
    )
    private val sync = WorkerSync(repo, DemoStore(seed = DemoSeed.state()))

    private suspend fun flags() = repo.priceBook().customerTypes.associate { it.name to it.workerCanEditPrice }

    @Test fun theAdminSeesTheFlagsInThePriceMatrix() = runTest {
        val types = admin.priceMatrix().types.associate { it.name to it.workerCanEditPrice }
        assertEquals(mapOf("Restaurant" to false, "Shop" to false, "Retail" to true, "Catering" to true), types)
    }

    @Test fun switchingTheFlagIsLoggedAndReachesTheWorkerAtTheNextSyncOnly() = runTest {
        sync.syncNow()
        assertEquals(true, flags()["Catering"])
        admin.setWorkerCanEditPrice(DemoIds.CATERING_TYPE, false, "Test Admin")
        assertEquals("not before the sync", true, flags()["Catering"])
        sync.syncNow()
        assertEquals(false, flags()["Catering"])
        admin.setWorkerCanEditPrice(DemoIds.RESTAURANT_TYPE, true, "Test Admin")
        sync.syncNow()
        assertEquals(true, flags()["Restaurant"])
        val log = admin.changeLog.value
        assertEquals(2, log.size)
        assertTrue(log.last().what.contains("Catering") && log.last().before.contains("can edit") && log.last().after.contains("cannot"))
        assertEquals(false, admin.priceMatrix().types.first { it.name == "Catering" }.workerCanEditPrice)
    }

    @Test fun anUnknownTypeOrNoChangeIsRefusedAndLeavesNoTrace() = runTest {
        try {
            admin.setWorkerCanEditPrice("nope", true, "Test Admin")
            fail("unknown type accepted")
        } catch (_: AdminRuleException) {
        }
        try {
            admin.setWorkerCanEditPrice(DemoIds.RETAIL_TYPE, true, "Test Admin") // already on
            fail("no change accepted")
        } catch (_: AdminRuleException) {
        }
        assertTrue(admin.changeLog.value.isEmpty())
    }

    @Test fun inTheDemoDataOnlyTypesThatMayEditEverChargedAChangedPrice() = runTest {
        val flags = admin.priceMatrix().types.associate { it.name to it.workerCanEditPrice }
        val changed = admin.invoices().filter { it.hasChangedPrice }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all { flags.getValue(it.typeName) })
        for (inv in admin.invoices().filter { flags[it.typeName] == false }) {
            assertTrue(inv.items.none { it.priceOverridden })
        }
        assertTrue(changed.any { it.typeName == "Catering" })
    }

    @Test fun theListPriceIsKeptNextToTheChargedPrice() = runTest {
        val item = admin.invoices().flatMap { it.items }.first { it.priceOverridden }
        assertTrue(item.listPriceCents > item.unitPriceCents) // the demo discounts are 5% off the list
        assertEquals(item.qtyPackets * item.unitPriceCents, item.lineTotalCents)
    }

    @Test fun theSalesFilterShowsOnlyInvoicesWithAChangedPrice() = runTest {
        val all = admin.invoices()
        val changed = filterInvoices(all, InvoiceFilter(priceChangedOnly = true))
        assertTrue(changed.isNotEmpty() && changed.all { it.hasChangedPrice })
        assertEquals(all.count { it.hasChangedPrice }, changed.size)
        assertTrue(filterInvoices(all, InvoiceFilter()).size == all.size)
        assertFalse(changed.size == all.size)
    }
}
