package com.mamre.billing.data.repository

import com.mamre.billing.data.FakeCustomerDao
import com.mamre.billing.data.FakeCustomerTypeDao
import com.mamre.billing.data.FakePriceDefaultDao
import com.mamre.billing.data.FakePriceOverrideDao
import com.mamre.billing.data.FakeProductDao
import com.mamre.billing.data.FakeSyncStateDao
import com.mamre.billing.data.FakeTransactionRunner
import com.mamre.billing.data.admin.FakeAdminApi
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.domain.pricing.PriceResult
import com.mamre.billing.domain.pricing.PriceSource
import com.mamre.billing.domain.pricing.resolvePrice
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner costing spec 7 and 8: "Sync now" pulls the catalog through the existing repository and cursor, so a
 * price the Admin set shows in the worker's catalog afterwards. The shared price table is the only link.
 */
class WorkerSyncTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val api = FakeApi(table)

    private val defaults = FakePriceDefaultDao()
    private var offline = false
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), defaults, FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeTransactionRunner(),
        CatalogRemote { cursor ->
            if (offline) throw IOException("no signal")
            api.catalog(api.login("user1", "user1").access, cursor)
        },
    )
    private val store = DemoStore(seed = DemoSeed.state())
    private val sync = WorkerSync(repo, store)

    private suspend fun shopFreshPrice(date: LocalDate): Long {
        val shop = repo.activeCustomers().first { it.id == "c-desi-grocers" } // a Shop customer with no override (Patel Mart has one)
        val fresh = repo.activeProducts().first { it.id == DemoIds.FRESH }
        return (resolvePrice(shop, fresh, date, repo.priceBook()) as PriceResult.Found).unitPriceCents
    }

    @Test fun theFirstSyncPullsTheWholeCatalogAndMovesTheCursor() = runTest {
        assertEquals(SyncOutcome.Done, sync.syncNow())
        assertEquals(300L, shopFreshPrice(today))
        assertEquals(table.version, repo.catalogCursor())
    }

    @Test fun aPriceSetByTheAdminAppearsInTheWorkerCatalogAfterSyncNow() = runTest {
        sync.syncNow()
        val cursor = repo.catalogCursor()
        admin.setDefaultPrice(DemoIds.FRESH, DemoIds.SHOP_TYPE, 333, LocalDate.of(2026, 11, 1), "Test Admin")
        // Before the sync the worker still has the old price.
        assertEquals(300L, shopFreshPrice(LocalDate.of(2026, 11, 2)))
        assertEquals(SyncOutcome.Done, sync.syncNow())
        assertEquals(333L, shopFreshPrice(LocalDate.of(2026, 11, 2)))
        assertEquals(300L, shopFreshPrice(today)) // the new price starts on its effective date
        assertTrue(repo.catalogCursor() > cursor)
    }

    @Test fun syncNowPullsFromTheStoredCursorAndASecondSyncChangesNothing() = runTest {
        sync.syncNow()
        val rows = defaults.rows.size
        val cursor = repo.catalogCursor()
        sync.syncNow()
        assertEquals(rows, defaults.rows.size)
        assertEquals(cursor, repo.catalogCursor())
    }

    @Test fun whenOfflineTheCatalogAndThePendingRecordsStayAsTheyWere() = runTest {
        sync.syncNow()
        val cursor = repo.catalogCursor()
        admin.setDefaultPrice(DemoIds.FRESH, DemoIds.SHOP_TYPE, 333, LocalDate.of(2026, 11, 1), "Test Admin")
        offline = true
        val outcome = sync.syncNow()
        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(cursor, repo.catalogCursor())
        assertEquals(300L, shopFreshPrice(LocalDate.of(2026, 11, 2)))
        offline = false
        assertEquals(SyncOutcome.Done, sync.syncNow())
        assertEquals(333L, shopFreshPrice(LocalDate.of(2026, 11, 2)))
    }
}

