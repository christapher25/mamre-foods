package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.SharedPriceTable
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set C1: four customer types, Catering seeded, flags defaults, one id per customer. */
class CateringSeedTest {
    private val today = LocalDate.of(2026, 10, 2)

    @Test fun thereAreFourTypesWithCateringAndRetailEditableByDefault() {
        val table = SharedPriceTable.seeded(today)
        assertEquals(listOf("Restaurant", "Shop", "Retail", "Catering"), table.types().map { it.name })
        assertEquals(
            mapOf("Restaurant" to false, "Shop" to false, "Retail" to true, "Catering" to true),
            table.types().associate { it.name to it.workerCanEditPrice },
        )
    }

    @Test fun cateringHasACustomerWithInvoicesAndPricesForEveryProduct() = runTest {
        val api = FakeAdminApi(prices = SharedPriceTable.seeded(today), clock = java.time.Clock.fixed(
            today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault(),
        ))
        val royal = api.customers().single { it.id == SeedIds.ROYAL_BANQUETS }
        assertEquals("Catering", royal.typeName)
        assertTrue(api.invoices().any { it.customerId == royal.id && it.typeName == "Catering" })
        val matrix = api.priceMatrix()
        for (p in listOf(DemoIds.FRESH, DemoIds.CHAPATHI)) {
            assertTrue(matrix.current(p, DemoIds.CATERING_TYPE, today) != null)
        }
        val d = api.dashboard(YearMonth.of(2026, 9))
        assertTrue(d.salesByCustomerType.any { it.label == "Catering" && it.cents > 0 })
    }

    @Test fun aWalkInFollowsTheRetailTypeInTheSeed() = runTest {
        val api = FakeAdminApi(prices = SharedPriceTable.seeded(today), clock = java.time.Clock.fixed(
            today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault(),
        ))
        val walkIns = api.invoices().filter { it.customerId == null }
        assertTrue(walkIns.isNotEmpty() && walkIns.all { it.typeName == "Retail" })
    }

    @Test fun theWorkerEditFlagBumpsTheVersionOnlyWhenItChanges() {
        val table = SharedPriceTable.seeded(today, baseVersion = 10)
        table.setWorkerCanEditPrice(DemoIds.RETAIL_TYPE, true) // already on
        assertEquals(10L, table.version)
        table.setWorkerCanEditPrice(DemoIds.RETAIL_TYPE, false)
        assertEquals(11L, table.version)
        assertFalse(table.types().first { it.id == DemoIds.RETAIL_TYPE }.workerCanEditPrice)
        assertEquals(listOf(DemoIds.RETAIL_TYPE), table.typesAfter(10).map { it.id })
    }

    @Test fun aNewCustomerNeedsAKnownTypeAndAUniqueId() {
        val table = SharedPriceTable.seeded(today, baseVersion = 10)
        val row = table.customers().first()
        try {
            table.addCustomer(row)
            org.junit.Assert.fail("duplicate id accepted")
        } catch (_: IllegalArgumentException) {
        }
        try {
            table.addCustomer(row.copy(id = "x", typeId = "nope"))
            org.junit.Assert.fail("unknown type accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(10L, table.version)
    }
}
