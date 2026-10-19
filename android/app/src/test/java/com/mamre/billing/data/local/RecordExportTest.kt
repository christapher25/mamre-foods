package com.mamre.billing.data.local

import com.mamre.billing.domain.export.parseLastExport
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RecordExport (Doc 1 s11.1, A-26): writes the setting last_export_at, epoch milliseconds UTC, in one transaction, on the real
 * database. A system record, not a business change: no change log entry, no schema change.
 */
@RunWith(RobolectricTestRunner::class)
class RecordExportTest {
    private val file = "record-export-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    @Test fun theSettingIsMissingUntilAnExportIsRecorded() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        assertNull(w.settings.get(SettingKeys.LAST_EXPORT_AT))
        w.db.close()
    }

    @Test fun theKeyIsLastExportAt() = assertEquals("last_export_at", SettingKeys.LAST_EXPORT_AT)

    @Test fun itWritesTheClocksEpochMillisOnce() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        w.clock.set("2026-10-19T15:10:00Z")
        w.recordExport()
        assertEquals(Instant.parse("2026-10-19T15:10:00Z").toEpochMilli().toString(), w.settings.get(SettingKeys.LAST_EXPORT_AT))
        assertEquals(1, rowsWithKey(w, SettingKeys.LAST_EXPORT_AT))
        w.db.close()
    }

    @Test fun aLaterExportOverwritesTheEarlierOneInTheSameRow() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        w.clock.set("2026-10-12T09:00:00Z")
        w.recordExport()
        w.clock.set("2026-10-19T15:10:00Z")
        w.recordExport()
        assertEquals(Instant.parse("2026-10-19T15:10:00Z").toEpochMilli(), parseLastExport(w.settings.get(SettingKeys.LAST_EXPORT_AT)))
        assertEquals(1, rowsWithKey(w, SettingKeys.LAST_EXPORT_AT))
        w.db.close()
    }

    @Test fun theValueSurvivesClosingAndReopeningTheDatabase() = runBlocking {
        val first = World(TestDatabase.file(file)).also { it.seed() }
        first.clock.set("2026-10-19T15:10:00Z")
        first.recordExport()
        first.db.close()
        val again = World(TestDatabase.file(file))
        assertEquals(Instant.parse("2026-10-19T15:10:00Z").toEpochMilli(), parseLastExport(again.settings.get(SettingKeys.LAST_EXPORT_AT)))
        again.db.close()
    }

    @Test fun itIsASystemRecordSoNoChangeLogEntryIsMadeAndNoOtherSettingMoves() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        val logBefore = w.books.changeLog().size
        val settingsBefore = w.settings.all()
        w.recordExport()
        assertEquals(logBefore, w.books.changeLog().size)
        assertEquals(settingsBefore, w.settings.all() - SettingKeys.LAST_EXPORT_AT)
        w.db.close()
    }

    @Test fun aFailedWriteLeavesNoValueBehind() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        val failing = com.mamre.billing.domain.usecase.RecordExport(
            object : UnitOfWork {
                override suspend fun <T> run(block: suspend () -> T): T = w.unitOfWork.run { block(); error("boom") }
            },
            w.settings, w.clock,
        )
        try {
            failing()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("boom"))
        }
        assertNull(w.settings.get(SettingKeys.LAST_EXPORT_AT))
        w.db.close()
    }

    private fun rowsWithKey(w: World, key: String): Int =
        w.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM settings WHERE key = ?", arrayOf(key)).use { it.moveToFirst(); it.getInt(0) }
}
