package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.SharedPriceTable
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Balances and ageing include the Catering type; the buckets add up to the balance (Doc 1 s6.2, s11). */
class BalancesCateringTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val api = FakeAdminApi(Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone), SharedPriceTable.seeded(today))

    @Test fun everyCustomerIsListedOnceIncludingCatering() = runTest {
        val rows = api.balances()
        assertEquals(api.customers().map { it.id }.toSet(), rows.map { it.customerId }.toSet())
        assertEquals(rows.size, rows.map { it.customerId }.toSet().size)
        val royal = rows.single { it.customerId == SeedIds.ROYAL_BANQUETS }
        assertEquals("Catering", royal.typeName)
    }

    @Test fun agedBucketsAddUpToThePositiveBalance() = runTest {
        for (r in api.balances()) {
            if (r.balanceCents > 0) assertEquals(r.customerName, r.balanceCents, r.currentCents + r.over30Cents + r.over60Cents)
            assertTrue(r.currentCents >= 0 && r.over30Cents >= 0 && r.over60Cents >= 0)
        }
    }

    @Test fun balancesAreSortedHighestFirst() = runTest {
        val b = api.balances().map { it.balanceCents }
        assertEquals(b.sortedDescending(), b)
    }

    @Test fun netSalesByCustomerTypeIncludesCatering() = runTest {
        val d = api.dashboard(java.time.YearMonth.of(2026, 9))
        assertEquals(listOf("Restaurant", "Shop", "Retail", "Catering"), d.salesByCustomerType.map { it.label })
        assertEquals(d.netSalesCents, d.salesByCustomerType.sumOf { it.cents })
    }
}
