package com.mamre.billing.data.local

import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Doc 1 s7.1, AT-9: a Credit return is worth the LINKED bill's unit price, or the customer's CURRENT price when no bill is
 * linked; it stores the packet size of the returned packets (not the product's standard size); and it can never return more
 * than the linked bill held, less what was already returned. All of it is enforced in the RecordReturn use case.
 */
@RunWith(RobolectricTestRunner::class)
class ReturnRulesTest {
    private val file = "return-rules-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    /** Restaurant 2.50 / 2.80 and Retail 2.60 / 2.90 per standard packet of 12. */
    private suspend fun priced(db: MamreDatabase = TestDatabase.inMemory()): World {
        val w = World(db)
        w.seed()
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 280)
        w.price(ReferenceIds.TYPE_RETAIL, 260, 290)
        return w
    }

    @Test fun aLinkedCreditIsWorthTheBillsUnitPriceNotTheCurrentPrice() = runBlocking {
        val w = priced()
        val c = w.customer("Rao Family", "Coppell", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH)
        // The Owner changed the price on this bill (list 2.60, charged 2.47).
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(qty = 10, unit = 247, list = 260)), paid = 2470, method = PaymentMethod.CASH))
        w.clock.advanceMinutes(60 * 24 * 40)
        w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_RETAIL, 999, w.today) // the list price changes later
        val r = w.recordReturn(w.returnOf(c.id, qty = 3, invoiceId = bill.id))
        assertEquals(247L, r.unitPriceCents)
        assertEquals(3 * 247L, r.creditCents)
    }

    @Test fun anUnlinkedCreditIsWorthTheCustomersCurrentPriceOverrideIncluded() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        assertEquals(2 * 250L, w.recordReturn(w.returnOf(c.id, qty = 2)).creditCents) // the type's price now
        w.setOverride(c.id, ReferenceIds.PRODUCT_FRESH, 200, w.today.minusDays(1), "regular")
        assertEquals(2 * 200L, w.recordReturn(w.returnOf(c.id, qty = 2)).creditCents) // the override now
    }

    @Test fun anUnlinkedReturnOfAProductWithNoPriceIsRefusedAndStoresNothing() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        val c = w.customer("Spice Garden", "Irving")
        assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 1)) }.contains("price"))
        assertEquals(0, w.db.returnDao().getAll().size)
    }

    @Test fun theStoredPacketSizeIsTheReturnedPacketsNotTheProductsStandardSize() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        // 250 per 12 -> 208 per packet of 10 (half up).
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(qty = 5, unit = 208, size = 10), w.line(qty = 5, unit = 250))))
        val custom = w.recordReturn(w.returnOf(c.id, qty = 2, invoiceId = bill.id, size = 10))
        assertEquals(2 * 208L, custom.creditCents)
        val standard = w.recordReturn(w.returnOf(c.id, qty = 2, invoiceId = bill.id, size = 12))
        assertEquals(2 * 250L, standard.creditCents)
        val rows = w.db.returnDao().getAll().associateBy { it.id }
        assertEquals(10, rows.getValue(custom.id).chapathisPerPacket)
        assertEquals(12, rows.getValue(standard.id).chapathisPerPacket)
        // Unlinked: the packet price of that size, half up (250 x 9 / 12 = 187.5 -> 188).
        val unlinked = w.recordReturn(w.returnOf(c.id, qty = 1, size = 9))
        assertEquals(188L, unlinked.unitPriceCents)
        assertEquals(9, w.db.returnDao().get(unlinked.id)!!.chapathisPerPacket)
    }

    @Test fun aSizeThatIsNotOnTheLinkedBillIsRefused() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(qty = 5, unit = 250))))
        assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 1, invoiceId = bill.id, size = 10)) }.contains("does not contain"))
        for (bad in listOf(0, 201)) assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 1, size = bad)) }.contains("1 to 200"))
        assertEquals(0, w.db.returnDao().getAll().size)
    }

    @Test fun theReturnedQuantityIsCappedAtWhatTheBillHeldLessEarlierReturns() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        // Two lines of the same product and size count together: 6 + 4 = 10 packets.
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(qty = 6, unit = 250), w.line(qty = 4, unit = 250))))
        assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 11, invoiceId = bill.id)) }.contains("10"))
        w.recordReturn(w.returnOf(c.id, qty = 6, invoiceId = bill.id))
        assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 5, invoiceId = bill.id)) }.contains("4")) // only 4 are left
        w.recordReturn(w.returnOf(c.id, qty = 3, credit = false, invoiceId = bill.id)) // a replacement counts as returned too
        assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 2, invoiceId = bill.id)) }.contains("1"))
        w.recordReturn(w.returnOf(c.id, qty = 1, invoiceId = bill.id)) // exactly what is left
        assertEquals(3, w.db.returnDao().getAll().size) // 6 credited, 3 replaced, 1 credited; the refused ones left nothing
    }

    @Test fun theCapIsPerSizeSoAnotherSizeOfTheSameProductIsNotAffected() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(qty = 2, unit = 250), w.line(qty = 3, unit = 208, size = 10))))
        w.recordReturn(w.returnOf(c.id, qty = 2, invoiceId = bill.id, size = 12))
        w.recordReturn(w.returnOf(c.id, qty = 3, invoiceId = bill.id, size = 10))
        assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 1, invoiceId = bill.id, size = 12)) }.isNotEmpty())
    }

    @Test fun aBillOfAnotherCustomerIsRefused() = runBlocking {
        val w = priced()
        val a = w.customer("Spice Garden", "Irving")
        val b = w.customer("Curry House", "Plano")
        val bill = w.makeBill(w.draft(a.id, listOf(w.line(qty = 2, unit = 250))))
        assertTrue(refused { w.recordReturn(w.returnOf(b.id, qty = 1, invoiceId = bill.id)) }.contains("another customer"))
    }

    @Test fun creditAndStoredSizeAndPriceReadBackAfterTheDatabaseIsClosedAndReopened() = runBlocking {
        var w = priced(TestDatabase.file(file))
        val c = w.customer("Rao Family", "Coppell", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH)
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(qty = 4, unit = 247, list = 260, size = 12)), paid = 988, method = PaymentMethod.CASH))
        val r = w.recordReturn(w.returnOf(c.id, qty = 2, invoiceId = bill.id))
        w.db.close()
        w = World(TestDatabase.file(file), w.clock)
        val row = w.db.returnDao().get(r.id)!!
        assertEquals(247L, row.unitPriceCents)
        assertEquals(494L, row.creditCents)
        assertEquals(12, row.chapathisPerPacket)
        assertEquals(bill.id, row.invoiceId)
        assertEquals(LocalDate.of(2026, 10, 4), w.sales.state().returns.single().occurredAt.toLocalDate())
        w.db.close()
    }
}
