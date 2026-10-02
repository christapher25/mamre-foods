package com.mamre.billing.data.demo

import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnResolution
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DemoStoreRecordsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T14:00:00Z"), ZoneOffset.UTC)
    private val store = DemoStore(clock)

    private fun pay(id: String = "p1", amount: Long = 5000) = PaymentDraft(
        id, DemoIds.RESTAURANT, "Test Restaurant", "W1", amount, PaymentMethod.ZELLE, " by phone ",
    )

    private fun ret(id: String = "r1", resolution: ReturnResolution) = ReturnDraft(
        id, DemoIds.RESTAURANT, "Test Restaurant", DemoIds.CHAPATHI, "Mamre Chapathi", 4,
        ReturnReason.DAMAGED, resolution, 250, "W1",
    )

    @Test fun aPaymentLowersTheBalanceAndGetsAReceiptNumber() {
        val record = store.recordPayment(pay())
        assertEquals("RCP-W1-0001", record.receiptNumber)
        assertEquals("by phone", record.note)
        assertEquals(7000L, store.state.value.balanceOf(DemoIds.RESTAURANT)) // 120.00 - 50.00
        assertEquals(1, store.state.value.pendingCount)
        assertEquals("RCP-W1-0002", store.recordPayment(pay("p2")).receiptNumber)
    }

    @Test fun aPaymentRecordedTwiceWithTheSameIdIsStoredOnce() {
        val first = store.recordPayment(pay())
        assertSame(first, store.recordPayment(pay()))
        assertEquals(1, store.state.value.payments.count { it.deviceCode == "W1" })
        assertEquals(1, store.state.value.pendingCount)
    }

    @Test fun anOverpaymentBecomesCreditOnAccount() {
        store.recordPayment(pay(amount = 15000))
        assertEquals(-3000L, store.state.value.balanceOf(DemoIds.RESTAURANT))
    }

    /** AT-9: a credit reduces the balance, a replacement does not. */
    @Test fun aCreditReturnReducesTheBalance() {
        val record = store.recordReturn(ret(resolution = ReturnResolution.CREDIT))
        assertEquals(1000L, record.creditCents)
        assertEquals(11000L, store.state.value.balanceOf(DemoIds.RESTAURANT))
        assertEquals(1, store.state.value.pendingCount)
    }

    @Test fun aReplacementLeavesTheBalanceAlone() {
        val record = store.recordReturn(ret(resolution = ReturnResolution.REPLACEMENT))
        assertEquals(0L, record.creditCents)
        assertEquals(12000L, store.state.value.balanceOf(DemoIds.RESTAURANT))
        assertEquals(1, store.state.value.pendingCount) // still a record to sync
    }

    @Test fun aReturnRecordedTwiceWithTheSameIdIsStoredOnce() {
        store.recordReturn(ret(resolution = ReturnResolution.CREDIT))
        store.recordReturn(ret(resolution = ReturnResolution.CREDIT))
        assertEquals(1, store.state.value.returns.size)
        assertEquals(11000L, store.state.value.balanceOf(DemoIds.RESTAURANT))
    }

    @Test fun recordsAreOnlyEverAdded() {
        store.recordPayment(pay())
        store.recordReturn(ret(resolution = ReturnResolution.CREDIT))
        store.syncNow()
        assertEquals(1, store.state.value.payments.count { it.deviceCode == "W1" })
        assertEquals(1, store.state.value.returns.size)
    }
}
