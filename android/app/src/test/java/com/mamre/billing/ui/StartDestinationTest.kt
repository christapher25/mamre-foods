package com.mamre.billing.ui

import androidx.navigation.NavGraphBuilder
import com.mamre.billing.data.startup.AppReadiness
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.AreaState
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.domain.auth.appStartDestination
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry seam (owner refinement C): the main navigation starts at the Sales Home with no reference to a login screen; a
 * build that binds an entry gate (the debug demo login) puts one screen in front of it. Both wirings are tested through the
 * AppViewModel, which is what the navigation host reads.
 */
class StartDestinationTest {
    private val Ready = object : AppReadiness {
        override val ready: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class FakeGate : EntryGate {
        override val route = "gate-screen"
        private val _open = MutableStateFlow(false)
        override val open: StateFlow<Boolean> = _open
        var closed = 0
        override fun register(builder: NavGraphBuilder) = Unit
        override fun close() {
            closed++
            _open.value = false
        }
        fun let() {
            _open.value = true
        }
    }

    @Test fun withoutAGateTheAppStartsAtTheSalesHomeAndIsOpen() {
        val vm = AppViewModel(AreaState(), Optional.empty(), Features(showAnalytics = false), Ready)
        assertNull(vm.gate)
        assertTrue(vm.gateOpen.value)
        assertEquals(Routes.SALES_GRAPH, appStartDestination(vm.gate?.route))
        assertEquals(Area.SALES, vm.area.value)
    }

    @Test fun withAGateTheAppStartsAtTheGateAndStaysClosedUntilItOpens() {
        val gate = FakeGate()
        val vm = AppViewModel(AreaState(), Optional.of(gate), Features(showAnalytics = false), Ready)
        assertEquals("gate-screen", appStartDestination(vm.gate?.route))
        assertFalse(vm.gateOpen.value)
        gate.let()
        assertTrue(vm.gateOpen.value)
        vm.gate!!.close()
        assertFalse(vm.gateOpen.value)
        assertEquals(1, gate.closed)
    }

    @Test fun theGateNeverChangesWhichAreaOpensByItself() {
        val areas = AreaState()
        val vm = AppViewModel(areas, Optional.of(FakeGate()), Features(showAnalytics = false), Ready)
        assertEquals(Area.SALES, vm.area.value)
        vm.openArea(Area.ADMIN)
        assertEquals(Area.ADMIN, vm.area.value)
    }

    @Test fun theStartRouteOfTheMainGraphIsNeverALoginRoute() {
        assertEquals("sales", appStartDestination(null))
        assertFalse(appStartDestination(null).contains("login", ignoreCase = true))
    }

    @Test fun theFirstScreenWaitsOnTheDatabaseBeingOpen() {
        val flag = MutableStateFlow(false)
        val vm = AppViewModel(AreaState(), Optional.empty(), Features(showAnalytics = false), object : AppReadiness {
            override val ready: StateFlow<Boolean> = flag
        })
        assertFalse(vm.ready.value)
        flag.value = true
        assertTrue(vm.ready.value)
    }
}
