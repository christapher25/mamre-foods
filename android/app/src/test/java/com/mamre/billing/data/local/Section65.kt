package com.mamre.billing.data.local

import com.mamre.billing.domain.model.PaymentMode

/**
 * Spice Garden (Restaurant, credit, Irving) with the Doc 1 s6.5 history MADE through the use cases in September 2026, the
 * clock set to each day (priced 1.00 per standard packet, so 120 packets are $120.00):
 *   Sep 3  bill 1  $120.00 -> $120.00      Sep 10 bill 2  $90.00 -> $210.00
 *   Sep 12 payment -$150.00 -> $60.00      Sep 20 bill 3  $60.00  -> $120.00
 * The owner's name is Rajesh. The clock is left on 2026-10-02 14:00 UTC. Returns the world and the customer's id.
 */
suspend fun section65World(db: MamreDatabase = TestDatabase.inMemory()): Pair<World, String> {
    val w = World(db, MutableClock("2026-09-01T09:00:00Z"))
    w.seed()
    w.settings.put(SettingKeys.OWNER_NAME, "Rajesh")
    w.price(ReferenceIds.TYPE_RESTAURANT, 100, 100)
    w.price(ReferenceIds.TYPE_RETAIL, 100, 100)
    w.price(ReferenceIds.TYPE_SHOP, 100, 100)
    val c = w.customer("Spice Garden", "Irving", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT)
    w.clock.set("2026-09-03T09:00:00Z"); w.makeBill(w.draft(c.id, listOf(w.line(qty = 120, unit = 100)))) // $120.00
    w.clock.set("2026-09-10T09:00:00Z"); w.makeBill(w.draft(c.id, listOf(w.line(qty = 90, unit = 100)))) // $90.00
    w.clock.set("2026-09-12T10:00:00Z"); w.recordPayment(w.payment(c.id, 15_000)) // -$150.00
    w.clock.set("2026-09-20T09:00:00Z"); w.makeBill(w.draft(c.id, listOf(w.line(qty = 60, unit = 100)))) // $60.00
    w.clock.set("2026-10-02T14:00:00Z")
    return w to c.id
}
