package com.mamre.billing.data.demo

import com.mamre.billing.domain.worker.InvoiceLine
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set C3: the use case that saves an invoice refuses a changed price for a type that may not change it. */
class DemoStorePriceEditTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T14:00:00Z"), ZoneOffset.UTC)
    private val store = DemoStore(clock, DemoSeed.state())

    private fun draft(line: InvoiceLine, allowed: Boolean) = InvoiceDraft(
        "draft-${line.unitPriceCents}-$allowed", DemoIds.RETAIL_CUSTOMER, "Rao Family", "Retail", "W1",
        listOf(line), line.lineTotalCents, com.mamre.billing.domain.worker.PaymentMethod.CASH,
        priceEditAllowed = allowed,
    )

    private val changed = InvoiceLine(DemoIds.FRESH, "Mamre Fresh Chapathi", 2, 300, listPriceCents = 350)
    private val unchanged = InvoiceLine(DemoIds.FRESH, "Mamre Fresh Chapathi", 2, 350)

    @Test fun aChangedPriceIsRefusedWhenTheTypeMayNotChangeIt() {
        assertThrows(IllegalArgumentException::class.java) { store.confirmInvoice(draft(changed, allowed = false)) }
        assertTrue(store.state.value.invoices.none { it.customerId == DemoIds.RETAIL_CUSTOMER })
        assertEquals(0, store.state.value.pendingCount)
    }

    @Test fun aChangedPriceIsSavedWhenTheTypeMayChangeIt() {
        val saved = store.confirmInvoice(draft(changed, allowed = true))
        assertEquals(600L, saved.totalCents)
        assertTrue(saved.lines.single().priceOverridden)
        assertEquals(350L, saved.lines.single().listPriceCents)
    }

    @Test fun anUnchangedPriceIsAlwaysFine() {
        val saved = store.confirmInvoice(draft(unchanged, allowed = false))
        assertEquals(700L, saved.totalCents)
    }
}
