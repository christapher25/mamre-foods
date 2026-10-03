package com.mamre.billing.data.demo

import java.time.Instant
import java.time.LocalDate

/** One selling price (Doc 2 s4.2 PriceDefault) with the sync_version it was last changed at. Sell prices only. */
data class SharedPriceRow(
    val id: String,
    val productId: String,
    val customerTypeId: String,
    val unitPriceCents: Long,
    val effectiveFrom: LocalDate,
    val syncVersion: Long,
)

/**
 * DEMO DATA. The ONE thing the Admin's server stand-in and the worker's FakeApi share: the default
 * selling prices (owner decision). The Admin writes here; the worker's catalog pull reads from here,
 * so a price set by the Admin reaches the worker at its next Sync now. Everything else in the two demo
 * data sets is separate. It holds no cost data, so nothing here can leak cost to a worker (Doc 2 I-8).
 *
 * Versions only go up. A new process starts from a higher base than the last one, so a worker that
 * synced during an earlier run still pulls the fresh table instead of seeing "nothing new".
 */
class SharedPriceTable private constructor(rows: List<SharedPriceRow>, baseVersion: Long) {
    private val rows = rows.toMutableList()
    private var current = baseVersion

    /** The highest sync version: the cursor a worker that is up to date holds. */
    @get:Synchronized
    val version: Long get() = current

    @Synchronized
    fun entries(): List<SharedPriceRow> = rows.toList()

    /** Rows changed after [cursor], oldest change first (Doc 2 s6.4 pull by sync_version). */
    @Synchronized
    fun entriesAfter(cursor: Long): List<SharedPriceRow> = rows.filter { it.syncVersion > cursor }

    /** Adds a price row (history is kept, never rewritten) and raises the version. */
    @Synchronized
    fun add(productId: String, customerTypeId: String, unitPriceCents: Long, effectiveFrom: LocalDate): SharedPriceRow {
        require(unitPriceCents > 0) { "a price must be above zero (a missing price is never zero)" }
        current += 1
        val row = SharedPriceRow("price-$current", productId, customerTypeId, unitPriceCents, effectiveFrom, current)
        rows += row
        return row
    }

    companion object {
        /** Cursor value from which the static catalog (products, types, customers) was already delivered. */
        const val STATIC_CATALOG_VERSION = 3L

        /** The invented test prices (Doc 1 P-4 is pending): Fresh and Chapathi per customer type. */
        fun seeded(today: LocalDate, baseVersion: Long = defaultBase()): SharedPriceTable {
            val start = today.minusMonths(12).withDayOfMonth(1)
            val older = today.minusMonths(18).withDayOfMonth(1)
            fun row(n: Int, product: String, type: String, cents: Long, from: LocalDate) = SharedPriceRow(
                id = "00000000-0000-4000-8000-0000000000d$n",
                productId = product,
                customerTypeId = type,
                unitPriceCents = cents,
                effectiveFrom = from,
                syncVersion = baseVersion,
            )
            return SharedPriceTable(
                listOf(
                    row(1, DemoIds.FRESH, DemoIds.RESTAURANT_TYPE, 280, start),
                    row(2, DemoIds.CHAPATHI, DemoIds.RESTAURANT_TYPE, 250, start),
                    row(3, DemoIds.FRESH, DemoIds.SHOP_TYPE, 300, start),
                    row(4, DemoIds.CHAPATHI, DemoIds.SHOP_TYPE, 270, start),
                    row(5, DemoIds.FRESH, DemoIds.RETAIL_TYPE, 350, start),
                    row(6, DemoIds.CHAPATHI, DemoIds.RETAIL_TYPE, 320, start),
                    // An older price, so the price history has more than one row to show.
                    row(7, DemoIds.FRESH, DemoIds.RESTAURANT_TYPE, 270, older),
                ),
                baseVersion,
            )
        }

        private fun defaultBase(): Long = maxOf(STATIC_CATALOG_VERSION, Instant.now().epochSecond)
    }
}
