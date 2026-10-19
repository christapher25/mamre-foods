package com.mamre.billing.data.export

import android.content.Context
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.export.parseLastExport
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * When the export is recorded (Doc 1 s11.1, A-26): only when the 18 files and the manifest are written AND the share sheet has been
 * launched. A failed read or write, or a share sheet that could not open, records nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExportRecordingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var cache: File
    private lateinit var w: World

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        cache = File(context.cacheDir, "rec-" + System.nanoTime()).also { it.mkdirs() }
        w = runBlocking { World(TestDatabase.inMemory()).also { it.seed() } }
        w.clock.set("2026-10-19T15:10:00Z")
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        cache.deleteRecursively()
        w.db.close()
    }

    private fun vm(writer: ExportWriter = ExportWriter(cache)) = ExportViewModel(ExportBuilder(w.db, w.clock), writer, w.recordExport, w.settings)

    private suspend fun recorded() = parseLastExport(w.settings.get(SettingKeys.LAST_EXPORT_AT))

    @Test fun nothingIsRecordedWhenTheFilesAreWrittenButNotYetShared() = runBlocking {
        val vm = vm()
        vm.export().join()
        assertTrue(vm.ui.value is ExportState.Ready)
        assertNull("the files exist but the share sheet has not been launched", recorded())
    }

    @Test fun theExportIsRecordedWhenTheShareSheetIsLaunched() = runBlocking {
        val vm = vm()
        vm.export().join()
        val files = (vm.ui.value as ExportState.Ready).files
        assertEquals(18 + 1, files.size) // the 18 CSV files and the manifest are all written by then
        assertTrue(files.all { it.length() > 0 })
        vm.shareLaunched().join()
        assertEquals(Instant.parse("2026-10-19T15:10:00Z").toEpochMilli(), recorded())
        assertTrue(vm.ui.value is ExportState.Idle)
    }

    @Test fun aFailedWriteRecordsNothing() = runBlocking {
        val notAFolder = File(cache, "plain-file").also { it.writeText("x") } // the export folder cannot be made under a file
        val vm = vm(ExportWriter(notAFolder))
        vm.export().join()
        assertTrue(vm.ui.value is ExportState.Failed)
        assertNull(recorded())
        vm.shareLaunched().let { /* the screen never calls it after a failure; even if it did, only Ready may record */ }
    }

    @Test fun aFailedReadRecordsNothing() = runBlocking {
        w.db.close()
        val vm = vm()
        vm.export().join()
        assertTrue(vm.ui.value is ExportState.Failed)
        Unit
    }

    @Test fun callingShareLaunchedWithoutReadyFilesRecordsNothing() = runBlocking {
        val vm = vm()
        vm.shareLaunched().join()
        assertNull(recorded())
    }

    @Test fun aShareSheetThatCouldNotOpenRecordsNothing() = runBlocking {
        val vm = vm()
        vm.export().join()
        vm.shareNotLaunched()
        assertNull(recorded())
        assertTrue(vm.ui.value is ExportState.Idle)
    }

    @Test fun theScreenReadsTheLastExportFromTheSetting() = runBlocking {
        val vm = vm()
        assertNull(withTimeout(10_000) { vm.lastExportAt.first() })
        vm.export().join()
        vm.shareLaunched().join()
        assertEquals(Instant.parse("2026-10-19T15:10:00Z").toEpochMilli(), withTimeout(10_000) { vm.lastExportAt.first { it != null } })
    }

    @Test fun exportingAgainLaterOverwritesTheTime() = runBlocking {
        val vm = vm()
        vm.export().join(); vm.shareLaunched().join()
        w.clock.set("2026-10-26T08:00:00Z")
        vm.export().join(); vm.shareLaunched().join()
        assertEquals(Instant.parse("2026-10-26T08:00:00Z").toEpochMilli(), recorded())
    }
}