/** Change set C1: one shared catalog; what the Admin adds reaches the worker only through Sync now. */
class SharedCatalogSyncTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val api = FakeApi(table)
    private val types = FakeCustomerTypeDao()
    private val repo = CatalogRepository(
        FakeProductDao(), types, FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeTransactionRunner(),
        CatalogRemote { cursor -> api.catalog(api.login("user1", "user1").access, cursor) },
    )
    private val sync = WorkerSync(repo, DemoStore(seed = DemoSeed.state()))

    private fun form(name: String, type: String = DemoIds.CATERING_TYPE) = com.mamre.billing.domain.admin.CustomerForm(
        name, type, "555-0100", "1 Main St", com.mamre.billing.domain.model.PaymentMode.CREDIT, "", true, location = "Hall Road",
    )

    @Test fun aCustomerAddedByTheAdminAppearsInTheWorkerListAfterSyncNowAndNotBefore() = runTest {
        sync.syncNow()
        assertTrue(repo.activeCustomers().none { it.name == "Banquet Hall" })
        admin.addCustomer(form("Banquet Hall"), "Test Admin")
        assertTrue("not before the sync", repo.activeCustomers().none { it.name == "Banquet Hall" })
        sync.syncNow()
        val c = repo.activeCustomers().single { it.name == "Banquet Hall" }
        assertEquals(DemoIds.CATERING_TYPE, c.typeId)
    }

    @Test fun anEditedCustomerReachesTheWorkerAtTheNextSyncToo() = runTest {
        sync.syncNow()
        val id = DemoIds.RETAIL_CUSTOMER
        admin.updateCustomer(id, form("Rao Family Restaurant", DemoIds.RESTAURANT_TYPE), "Test Admin")
        assertEquals("Rao Family", repo.customer(id)!!.name)
        sync.syncNow()
        assertEquals("Rao Family Restaurant", repo.customer(id)!!.name)
        assertEquals(DemoIds.RESTAURANT_TYPE, repo.customer(id)!!.typeId)
    }

    @Test fun theWorkerCatalogHasFourTypesAndTheFlagsReachItWithTheFirstSync() = runTest {
        sync.syncNow()
        val byName = repo.priceBook().customerTypes.associateBy { it.name }
        assertEquals(setOf("Restaurant", "Shop", "Retail", "Catering"), byName.keys)
        assertEquals(
            mapOf("Restaurant" to false, "Shop" to false, "Retail" to true, "Catering" to true),
            byName.mapValues { it.value.workerCanEditPrice },
        )
    }

    @Test fun anOverridePriceSetByTheAdminReachesTheWorkerAfterSync() = runTest {
        sync.syncNow()
        val before = repo.priceBook().overrides.size
        admin.setOverride("c-desi-grocers", DemoIds.FRESH, 290, LocalDate.of(2026, 11, 1), "Loyalty", "Test Admin")
        assertEquals(before, repo.priceBook().overrides.size)
        sync.syncNow()
        val o = repo.priceBook().overrides.single { it.customerId == "c-desi-grocers" }
        assertEquals(290L, o.unitPriceCents)
        admin.clearOverride("c-desi-grocers", DemoIds.FRESH, "Test Admin")
        sync.syncNow()
        assertTrue(repo.priceBook().overrides.single { it.customerId == "c-desi-grocers" }.let { !it.isActive })
    }

    @Test fun everyCustomerExistsOnceWithOneIdOnBothSides() = runTest {
        val shared = table.customers()
        assertEquals(shared.size, shared.map { it.id }.toSet().size)
        assertEquals(shared.size, shared.map { it.name to it.location }.toSet().size) // name plus location is the unique pair (D2)
        assertEquals(shared.map { it.id }.toSet(), admin.customers().map { it.id }.toSet())
        sync.syncNow()
        assertEquals(shared.map { it.id }.toSet(), repo.activeCustomers().map { it.id }.toSet())
    }
}
