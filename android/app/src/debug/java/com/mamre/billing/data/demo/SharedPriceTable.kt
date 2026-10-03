package com.mamre.billing.data.demo

import com.mamre.billing.domain.model.PaymentMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

// DEMO DATA. The ONE catalog the Admin's server stand-in and the worker's FakeApi share (DECISIONS 2026-10-03,
// extended by change set C1): customer types with their "worker can edit price" flag, products, customers,
// default prices and override prices. Every change bumps one sync_version. Selling data only: no cost
// fields exist here, so nothing in it can leak cost to a worker (Doc 2 I-8).

/** One selling price (Doc 2 s4.2 PriceDefault) with the sync_version it was last changed at. */
data class SharedPriceRow(
    val id: String,
    val productId: String,
    val customerTypeId: String,
    val unitPriceCents: Long,
    val effectiveFrom: LocalDate,
    val syncVersion: Long,
)

data class SharedTypeRow(
    val id: String,
    val name: String,
    val isActive: Boolean,
    /** Owner decision C3: whether the worker may change a line's price for customers of this type. */
    val workerCanEditPrice: Boolean,
    val syncVersion: Long,
)

data class SharedProductRow(
    val id: String,
    val code: String,
    val name: String,
    /** Chapathis in a standard packet (6). Editable by the Admin (change set C2). */
    val standardPacketSize: Int,
    val isActive: Boolean,
    val syncVersion: Long,
)

data class SharedCustomerRow(
    val id: String,
    val name: String,
    val typeId: String,
    val phone: String,
    val address: String,
    val paymentMode: PaymentMode,
    val isActive: Boolean,
    val syncVersion: Long,
    /** Area or branch (change set D2). */
    val location: String = "",
)

data class SharedOverrideRow(
    val id: String,
    val customerId: String,
    val productId: String,
    val unitPriceCents: Long,
    val effectiveFrom: LocalDate,
    val isActive: Boolean,
    val syncVersion: Long,
)

/**
 * Versions only go up. A new process starts from a higher base than the last one, so a worker that synced
 * during an earlier run still pulls the fresh catalog instead of seeing "nothing new". Everything is stored
 * in memory and resets when the app restarts.
 */
class SharedPriceTable private constructor(baseVersion: Long, seed: Seed) {
    class Seed(
        val types: List<SharedTypeRow>,
        val products: List<SharedProductRow>,
        val customers: List<SharedCustomerRow>,
        val prices: List<SharedPriceRow>,
        val overrides: List<SharedOverrideRow>,
    )

    private val typeRows = seed.types.toMutableList()
    private val productRows = seed.products.toMutableList()
    private val customerRows = seed.customers.toMutableList()
    private val priceRows = seed.prices.toMutableList()
    private val overrideRows = seed.overrides.toMutableList()
    private var current = baseVersion

    /** The highest sync version: the cursor a worker that is up to date holds. */
    @get:Synchronized
    val version: Long get() = current

    private fun bump(): Long {
        current += 1
        return current
    }

    // ------------------------------------------------------------------ reads

    @Synchronized fun types(): List<SharedTypeRow> = typeRows.toList()
    @Synchronized fun products(): List<SharedProductRow> = productRows.toList()
    @Synchronized fun customers(): List<SharedCustomerRow> = customerRows.toList()
    @Synchronized fun overrides(): List<SharedOverrideRow> = overrideRows.toList()

    /** Selling prices, history included (Doc 2 s4.2 PriceDefault). */
    @Synchronized fun all(): List<SharedPriceRow> = priceRows.toList()

    // Rows changed after [cursor] (Doc 2 s6.4 pull by sync_version).
    @Synchronized fun typesAfter(cursor: Long) = typeRows.filter { it.syncVersion > cursor }
    @Synchronized fun productsAfter(cursor: Long) = productRows.filter { it.syncVersion > cursor }
    @Synchronized fun customersAfter(cursor: Long) = customerRows.filter { it.syncVersion > cursor }
    @Synchronized fun overridesAfter(cursor: Long) = overrideRows.filter { it.syncVersion > cursor }
    @Synchronized fun entriesAfter(cursor: Long): List<SharedPriceRow> = priceRows.filter { it.syncVersion > cursor }

    // ------------------------------------------------------------------ writes (each raises the version)

