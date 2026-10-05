package com.mamre.billing.data.startup

import com.mamre.billing.data.local.MamreDatabase
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
 * Start-up never blocks the main thread (review finding 11): nothing runs in Application.onCreate but a launch. The database
 * is opened (and, on first run, created WITH its reference data in one transaction) in the background, and the first screen
 * waits on [AppStartup.ready]. Whatever else a build adds (the debug sample data) runs after that, in the background, and
 * reports through [AppStartup.extrasDone]. A debug build that replayed seven months of history inside Application.onCreate
 * was killed by Android ("failed to complete startup").
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

    private fun startup(vararg tasks: StartupTask) = AppStartup(db, tasks.toSet())

    @Test fun theReferenceDataExistsWhenReadyEvenWhileAnExtraTaskIsStillWorking() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val s = startup(StartupTask { release.await() })
        withTimeout(10_000) { s.openDatabase() } // must not wait for the extra task
        assertTrue(s.ready.value)
        assertTrue("the four customer types exist", db.customerTypeDao().getAll().size == 4)
        s.startExtras(scope)
        assertFalse("the extra task has not finished", s.extrasDone.value)
        release.complete(Unit)
        withTimeout(10_000) { s.extrasDone.first { it } }
        assertTrue(s.extrasDone.value)
    }

    @Test fun theStartIsNotReadyUntilTheDatabaseIsOpen() = runBlocking {
        val s = startup()
        assertFalse("nothing is open before start", s.ready.value)
        s.start(scope)
        withTimeout(10_000) { s.ready.first { it } }
        assertEquals(4, db.customerTypeDao().count())
    }

    @Test fun extraTasksNeverStartBeforeTheReferenceDataIsThere() = runBlocking {
        var typesSeenByTheTask = -1
        val s = startup(StartupTask { typesSeenByTheTask = db.customerTypeDao().getAll().size })
        s.start(scope) // opens first, then runs the extra tasks
        withTimeout(10_000) { s.extrasDone.first { it } }
        assertEquals(4, typesSeenByTheTask)
    }

    @Test fun aBuildWithNoExtraTasksIsReadyAtOnce() = runBlocking {
        val s = startup() // the release wiring
        assertTrue(s.extrasDone.value)
        s.openDatabase()
        s.startExtras(scope)
        assertTrue(s.extrasDone.value)
    }

    @Test fun aFailingExtraTaskIsLoudAndNeverReportsDone() = runBlocking {
        val failed = CompletableDeferred<Throwable>()
        val loud = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failed.complete(e) })
        val s = startup(StartupTask { error("a rule refused a sample event") })
        s.openDatabase()
        s.startExtras(loud)
        val error = withTimeout(10_000) { failed.await() }
        assertEquals("a rule refused a sample event", error.message)
        assertFalse("a failed task is never reported as done", s.extrasDone.value)
        loud.cancel()
    }

    @Test fun openingTheDatabaseTwiceChangesNothing() = runBlocking {
        val s = startup()
        s.openDatabase()
        val before = db.productDao().getAll().size
        s.openDatabase()
        assertEquals(before, db.productDao().getAll().size)
    }
}
