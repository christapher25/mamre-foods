package com.mamre.billing.data.local

import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.MAX_PRICE_FACTOR
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.customPacketPriceCents
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review finding 2, moved from DemoStore.confirmInvoice to the real MakeBill use case (L1 step 1): the price limits (above
 * zero, at most 10 times the list price) hold in the use case itself, so a screen or a view model that skips its own check
 * cannot produce an illegal price. Same cases as the old PriceLimitUseCaseTest second half.
 */
@RunWith(RobolectricTestRunner::class)
class PriceLimitMakeBillTest {
    private val list = 250L
    private val cap = list * MAX_PRICE_FACTOR // 2,500 cents, exactly 10 times

    /** Retail (switch on) and Restaurant (switch off) both price Mamre Fresh Chapathi at 2.50 per packet. */
    private suspend fun world(): World = World(TestDatabase.inMemory()).also {
        it.seed()
        it.price(ReferenceIds.TYPE_RETAIL, list, 280)
        it.price(ReferenceIds.TYPE_RESTAURANT, list, 280)
    }

    private fun lineCharging(charged: Long, listCents: Long = list, size: Int = 12) =
        InvoiceLine(ReferenceIds.PRODUCT_FRESH, "Mamre Fresh Chapathi", 1, charged, size, listCents, isCustomPacket = size != 12)

    private suspend fun World.walkIn(line: InvoiceLine) =
        makeBill(draft(null, listOf(line), paid = line.lineTotalCents, method = PaymentMethod.CASH))

    private suspend fun World.nothingStored() {
        assertEquals(0, db.invoiceDao().count())
        assertEquals("1", settings.get(SettingKeys.NEXT_BILL_SEQ))
    }

    @Test fun makeBillAcceptsExactlyTenTimesTheListPrice() = runBlocking {
        val w = world()
        val record = w.walkIn(lineCharging(cap))
        assertEquals(cap, record.lines.single().unitPriceCents)
        assertEquals(1, w.db.invoiceDao().count())
    }

    @Test fun makeBillRefusesOneCentMoreThanTenTimesAndStoresNothing() = runBlocking {
        val w = world()
        val e = try {
            w.walkIn(lineCharging(cap + 1))
            fail("one cent more than 10 times must be refused")
            return@runBlocking
        } catch (e: RuleException) {
            e
        }
        assertTrue(e.message!!, e.message!!.contains("10 times"))
        w.nothingStored()
    }

    @Test fun makeBillRefusesTheCapOfACustomPacketAgainstItsOwnList() = runBlocking {
        val w = world()
        val customList = customPacketPriceCents(list, 10, 12)
        w.walkIn(lineCharging(customList * 10, customList, size = 10))
        assertThrows(RuleException::class.java) {
            runBlocking { w.walkIn(lineCharging(customList * 10 + 1, customList, size = 10)) }
        }
        assertEquals(1, w.db.invoiceDao().count())
    }

    @Test fun aZeroOrNegativePriceCannotEvenBecomeALineSoMakeBillNeverSeesOne() = runBlocking {
        val w = world()
        for (bad in listOf(0L, -1L, -list)) {
            assertThrows("charged $bad", IllegalArgumentException::class.java) { lineCharging(bad) }
        }
        w.nothingStored()
    }

    @Test fun makeBillStillRefusesAChangedPriceForANonEditableType() = runBlocking {
        val w = world()
        val restaurant = w.customer("Spice Garden", "Irving", ReferenceIds.TYPE_RESTAURANT)
        assertThrows(RuleException::class.java) {
            runBlocking { w.makeBill(w.draft(restaurant.id, listOf(lineCharging(200)))) }
        }
        // An unchanged price is fine for any type.
        w.makeBill(w.draft(restaurant.id, listOf(lineCharging(list))))
        assertEquals(1, w.db.invoiceDao().count())
    }
}
