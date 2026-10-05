package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.DemoWorld
import com.mamre.billing.data.local.World
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Balances and ageing include the Catering type; the buckets add up to the balance (Doc 1 s6.2, s11), on the real database. */
@RunWith(RobolectricTestRunner::class)
class BalancesCateringTest {
    private lateinit var w: World
    private val api get() = w.admin

    @Before fun open() {
        w = runBlocking { DemoWorld.open() }
    }

    @After fun close() {
        w.db.close()
    }

    @Test fun everyCustomerIsListedOnceIncludingCatering() = runBlocking {
        val rows = api.balances()
        assertEquals(api.customers().map { it.id }.toSet(), rows.map { it.customerId }.toSet())
        assertEquals(rows.size, rows.map { it.customerId }.toSet().size)
        val royal = rows.single { it.customerId == SeedIds.ROYAL_BANQUETS }
        assertEquals("Catering", royal.typeName)
    }

    @Test fun agedBucketsAddUpToThePositiveBalance() = runBlocking {
        for (r in api.balances()) {
            if (r.balanceCents > 0) assertEquals(r.customerName, r.balanceCents, r.currentCents + r.over30Cents + r.over60Cents)
            assertTrue(r.currentCents >= 0 && r.over30Cents >= 0 && r.over60Cents >= 0)
        }
    }

    @Test fun balancesAreSortedHighestFirst() = runBlocking {
        val b = api.balances().map { it.balanceCents }
        assertEquals(b.sortedDescending(), b)
    }

    @Test fun netSalesByCustomerTypeIncludesCatering() = runBlocking {
        val d = api.dashboard(YearMonth.of(2026, 9))
        assertEquals(listOf("Restaurant", "Shop", "Retail", "Catering"), d.salesByCustomerType.map { it.label })
        assertEquals(d.netSalesCents, d.salesByCustomerType.sumOf { it.cents })
    }
}
