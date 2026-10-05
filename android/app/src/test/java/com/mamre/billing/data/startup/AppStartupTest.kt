package com.mamre.billing.data.startup

import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.ReferenceSeed
import com.mamre.billing.data.local.RoomUnitOfWork
import com.mamre.billing.data.local.TestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Start-up never holds the main thread for long: only the first-run reference data (Doc 2 s5.2) is awaited before the first
 * screen; whatever else a build adds (the debug sample data) runs in the background and reports when it is done. A debug
 * build that replayed seven months of history inside Application.onCreate was killed by Android ("failed to complete startup").
 */
@RunWith(RobolectricTestRunner::class)
class AppStartupTest {
    private lateinit var db: MamreDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun open() {
        db = TestDatabase.inMemory()
    }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    private fun startup(vararg tasks: StartupTask) = AppStartup(ReferenceSeed(db, RoomUnitOfWork(db)), tasks.toSet())

    @Test fun theReferenceDataExistsWhenRunReferenceReturnsEvenWhileAnExtraTaskIsStillWorking() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val s = startup(StartupTask { release.await() })
        withTimeout(10_000) { s.runReference() } // must not wait for the extra task: it only returns when the reference data exists
        assertTrue("the four customer types exist", db.customerTypeDao().getAll().size == 4)
        s.startExtras(scope)
        assertFalse("the extra task has not finished", s.extrasDone.value)
        release.complete(Unit)
        withTimeout(10_000) { s.extrasDone.first { it } }
        assertTrue(s.extrasDone.value)
    }

    @Test fun extraTasksNeverStartBeforeTheReferenceDataIsThere() = runBlocking {
        var typesSeenByTheTask = -1
        val s = startup(StartupTask { typesSeenByTheTask = db.customerTypeDao().getAll().size })
        s.runReference()
        s.startExtras(scope)
        withTimeout(10_000) { s.extrasDone.first { it } }
        assertEquals(4, typesSeenByTheTask)
    }

    @Test fun aBuildWithNoExtraTasksIsReadyAtOnce() = runBlocking {
        val s = startup() // the release wiring
        assertTrue(s.extrasDone.value)
        s.runReference()
        s.startExtras(scope)
        assertTrue(s.extrasDone.value)
    }

    @Test fun aFailingExtraTaskIsLoudAndNeverReportsDone() = runBlocking {
        val failed = CompletableDeferred<Throwable>()
        val loud = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failed.complete(e) })
        val s = startup(StartupTask { error("a rule refused a sample event") })
        s.runReference()
        s.startExtras(loud)
        val error = withTimeout(10_000) { failed.await() }
        assertEquals("a rule refused a sample event", error.message)
        assertFalse("a failed task is never reported as done", s.extrasDone.value)
        loud.cancel()
    }

    @Test fun runningTheReferenceSeedTwiceChangesNothing() = runBlocking {
        val s = startup()
        s.runReference()
        val before = db.productDao().getAll().size
        s.runReference()
        assertEquals(before, db.productDao().getAll().size)
    }
}
