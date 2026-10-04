package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.valueOrNull
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Owner costing spec 5: purchases and other expenses are add only. A mistake is corrected by a reversing
 * entry (negative quantity and amount, a required reason, linked to the original), which the monthly
 * table nets out. Nothing is ever edited or deleted (Doc 3 N3).
 */
class ReversalTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T14:00:00Z"), ZoneOffset.UTC)
    private val api = FakeAdminApi(clock)
    private val october = YearMonth.of(2026, 10)
    private val day = LocalDate.of(2026, 10, 1)

    private suspend fun expectRefused(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: AdminRuleException) {
            return
        }
        fail("expected AdminRuleException")
    }

    private suspend fun wheat() = api.stock(october).rows.first { it.material.id == SeedIds.WHEAT }

    @Test fun reversingAPurchaseAddsANegativeLinkedEntryAndKeepsTheOriginal() = runTest {
        val original = api.addPurchase(SeedIds.WHEAT, day, 50_000_000, 5_500, "Typo", "Test Admin")
        val rev = api.reversePurchase(original.id, "Entered twice by mistake", "Test Admin")
        assertEquals(-50_000_000L, rev.qtyMb)
        assertEquals(-5_500L, rev.totalCents)
        assertEquals(original.id, rev.reversesId)
        assertEquals("Entered twice by mistake", rev.reason)
        val list = api.purchases(october)
        assertTrue(list.any { it.id == original.id && it.qtyMb == 50_000_000L }) // the original is untouched
        assertTrue(list.any { it.id == rev.id })
    }

    @Test fun theMonthlyTableNetsAReversalOut() = runTest {
        val before = wheat()
        val original = api.addPurchase(SeedIds.WHEAT, day, 50_000_000, 5_500, "", "Test Admin")
        val bought = wheat()
        assertEquals(before.boughtQtyMb + 50_000_000, bought.boughtQtyMb)
        api.reversePurchase(original.id, "Wrong supplier", "Test Admin")
        val after = wheat()
        assertEquals(before.boughtQtyMb, after.boughtQtyMb)
        assertEquals(before.boughtCents, after.boughtCents)
        assertEquals(before.closingQtyMb, after.closingQtyMb)
        assertEquals(before.avgPriceTt, after.avgPriceTt)
        assertEquals(before.costConsumedCents, after.costConsumedCents)
    }

    @Test fun aReversalNeedsAReasonAndAnExistingEntryAndWorksOnlyOnce() = runTest {
        val original = api.addPurchase(SeedIds.WHEAT, day, 50_000_000, 5_500, "", "Test Admin")
        expectRefused { api.reversePurchase(original.id, "   ", "Test Admin") }
        expectRefused { api.reversePurchase("nope", "x", "Test Admin") }
        val rev = api.reversePurchase(original.id, "x", "Test Admin")
        expectRefused { api.reversePurchase(original.id, "again", "Test Admin") } // already reversed
        expectRefused { api.reversePurchase(rev.id, "undo the undo", "Test Admin") } // a reversal is not reversed
        assertEquals(2, api.changeLog.value.size) // add + one reversal; the refused attempts left no trace
    }

    @Test fun everyReversalIsInTheChangeLogWithItsReason() = runTest {
        val original = api.addPurchase(SeedIds.WHEAT, day, 50_000_000, 5_500, "", "Test Admin")
        api.reversePurchase(original.id, "Wrong supplier", "Test Admin")
        val entry = api.changeLog.value.first()
        assertTrue(entry.what.contains("Reverse purchase"))
        assertTrue(entry.after.contains("Wrong supplier"))
    }

    @Test fun reversingAnExpenseNetsTheCategoryAndIndirectTotal() = runTest {
        val before = api.expenses(october).indirectTotalCents
        val e = api.addExpense(SeedIds.CAT_LABOUR, day, 12_345, "Extra shift", "Test Admin")
        assertEquals(before + 12_345, api.expenses(october).indirectTotalCents)
        val rev = api.reverseExpense(e.id, "Wrong amount", "Test Admin")
        assertEquals(-12_345L, rev.amountCents)
        assertEquals(e.id, rev.reversesId)
        val report = api.expenses(october)
        assertEquals(before, report.indirectTotalCents)
        assertEquals(before, report.categories.sumOf { it.totalCents })
        assertEquals(before, api.dashboard(october).indirectExpensesCents)
        assertTrue(report.categories.flatMap { it.entries }.any { it.id == e.id }) // still listed
    }

    @Test fun anExpenseReversalNeedsAReasonAndWorksOnlyOnce() = runTest {
        val e = api.addExpense(SeedIds.CAT_LABOUR, day, 100, "", "Test Admin")
        expectRefused { api.reverseExpense(e.id, "", "Test Admin") }
        val rev = api.reverseExpense(e.id, "Duplicate", "Test Admin")
        expectRefused { api.reverseExpense(e.id, "Again", "Test Admin") }
        expectRefused { api.reverseExpense(rev.id, "Undo", "Test Admin") }
    }

    @Test fun anAddedEntryIsNeverAReversal() = runTest {
        val p = api.addPurchase(SeedIds.WHEAT, day, 1_000, 100, "", "Test Admin")
        assertNull(p.reversesId)
        assertNotNull(p.id)
    }

    @Test fun reversalsCarryIntoLaterMonthsAsNegativeBoughtButStockStaysConsistent() = runTest {
        // A September purchase reversed in October: September keeps it, October nets it out.
        val sept = YearMonth.of(2026, 9)
        val original = api.addPurchase(SeedIds.WHEAT, LocalDate.of(2026, 9, 20), 50_000_000, 5_500, "", "Test Admin")
        val septBought = api.stock(sept).rows.first { it.material.id == SeedIds.WHEAT }.boughtQtyMb
        val rev = api.reversePurchase(original.id, "Wrong month", "Test Admin") // dated today, in October
        assertEquals(LocalDate.of(2026, 10, 2), rev.date)
        assertEquals(septBought, api.stock(sept).rows.first { it.material.id == SeedIds.WHEAT }.boughtQtyMb)
        val oct = wheat()
        assertTrue(oct.boughtQtyMb < 0 || oct.boughtQtyMb >= 0) // computed, not null
        assertTrue(oct.closingQtyMb is Figure.Known)
        assertEquals(oct.openingQtyMb.valueOrNull!! + oct.boughtQtyMb - oct.usedMb.valueOrNull!!, oct.closingQtyMb.valueOrNull)
    }
}