    /** Adds a price row (history is kept, never rewritten). */
    @Synchronized
    fun add(productId: String, customerTypeId: String, unitPriceCents: Long, effectiveFrom: LocalDate): SharedPriceRow {
        require(unitPriceCents > 0) { "a price must be above zero (a missing price is never zero)" }
        val v = bump()
        val row = SharedPriceRow("price-$v", productId, customerTypeId, unitPriceCents, effectiveFrom, v)
        priceRows += row
        return row
    }

    @Synchronized
    fun addCustomer(row: SharedCustomerRow): SharedCustomerRow {
        require(customerRows.none { it.id == row.id }) { "customer ${row.id} exists" }
        require(typeRows.any { it.id == row.typeId }) { "unknown customer type ${row.typeId}" }
        val saved = row.copy(syncVersion = bump())
        customerRows += saved
        return saved
    }

    @Synchronized
    fun updateCustomer(id: String, change: (SharedCustomerRow) -> SharedCustomerRow): SharedCustomerRow {
        val i = customerRows.indexOfFirst { it.id == id }
        require(i >= 0) { "unknown customer $id" }
        val updated = change(customerRows[i]).copy(id = id, syncVersion = bump())
        require(typeRows.any { it.id == updated.typeId }) { "unknown customer type ${updated.typeId}" }
        customerRows[i] = updated
        return updated
    }

    @Synchronized
    fun setWorkerCanEditPrice(typeId: String, allowed: Boolean): SharedTypeRow {
        val i = typeRows.indexOfFirst { it.id == typeId }
        require(i >= 0) { "unknown customer type $typeId" }
        if (typeRows[i].workerCanEditPrice == allowed) return typeRows[i] // no change, no new version
        val updated = typeRows[i].copy(workerCanEditPrice = allowed, syncVersion = bump())
        typeRows[i] = updated
        return updated
    }

    @Synchronized
    fun setStandardPacketSize(productId: String, size: Int): SharedProductRow {
        require(size in 1..MAX_PACKET_SIZE) { "a packet holds 1 to $MAX_PACKET_SIZE chapathis" }
        val i = productRows.indexOfFirst { it.id == productId }
        require(i >= 0) { "unknown product $productId" }
        if (productRows[i].standardPacketSize == size) return productRows[i]
        val updated = productRows[i].copy(standardPacketSize = size, syncVersion = bump())
        productRows[i] = updated
        return updated
    }

    /** Adds an override row (history is kept); the older active rows stay as history. */
    @Synchronized
    fun addOverride(customerId: String, productId: String, unitPriceCents: Long, effectiveFrom: LocalDate): SharedOverrideRow {
        require(unitPriceCents > 0) { "a price must be above zero (a missing price is never zero)" }
        val v = bump()
        val row = SharedOverrideRow("override-$v", customerId, productId, unitPriceCents, effectiveFrom, true, v)
        overrideRows += row
        return row
    }

    /** Switches the active override rows of a customer and product off; rows are never removed. */
    @Synchronized
    fun deactivateOverrides(customerId: String, productId: String) {
        for (i in overrideRows.indices) {
            val r = overrideRows[i]
            if (r.customerId == customerId && r.productId == productId && r.isActive) {
                overrideRows[i] = r.copy(isActive = false, syncVersion = bump())
            }
        }
    }

