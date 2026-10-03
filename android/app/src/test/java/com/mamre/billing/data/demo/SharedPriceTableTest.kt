package com.mamre.billing.data.demo

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one table linking the Admin's server stand-in and the worker's FakeApi catalog (DECISIONS 2026-10-03). */
class SharedPriceTableTest {
    private val today = LocalDate.of(2026, 10, 3)

    @Test fun seededTableHasPricesForEveryProductAndTypeAndNoZeroPrice() {
        val t = SharedPriceTable.seeded(today)
        assertTrue(t.all().all { it.unitPriceCents > 0 })
        for (p in listOf(DemoIds.FRESH, DemoIds.CHAPATHI)) {
            for (ty in listOf(DemoIds.RESTAURANT_TYPE, DemoIds.SHOP_TYPE, DemoIds.RETAIL_TYPE)) {
                assertTrue("$p $ty", t.all().any { it.productId == p && it.customerTypeId == ty })
            }
        }
    }

    @Test fun seededEntriesAreNotNewerThanTheStaticCatalogVersion() {
        val t = SharedPriceTable.seeded(today)
        assertEquals(SharedPriceTable.STATIC_CATALOG_VERSION, t.version)
        assertTrue(t.entriesAfter(SharedPriceTable.STATIC_CATALOG_VERSION).isEmpty())
        assertEquals(t.all().size, t.entriesAfter(0).size)
    }

    @Test fun addingAPriceBumpsTheVersionAndOnlyTheNewEntryIsAfterTheOldCursor() {
        val t = SharedPriceTable.seeded(today)
        val before = t.version
        val added = t.add(DemoIds.FRESH, DemoIds.SHOP_TYPE, 310, LocalDate.of(2026, 11, 1))
        assertEquals(before + 1, t.version)
        assertEquals(before + 1, added.syncVersion)
        assertEquals(listOf(added), t.entriesAfter(before))
        assertTrue(t.entriesAfter(t.version).isEmpty())
    }

    @Test fun historyIsKeptWhenAPriceIsAdded() {
        val t = SharedPriceTable.seeded(today)
        val count = t.all().size
        t.add(DemoIds.FRESH, DemoIds.SHOP_TYPE, 310, LocalDate.of(2026, 11, 1))
        assertEquals(count + 1, t.all().size)
    }

    @Test fun aPriceOfZeroOrLessIsRefused() {
        val t = SharedPriceTable.seeded(today)
        val count = t.all().size
        val version = t.version
        try {
            t.add(DemoIds.FRESH, DemoIds.SHOP_TYPE, 0, LocalDate.of(2026, 11, 1))
            org.junit.Assert.fail("expected refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals(count, t.all().size)
            assertEquals(version, t.version)
        }
    }
}
