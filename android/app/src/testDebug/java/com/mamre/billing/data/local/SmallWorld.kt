package com.mamre.billing.data.local

import com.mamre.billing.domain.model.PaymentMode

/**
 * A few customers and prices on the real Room database, for the tests that wait on the Sales flow. That flow reads every bill,
 * line, payment and return on each change, so on the seven months of DemoWorld history it is real CPU work under Robolectric
 * and takes as long as the machine is slow. A test that is not about the history uses this instead, so what it waits for is small
 * and does not depend on how fast the machine is. Run with RobolectricTestRunner.
 */
class SmallWorld(val w: World, val spiceGardenId: String, val freshMartId: String) {
    companion object {
        const val BUSINESS_NAME = "MAMRE FOODS"

        /** Seeded reference data, prices for all types, a Restaurant on credit and a corporate Shop on credit. */
        suspend fun open(db: MamreDatabase = TestDatabase.inMemory(), clock: MutableClock = MutableClock("2026-10-02T14:00:00Z")): SmallWorld {
            val w = World(db, clock).also { it.seed() }
            w.settings.put(SettingKeys.BUSINESS_NAME, BUSINESS_NAME)
            w.price(ReferenceIds.TYPE_RESTAURANT, 250, 240)
            w.price(ReferenceIds.TYPE_SHOP, 280, 270)
            w.price(ReferenceIds.TYPE_RETAIL, 300, 290)
            val spice = w.customer("Spice Garden", "Irving", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT, opening = 5_000)
            val mart = w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP, PaymentMode.CREDIT, corporate = true, opening = 5_000)
            return SmallWorld(w, spice.id, mart.id)
        }
    }
}