    companion object {
        /** Kept for older callers: the lowest version a process can start from. */
        const val STATIC_CATALOG_VERSION = 3L
        const val MAX_PACKET_SIZE = 200

        /** The invented test prices (Doc 1 P-4 is pending): Fresh and Chapathi per customer type. */
        fun seeded(today: LocalDate, baseVersion: Long = defaultBase()): SharedPriceTable =
            SharedPriceTable(baseVersion, seedFor(today, baseVersion))

        private fun seedFor(today: LocalDate, v: Long): Seed {
            val first = YearMonth.from(today).minusMonths(6)
            val start = first.atDay(1)
            val launch = first.plusMonths(2).atDay(1) // Mamre Chapathi is sold from the third month of the data
            val priceStart = today.minusMonths(12).withDayOfMonth(1)
            val older = today.minusMonths(18).withDayOfMonth(1)

            val types = listOf(
                SharedTypeRow(DemoIds.RESTAURANT_TYPE, "Restaurant", true, false, v),
                SharedTypeRow(DemoIds.SHOP_TYPE, "Shop", true, false, v),
                SharedTypeRow(DemoIds.RETAIL_TYPE, "Retail", true, true, v),
                SharedTypeRow(DemoIds.CATERING_TYPE, "Catering", true, true, v),
            )
            val products = listOf(
                SharedProductRow(DemoIds.FRESH, "FRESH", "Mamre Fresh Chapathi", STANDARD_PACKET_SIZE, true, v),
                SharedProductRow(DemoIds.CHAPATHI, "CHAPATHI", "Mamre Chapathi", STANDARD_PACKET_SIZE, true, v),
            )
            fun customer(id: String, name: String, location: String, type: String, mode: PaymentMode) =
                SharedCustomerRow(id, name, type, "", "", mode, true, v, location)
            // Each customer exists once, with one id, on both sides (change set C1). Ledgers stay separate.
            val customers = listOf(
                customer(DemoIds.RESTAURANT, "Spice Garden", "Irving", DemoIds.RESTAURANT_TYPE, PaymentMode.CREDIT),
                customer("c-curry-house", "Curry House", "Plano", DemoIds.RESTAURANT_TYPE, PaymentMode.CREDIT),
                customer("c-taj-kitchen", "Taj Kitchen", "Frisco", DemoIds.RESTAURANT_TYPE, PaymentMode.CREDIT),
                customer("c-masala-bistro", "Masala Bistro", "Allen", DemoIds.RESTAURANT_TYPE, PaymentMode.CASH),
                customer(DemoIds.SHOP, "Patel Mart", "Carrollton", DemoIds.SHOP_TYPE, PaymentMode.CREDIT),
                customer("c-corner-shop", "Corner Shop", "Richardson", DemoIds.SHOP_TYPE, PaymentMode.CREDIT),
                customer("c-desi-grocers", "Desi Grocers", "Garland", DemoIds.SHOP_TYPE, PaymentMode.CASH),
                customer(DemoIds.RETAIL_CUSTOMER, "Rao Family", "Coppell", DemoIds.RETAIL_TYPE, PaymentMode.CASH),
                customer("c-sharma-family", "Sharma Family", "Lewisville", DemoIds.RETAIL_TYPE, PaymentMode.CREDIT),
                customer(DemoIds.CATERING, "Royal Banquets", "Addison", DemoIds.CATERING_TYPE, PaymentMode.CREDIT),
                // Two stores of one chain: the same name, told apart by location (change set D2).
                customer(DemoIds.FRESHMART_DOWNTOWN, "FreshMart", "Downtown", DemoIds.SHOP_TYPE, PaymentMode.CREDIT),
                customer(DemoIds.FRESHMART_WESTSIDE, "FreshMart", "Westside", DemoIds.SHOP_TYPE, PaymentMode.CREDIT),
            )
            fun price(n: Int, product: String, type: String, cents: Long, from: LocalDate) = SharedPriceRow(
                id = "00000000-0000-4000-8000-0000000000d$n",
                productId = product,
                customerTypeId = type,
                unitPriceCents = cents,
                effectiveFrom = from,
                syncVersion = v,
            )
            val prices = listOf(
                price(1, DemoIds.FRESH, DemoIds.RESTAURANT_TYPE, 280, priceStart),
                price(2, DemoIds.CHAPATHI, DemoIds.RESTAURANT_TYPE, 250, priceStart),
                price(3, DemoIds.FRESH, DemoIds.SHOP_TYPE, 300, priceStart),
                price(4, DemoIds.CHAPATHI, DemoIds.SHOP_TYPE, 270, priceStart),
                price(5, DemoIds.FRESH, DemoIds.RETAIL_TYPE, 350, priceStart),
                price(6, DemoIds.CHAPATHI, DemoIds.RETAIL_TYPE, 320, priceStart),
                // An older price, so the price history has more than one row to show.
                price(7, DemoIds.FRESH, DemoIds.RESTAURANT_TYPE, 270, older),
                // Catering (invented test prices, Doc 1 P-4 pending).
                price(8, DemoIds.FRESH, DemoIds.CATERING_TYPE, 260, priceStart),
                price(9, DemoIds.CHAPATHI, DemoIds.CATERING_TYPE, 230, priceStart),
            )
            val overrides = listOf(
                SharedOverrideRow("po-1", DemoIds.RESTAURANT, DemoIds.CHAPATHI, 240, launch, true, v),
                SharedOverrideRow("po-2", DemoIds.SHOP, DemoIds.FRESH, 285, start, true, v),
            )
            return Seed(types, products, customers, prices, overrides)
        }

        /** The standard packet: 6 chapathis (owner decision, change set C2). */
        const val STANDARD_PACKET_SIZE = 6

        private fun defaultBase(): Long = maxOf(STATIC_CATALOG_VERSION, Instant.now().epochSecond)
    }
}
