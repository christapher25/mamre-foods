package com.mamre.billing.data.admin

import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.SharedPriceTable
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A price set by the Admin shows in the worker's catalog at the next pull (owner change 5). */
class SharedPricesTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val worker = FakeApi(table)

    @Test fun anAdminPriceReachesTheWorkerCatalogOnTheNextPullFromTheOldCursor() = runTest {
        val token = worker.login("user1", "user1").access
        val first = worker.catalog(token, 0)
        admin.setDefaultPrice(DemoIds.FRESH, DemoIds.SHOP_TYPE, 333, LocalDate.of(2026, 11, 1), "Test Admin")
        val next = worker.catalog(token, first.cursor)
        assertTrue(next.cursor > first.cursor)
        val p = next.priceDefaults.single()
        assertEquals(333L, p.unitPriceCents)
        assertEquals("2026-11-01", p.effectiveFrom)
        assertEquals(DemoIds.FRESH, p.productId)
        assertTrue(next.products.isEmpty() && next.customers.isEmpty())
    }

    @Test fun withoutAnAdminChangeTheSecondPullIsEmpty() = runTest {
        val token = worker.login("user1", "user1").access
        val first = worker.catalog(token, 0)
        assertTrue(worker.catalog(token, first.cursor).priceDefaults.isEmpty())
    }

    @Test fun aRefusedAdminPriceDoesNotReachTheWorker() = runTest {
        val token = worker.login("user1", "user1").access
        val first = worker.catalog(token, 0)
        try {
            admin.setDefaultPrice(DemoIds.FRESH, DemoIds.SHOP_TYPE, 333, LocalDate.of(2025, 10, 1), "Test Admin")
            org.junit.Assert.fail("expected the date rule")
        } catch (_: AdminRuleException) {
        }
        assertEquals(first.cursor, worker.catalog(token, first.cursor).cursor)
    }
}
