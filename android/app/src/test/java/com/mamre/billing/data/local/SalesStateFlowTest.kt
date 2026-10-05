package com.mamre.billing.data.local

import com.mamre.billing.domain.worker.SalesState
import com.mamre.billing.testing.Repeat
import com.mamre.billing.testing.RepeatRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Sales read model (SalesRepository.observeState) is what Home, the invoice flow, payments, returns and Today's bills
 * watch. A bill is written into several tables (the bill and its lines). The flow must NEVER show a half-written bill and
 * never die: it used to combine five separately watched tables, so the bill row could arrive before its lines, an
 * InvoiceRecord refused the total (Doc 2 I-2), the exception ended the flow for good and the screens froze at the old state.
 * That showed up as a flaky Home test (about 7 failures in 9 runs).
 */
@RunWith(RobolectricTestRunner::class)
class SalesStateFlowTest {
    @get:Rule val repeat = RepeatRule()

    private lateinit var w: World
    private lateinit var scope: CoroutineScope

    @Before fun open() {
        scope = CoroutineScope(Job() + Dispatchers.Default) // a new one per repetition: @After cancels it
        w = runBlocking {
            World(TestDatabase.inMemory()).also {
                it.seed()
                it.price(ReferenceIds.TYPE_RESTAURANT, 250, 280)
            }
        }
    }

    @After fun close() {
        scope.cancel()
        w.db.close()
    }

    @Repeat(20)
    @Test fun theFlowSurvivesManyBillsAndEveryStateItShowsIsWhole() = runBlocking {
        val c = w.customer("Spice Garden", "Irving")
        val seen = java.util.Collections.synchronizedList(mutableListOf<SalesState>())
        val failure = CompletableDeferred<Throwable>()
        val firstState = CompletableDeferred<Unit>()
        scope.launch(CoroutineExceptionHandler { _, e -> failure.complete(e) }) {
            w.sales.observeState().catch { failure.complete(it) }.collect {
                seen += it
                firstState.complete(Unit)
            }
        }
        withTimeout(10_000) { firstState.await() }

        val bills = 8
        repeat(bills) { i ->
            w.makeBill(w.draft(c.id, listOf(w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = i + 1, unit = 280))))
        }

        // The last state must show every bill, and the flow must still be alive.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && failure.isActive && (seen.lastOrNull()?.invoices?.size ?: 0) < bills) {
            Thread.sleep(20)
        }
        assertTrue("the flow ended with ${failure.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }}", failure.isActive)
        assertEquals(bills, seen.last().invoices.size)
        // Every bill of every state is whole: its total is the sum of its lines.
        seen.forEach { s -> s.invoices.forEach { assertEquals(it.totalCents, it.lines.sumOf { l -> l.lineTotalCents }) } }
        assertNull(failure.takeIf { it.isCompleted }?.getCompleted())
    }
}
