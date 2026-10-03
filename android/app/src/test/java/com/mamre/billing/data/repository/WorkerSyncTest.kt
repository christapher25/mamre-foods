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
    private val store = DemoStore()
    private val sync = WorkerSync(repo, store)

    private suspend fun shopFreshPrice(date: LocalDate): Long {
        val shop = repo.activeCustomers().first { it.id == DemoIds.SHOP }
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
