package com.mamre.billing.data.demo

import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.broughtForward
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoStoreTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T14:00:00Z"), ZoneOffset.UTC)
    private val store = DemoStore(clock)

    private fun draft(
        id: String = "draft-1",
        customerId: String? = DemoIds.RESTAURANT,
        packets: Int = 10,
        paid: Long = 0,
        method: PaymentMethod? = null,
    ) = InvoiceDraft(
        id, customerId, "Test Restaurant", "Restaurant", "W1",
        listOf(InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", packets, 250)), paid, method,
    )

    @Test fun seedReproducesTheDoc1Section65LedgerInSeptember() {
        // 120 -> 210 -> 60 -> 120 with Sep 3, 10, 12 and 20: closing balance is $120.00.
        assertEquals(12000L, store.state.value.balanceOf(DemoIds.RESTAURANT))
    }

    @Test fun octoberOpensWithTheSeptemberClosingBalanceBroughtForward() {
        val ledger = store.state.value.ledgerOf(DemoIds.RESTAURANT)
        assertEquals(12000L, broughtForward(0, ledger, YearMonth.of(2026, 10)))
        assertEquals(0L, broughtForward(0, ledger, YearMonth.of(2026, 9)))
    }

    @Test fun otherCustomersStartAtZero() {
        assertEquals(0L, store.state.value.balanceOf(DemoIds.SHOP))
        assertEquals(0L, store.state.value.balanceOf(DemoIds.RETAIL_CUSTOMER))
    }

    @Test fun nothingIsPendingAndNoInvoiceIsFromTodayAtStart() {
        assertEquals(0, store.state.value.pendingCount)
        assertTrue(store.state.value.invoices.none { it.issuedAt.toLocalDate() == store.today() })
    }

    @Test fun theFirstNewInvoiceIsNumberOneForThatDevice() {
        assertEquals("MAM-W1-0001", store.confirmInvoice(draft()).number)
    }

    @Test fun numbersGoUpByOneAndAreNeverReused() {
        val a = store.confirmInvoice(draft("a"))
        val b = store.confirmInvoice(draft("b"))
        assertEquals(listOf("MAM-W1-0001", "MAM-W1-0002"), listOf(a.number, b.number))
        assertEquals("MAM-W1-0003", store.nextInvoiceNumber("W1"))
        assertEquals("MAM-W2-0001", store.nextInvoiceNumber("W2"))
    }

    @Test fun confirmingTheSameDraftTwiceStoresOneInvoice() {
        val first = store.confirmInvoice(draft("same"))
        val again = store.confirmInvoice(draft("same"))
        assertSame(first, again)
        assertEquals(1, store.state.value.invoices.count { it.deviceCode == "W1" })
        assertEquals(1, store.state.value.pendingCount)
    }

    @Test fun anInvoiceRaisesTheBalanceByItsTotalLessWhatWasPaid() {
        val record = store.confirmInvoice(draft(packets = 10, paid = 1000, method = PaymentMethod.CASH))
        assertEquals(2500L, record.totalCents)
        assertEquals(13500L, record.balanceAfterCents) // 120.00 + 25.00 - 10.00
        assertEquals(13500L, store.state.value.balanceOf(DemoIds.RESTAURANT))
    }

    @Test fun aWalkInInvoiceTouchesNoLedger() {
        val record = store.confirmInvoice(draft(customerId = null, paid = 2500, method = PaymentMethod.CASH))
        assertEquals(0L, record.balanceAfterCents)
        assertEquals(12000L, store.state.value.balanceOf(DemoIds.RESTAURANT))
    }

    @Test fun theInvoiceIsStampedWithTheClock() {
        assertEquals(store.today(), store.confirmInvoice(draft()).issuedAt.toLocalDate())
    }

    @Test fun pendingCountsUpAndSyncNowClearsIt() {
        store.confirmInvoice(draft("a"))
        store.confirmInvoice(draft("b"))
        assertEquals(2, store.state.value.pendingCount)
        store.syncNow()
        assertEquals(0, store.state.value.pendingCount)
        assertEquals(2, store.state.value.invoices.count { it.deviceCode == "W1" }) // nothing is deleted
    }

    @Test fun anEmptyDraftIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            store.confirmInvoice(draft().copy(lines = emptyList()))
        }
    }
}
