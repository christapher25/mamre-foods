package com.mamre.billing.ui.login

import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.ReferenceSeed
import com.mamre.billing.data.local.RoomUnitOfWork
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.startup.AppStartup
import com.mamre.billing.data.startup.StartupTask
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.AreaState
import kotlinx.coroutines.CompletableDeferred
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

/** DEBUG ONLY: the demo login waits for the sample data, then opens the area its account belongs to. */
@RunWith(RobolectricTestRunner::class)
class DemoLoginGateTest {
    private lateinit var db: MamreDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val area = AreaState()

    @Before fun open() {
        db = TestDatabase.inMemory()
    }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    private fun gate(task: StartupTask? = null): Pair<DemoLoginGate, AppStartup> {
        val startup = AppStartup(ReferenceSeed(db, RoomUnitOfWork(db)), setOfNotNull(task))
        return DemoLoginGate(area, startup) to startup
    }

    @Test fun whileTheSampleDataIsBeingPreparedEvenTheRightAccountIsRefused() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val (gate, startup) = gate(StartupTask { release.await() })
        startup.runReference()
        startup.startExtras(scope)
        assertFalse(gate.ready.value)
        assertFalse(gate.signIn("user1", "user1"))
        assertFalse(gate.open.value)
        assertEquals(Area.SALES, area.area.value)
        release.complete(Unit)
        withTimeout(10_000) { startup.extrasDone.first { it } }
        assertTrue(gate.ready.value)
        assertTrue(gate.signIn("admin", "admin5"))
        assertEquals(Area.ADMIN, area.area.value)
        assertTrue(gate.open.value)
    }

    @Test fun onceReadyTheTwoDemoAccountsOpenTheirAreasAndNothingElseDoes() = runBlocking {
        val (gate, startup) = gate()
        startup.runReference()
        assertTrue(gate.ready.value)
        assertFalse(gate.signIn("user1", "wrong"))
        assertFalse(gate.signIn("USER1", "user1"))
        assertFalse(gate.open.value)
        assertTrue(gate.signIn("user1", "user1"))
        assertEquals(Area.SALES, area.area.value)
        gate.close()
        assertFalse(gate.open.value)
    }
}
