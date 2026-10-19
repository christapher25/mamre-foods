package com.mamre.billing.data.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Sharing the export (Doc 2 s5.3, s6): the files go to cacheDir/exports (old files deleted first) and leave through
 * ACTION_SEND_MULTIPLE with content:// addresses from androidx.core's FileProvider: no storage permission, no INTERNET.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExportShareTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var cache: File

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        cache = File(context.cacheDir, "test-" + System.nanoTime()).also { it.mkdirs() }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        cache.deleteRecursively()
        File(context.cacheDir, ExportWriter.DIR).deleteRecursively()
    }

    private fun bundle() = ExportBundle(
        listOf(
            ExportFile("customers.csv", "id,name\r\n1,\"Mario's \"\"Pizza\"\", Plano\"\r\n2,Café 中\r\n"),
            ExportFile("invoices.csv", "id\r\n"),
            ExportFile("manifest.txt", "data_version: 1\r\n"),
        ),
    )

    @Test fun theWriterDeletesOldFilesFirstAndWritesExactlyTheBundle() {
        val dir = File(cache, ExportWriter.DIR).also { it.mkdirs() }
        File(dir, "customers.csv").writeText("OLD CUSTOMER DATA")
        File(dir, "stale.csv").writeText("stale")
        File(dir, "nested").mkdirs()
        File(dir, "nested/old.txt").writeText("old")
        val written = ExportWriter(cache).write(bundle())
        assertEquals(listOf("customers.csv", "invoices.csv", "manifest.txt"), written.map { it.name })
        assertEquals(setOf("customers.csv", "invoices.csv", "manifest.txt"), dir.list()!!.toSet()) // nothing old is left
        for ((file, source) in written.zip(bundle().files)) assertEquals(File(dir, source.name), file)
    }

    @Test fun theBytesAreUtf8WithNoByteOrderMarkAndTheCrlfIsKept() {
        val written = ExportWriter(cache).write(bundle())
        val bytes = written.first().readBytes()
        assertArrayEquals(bundle().files.first().text.toByteArray(Charsets.UTF_8), bytes)
        assertFalse(bytes[0] == 0xEF.toByte())
        assertTrue(String(bytes, Charsets.UTF_8).contains("\r\n"))
    }

    @Test fun anExportWithNothingToWriteStillClearsTheOldFiles() {
        val dir = File(cache, ExportWriter.DIR).also { it.mkdirs() }
        File(dir, "customers.csv").writeText("OLD")
        ExportWriter(cache).write(ExportBundle(emptyList()))
        assertEquals(0, dir.list()!!.size)
    }

    private fun addressOf(file: File): Uri = Uri.parse("content://" + context.packageName + ".exports/exports/" + file.name)

    @Test fun theShareIntentIsSendMultipleWithContentAddressesAndAReadGrant() {
        val files = ExportWriter(context.cacheDir).write(bundle())
        val intent = ExportShare.intent(context, files, ::addressOf)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("text/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(files.map { it.name }, uris.map { it.lastPathSegment })
        assertTrue(uris.all { it.scheme == "content" && it.authority == context.packageName + ".exports" })
        val clip: ClipData = intent.clipData!!
        assertEquals(3, clip.itemCount) // the grant reaches every file, not only the first
        assertEquals(uris, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
    }

    @Test fun anExportWithNoFilesCannotBeShared() {
        try {
            ExportShare.intent(context, emptyList(), ::addressOf)
            fail("nothing to share")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nothing to share"))
        }
    }

    /** androidx.core's FileProvider matches folders with "/" and so cannot run on a Windows JVM; on Linux (CI) this runs, and the emulator proves it on the phone. */
    @Test fun theRealFileProviderHandsOutTheBytesThatWereWritten() {
        assumeTrue("FileProvider needs a POSIX path on the JVM", File.separatorChar == '/')
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { f -> (f.get(null) as MutableMap<*, *>).clear() }
        val files = ExportWriter(context.cacheDir).write(bundle())
        val intent = ExportShare.intent(context, files)
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(files.map { it.name }, uris.map { it.lastPathSegment })
        assertEquals("content", uris.first().scheme)
        assertFalse(uris.any { it.toString().startsWith("file:") })
        val read = context.contentResolver.openInputStream(uris.first())!!.use { it.readBytes() }
        assertArrayEquals(files.first().readBytes(), read)
    }

    @Test fun theViewModelWritesTheRealDatabaseToTheCacheAndOffersTheFilesToShare() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        val vm = ExportViewModel(ExportBuilder(w.db, w.clock), ExportWriter(cache), w.recordExport, w.settings)
        assertTrue(vm.ui.value is ExportState.Idle)
        vm.export().join()
        val ready = vm.ui.value as ExportState.Ready
        assertEquals(ExportTables.fileNames + ExportTables.MANIFEST, ready.files.map { it.name })
        assertTrue(ready.files.all { it.parentFile == File(cache, ExportWriter.DIR) && it.length() > 0 })
        vm.shareLaunched().join()
        assertTrue(vm.ui.value is ExportState.Idle)
        w.db.close()
    }

    @Test fun aFailedExportShowsAMessageAndSharesNothing() = runBlocking {
        val w = World(TestDatabase.inMemory()).also { it.seed() }
        w.db.close() // reading a closed database fails
        val vm = ExportViewModel(ExportBuilder(w.db, w.clock), ExportWriter(cache), w.recordExport, w.settings)
        vm.export().join()
        val failed = vm.ui.value as ExportState.Failed
        assertTrue(failed.message.isNotBlank())
        assertFalse(File(cache, ExportWriter.DIR).exists() && File(cache, ExportWriter.DIR).list()!!.isNotEmpty())
    }

    @Test fun theWarningSaysThePlainTextFilesHoldCustomerData() {
        assertTrue(EXPORT_WARNING.contains("plain text"))
        assertTrue(EXPORT_WARNING.contains("customer"))
        assertTrue(EXPORT_WARNING.contains("trust"))
    }
}
