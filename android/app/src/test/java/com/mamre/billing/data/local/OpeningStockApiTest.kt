package com.mamre.billing.data.local

import com.mamre.billing.domain.usecase.RuleException
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B3, Doc 1 s9.4 and A-18: the Admin API sets and reads opening stock through SetOpeningStock, read back AFTER THE DATABASE IS
 * CLOSED AND OPENED AGAIN. Opening stock is entered once per material; a second entry is refused and changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class OpeningStockApiTest {
    private val file = "opening-stock-api-test.db"
    private val october = YearMonth.of(2026, 10)

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun first(): World = World(TestDatabase.file(file)).also { it.seed() }

    private fun reopen(w: World): World {
        w.db.close()
        return World(TestDatabase.file(file), w.clock)
    }

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    @Test fun nothingIsSetOnAFreshInstall() = runBlocking {
        val w = first()
        assertTrue(w.admin.openingStock().isEmpty())
        w.db.close()
    }

    @Test fun anEntryIsSavedAndReadBackWithItsMonthAfterReopening() = runBlocking {
        var w = first()
        w.admin.setOpeningStock(ReferenceIds.MATERIAL_SORBATE, october, 12_500_000L, 44_050L)
        w = reopen(w)
        val row = w.admin.openingStock().single()
        assertEquals(ReferenceIds.MATERIAL_SORBATE, row.materialId)
        assertEquals(october, row.month)
        assertEquals(12_500_000L, row.qtyMb)
        assertEquals(44_050L, row.valueCents)
        w.db.close()
    }

    @Test fun theUseCaseWritesTheChangeLogEntry() = runBlocking {
        val w = first()
        val before = w.changeLog.all().size
        w.admin.setOpeningStock(ReferenceIds.MATERIAL_SORBATE, october, 1_000L, 100L)
        val added = w.changeLog.all().drop(before)
        assertEquals(1, added.size)
        assertTrue(added.single().what.startsWith("Opening stock "))
        w.db.close()
    }

    @Test fun aSecondEntryForTheSameMaterialIsRefusedAndChangesNothing() = runBlocking {
        var w = first()
        w.admin.setOpeningStock(ReferenceIds.MATERIAL_SORBATE, october, 1_000L, 100L)
        val log = w.changeLog.all().size
        val message = refused { w.admin.setOpeningStock(ReferenceIds.MATERIAL_SORBATE, october.plusMonths(1), 9_000L, 900L) }
        assertTrue(message, message.contains("is already set"))
        w = reopen(w)
        val row = w.admin.openingStock().single()
        assertEquals(1_000L, row.qtyMb)
        assertEquals(100L, row.valueCents)
        assertEquals(october, row.month)
        assertEquals(log, w.changeLog.all().size)
        w.db.close()
    }

    @Test fun anotherMaterialCanStillBeSetAfterOneIsSet() = runBlocking {
        val w = first()
        val materials = w.admin.materials()
        w.admin.setOpeningStock(materials[0].id, october, 1_000L, 100L)
        w.admin.setOpeningStock(materials[1].id, october, 2_000L, 200L)
        assertEquals(setOf(materials[0].id, materials[1].id), w.admin.openingStock().map { it.materialId }.toSet())
        w.db.close()
    }

    @Test fun zeroIsAllowedAndANegativeQuantityOrValueIsRefusedAndChangesNothing() = runBlocking {
        val w = first()
        val materials = w.admin.materials()
        w.admin.setOpeningStock(materials[0].id, october, 0L, 0L)
        assertEquals(0L, w.admin.openingStock().single().qtyMb)
        assertTrue(refused { w.admin.setOpeningStock(materials[1].id, october, -1L, 100L) }.contains("negative"))
        assertTrue(refused { w.admin.setOpeningStock(materials[1].id, october, 100L, -1L) }.contains("negative"))
        assertEquals(1, w.admin.openingStock().size)
        w.db.close()
    }

    @Test fun anUnknownMaterialIsRefused() = runBlocking {
        val w = first()
        assertTrue(refused { w.admin.setOpeningStock("no-such-material", october, 1_000L, 100L) }.contains("material"))
        assertTrue(w.admin.openingStock().isEmpty())
        w.db.close()
    }
}
