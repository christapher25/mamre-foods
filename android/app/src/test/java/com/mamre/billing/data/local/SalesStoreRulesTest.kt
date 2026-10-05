package com.mamre.billing.data.local

import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.domain.worker.broughtForward
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The old DemoStoreTest, DemoStoreRecordsTest and DemoStorePriceEditTest, moved onto the real database and the real use
 * cases (L1 step 1). The demo store seeded the Doc 1 s6.5 ledger by hand; here the ledger is MADE through the use cases with
 * the clock set to each day, so AT-1 is proved on real writes. "Pending" and "Sync now" no longer exist (no server).
 */
@RunWith(RobolectricTestRunner::class)
class SalesStoreRulesTest {
    private val file = "sales-store-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun worldWithSection65Ledger() = section65World()

    @Test fun theSection65LedgerMadeThroughTheUseCasesClosesAt120Dollars() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        assertEquals(12000L, w.sales.state().balanceOf(id)) // 120 -> 210 -> 60 -> 120
    }

    @Test fun octoberOpensWithTheSeptemberClosingBalanceBroughtForward() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        val state = w.sales.state()
        assertEquals(12000L, broughtForward(state.openingOf(id), state.ledgerOf(id), YearMonth.of(2026, 10)))
        assertEquals(0L, broughtForward(state.openingOf(id), state.ledgerOf(id), YearMonth.of(2026, 9)))
    }

    @Test fun otherCustomersStartAtZeroAndNoBillIsFromTodayAtStart() = runBlocking {
        val (w, _) = worldWithSection65Ledger()
        val shop = w.customer("Patel Mart", "Mesquite", ReferenceIds.TYPE_SHOP)
        val retail = w.customer("Rao Family", "Coppell", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH)
        assertEquals(0L, w.sales.state().balanceOf(shop.id))
        assertEquals(0L, w.sales.state().balanceOf(retail.id))
        assertTrue(w.sales.state().invoices.none { it.issuedAt.toLocalDate() == w.today })
    }

    @Test fun numbersGoUpByOneAreNeverReusedAndFollowTheDeviceCodeSetting() = runBlocking {
        val (w, id) = worldWithSection65Ledger() // three bills already: 0001 to 0003
        val a = w.makeBill(w.draft(id, listOf(w.line(qty = 1, unit = 100))))
        val b = w.makeBill(w.draft(id, listOf(w.line(qty = 1, unit = 100))))
        assertEquals(listOf("MAM-W1-0004", "MAM-W1-0005"), listOf(a.number, b.number))
        w.settings.put(SettingKeys.DEVICE_CODE, "W2") // the device code is a setting; the sequence continues
        assertEquals("MAM-W2-0006", w.makeBill(w.draft(id, listOf(w.line(qty = 1, unit = 100)))).number)
    }

    @Test fun anInvoiceRaisesTheBalanceByItsTotalLessWhatWasPaid() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        val record = w.makeBill(w.draft(id, listOf(w.line(qty = 25, unit = 100)), paid = 1000, method = PaymentMethod.CASH))
        assertEquals(2500L, record.totalCents)
        assertEquals(13500L, record.balanceAfterCents) // 120.00 + 25.00 - 10.00
        assertEquals(13500L, w.sales.state().balanceOf(id))
    }

    @Test fun aWalkInInvoiceTouchesNoLedger() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        val record = w.makeBill(w.draft(null, listOf(w.line(qty = 25, unit = 100)), paid = 2500, method = PaymentMethod.CASH))
        assertEquals(0L, record.balanceAfterCents)
        assertEquals(12000L, w.sales.state().balanceOf(id))
    }

    @Test fun anEmptyDraftIsRefused() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        assertThrows(com.mamre.billing.domain.usecase.RuleException::class.java) { runBlocking { w.makeBill(w.draft(id, emptyList())) } }
        assertEquals(3, w.db.invoiceDao().count())
    }

    // ------------------------------------------------------------------ payments and returns (DemoStoreRecordsTest)

    @Test fun aPaymentLowersTheBalanceAndGetsAReceiptNumber() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        val before = w.db.paymentDao().getAll().size // the ledger's own payment already holds RCP-W1-0001
        val record = w.recordPayment(w.payment(id, 5000, PaymentMethod.ZELLE, " by phone "))
        assertEquals("RCP-W1-000${before + 1}", record.receiptNumber)
        assertEquals("by phone", record.note)
        assertEquals(7000L, w.sales.state().balanceOf(id)) // 120.00 - 50.00
        assertEquals("RCP-W1-000${before + 2}", w.recordPayment(w.payment(id, 100)).receiptNumber)
    }

    @Test fun aPaymentRecordedTwiceWithTheSameIdIsStoredOnce() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        val first = w.recordPayment(w.payment(id, 5000, id = "p1"))
        val again = w.recordPayment(w.payment(id, 5000, id = "p1"))
        assertEquals(first, again)
        assertEquals(1, w.sales.state().payments.count { it.id == "p1" })
        assertEquals(7000L, w.sales.state().balanceOf(id))
    }

    @Test fun anOverpaymentBecomesCreditOnAccount() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        w.recordPayment(w.payment(id, 15_000))
        assertEquals(-3000L, w.sales.state().balanceOf(id))
    }

    /** AT-9: a credit reduces the balance, a replacement does not. */
    @Test fun aCreditReturnReducesTheBalance() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        val record = w.recordReturn(w.returnOf(id, qty = 4, unit = 250, credit = true))
        assertEquals(1000L, record.creditCents)
        assertEquals(11000L, w.sales.state().balanceOf(id))
    }

    @Test fun aReplacementLeavesTheBalanceAlone() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        val record = w.recordReturn(w.returnOf(id, qty = 4, unit = 250, credit = false))
        assertEquals(0L, record.creditCents)
        assertEquals(ReturnResolution.REPLACEMENT, record.resolution)
        assertEquals(12000L, w.sales.state().balanceOf(id))
    }

    @Test fun aReturnRecordedTwiceWithTheSameIdIsStoredOnce() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        w.recordReturn(w.returnOf(id, qty = 4, unit = 250, id = "r1"))
        w.recordReturn(w.returnOf(id, qty = 4, unit = 250, id = "r1"))
        assertEquals(1, w.sales.state().returns.size)
        assertEquals(11000L, w.sales.state().balanceOf(id))
    }

    @Test fun recordsAreOnlyEverAdded() = runBlocking {
        val (w, id) = worldWithSection65Ledger()
        w.recordPayment(w.payment(id, 5000, id = "p9"))
        w.recordReturn(w.returnOf(id, qty = 4, unit = 250, id = "r9"))
        val state = w.sales.state()
        assertEquals(1, state.payments.count { it.id == "p9" })
        assertEquals(1, state.returns.count { it.id == "r9" })
        assertEquals(3, state.invoices.size) // nothing was deleted
    }

    // ------------------------------------------------------------------ price edits (DemoStorePriceEditTest)

    private suspend fun retailWorld(): Pair<World, String> {
        val w = World(TestDatabase.inMemory())
        w.seed()
        w.price(ReferenceIds.TYPE_RETAIL, 350, 320)
        return w to w.customer("Rao Family", "Coppell", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH).id
    }

    @Test fun aChangedPriceIsRefusedWhenTheTypeMayNotChangeIt() = runBlocking {
        val (w, id) = retailWorld()
        w.setCanEditPrice(ReferenceIds.TYPE_RETAIL, false)
        val changed = w.line(qty = 2, unit = 300, list = 350)
        assertThrows(com.mamre.billing.domain.usecase.RuleException::class.java) {
            runBlocking { w.makeBill(w.draft(id, listOf(changed), paid = 600, method = PaymentMethod.CASH)) }
        }
        assertTrue(w.sales.state().invoices.none { it.customerId == id })
    }

    @Test fun aChangedPriceIsSavedWhenTheTypeMayChangeIt() = runBlocking {
        val (w, id) = retailWorld()
        val saved = w.makeBill(w.draft(id, listOf(w.line(qty = 2, unit = 300, list = 350)), paid = 600, method = PaymentMethod.CASH))
        assertEquals(600L, saved.totalCents)
        assertTrue(saved.lines.single().priceOverridden)
        assertEquals(350L, saved.lines.single().listPriceCents)
    }

    @Test fun anUnchangedPriceIsAlwaysFine() = runBlocking {
        val (w, id) = retailWorld()
        w.setCanEditPrice(ReferenceIds.TYPE_RETAIL, false)
        val saved = w.makeBill(w.draft(id, listOf(w.line(qty = 2, unit = 350)), paid = 700, method = PaymentMethod.CASH))
        assertEquals(700L, saved.totalCents)
    }
}
