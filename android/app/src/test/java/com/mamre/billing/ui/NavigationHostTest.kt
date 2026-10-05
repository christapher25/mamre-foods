package com.mamre.billing.ui

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.domain.auth.appStartDestination
import com.mamre.billing.domain.auth.startDestinationFor
import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.admin.adminGraph
import com.mamre.billing.ui.worker.WorkerRoutes
import com.mamre.billing.ui.worker.workerGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The navigation host's wiring, on the REAL navigation graphs (Sales and Admin) and a real NavHostController (review
 * finding 9): the release start is the Sales Home; with analytics off the Dashboard and Costing do not exist in the graph; and a
 * Sales route cannot reach an Admin route (or the reverse) except through the explicit area switch. The host calls the same
 * [navTarget] policy that these tests call after every move.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationHostTest {
    private class Owner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun host(features: Features, gate: GateState? = null): NavHostController {
        val controller = NavHostController(context)
        controller.setLifecycleOwner(Owner())
        controller.setViewModelStore(ViewModelStore())
        controller.navigatorProvider.addNavigator(ComposeNavigator())
        val graph = controller.createGraph(startDestination = appStartDestination(gate?.route)) {
            gate?.let { g -> composable(g.route) { } }
            workerGraph(controller, onOpenAdmin = {})
            adminGraph(controller, features, onOpenSales = {})
        }
        controller.setGraph(graph, null)
        return controller
    }

    /** What the host does after any change: ask the policy where to go, and go there (clearing the back stack). */
    private fun NavHostController.settle(area: Area, features: Features, gate: GateState? = null) {
        navTarget(area, features, gate, currentDestination?.route)?.let { target ->
            navigate(target) { popUpTo(0) { inclusive = true } }
        }
    }

    /** findNode only looks at one level; the Admin screens live in the nested Admin graph. */
    private fun NavGraph.deepFind(route: String): NavDestination? =
        findNode(route) ?: filterIsInstance<NavGraph>().firstNotNullOfOrNull { it.deepFind(route) }

    private val release = Features(showAnalytics = false)
    private val analytics = Features(showAnalytics = true)

    @Test fun theReleaseStartIsTheSalesHomeWithNoLoginInFront() {
        val c = host(release)
        assertEquals(Routes.SALES_HOME, c.currentDestination?.route)
        assertEquals(Routes.SALES_GRAPH, appStartDestination(null))
        c.settle(Area.SALES, release)
        assertEquals(Routes.SALES_HOME, c.currentDestination?.route) // nothing to do: the policy keeps it
    }

    @Test fun withAnalyticsOffTheDashboardAndCostingAreNotInTheGraphAtAll() {
        val c = host(release)
        assertNull(c.graph.deepFind(AdminRoutes.DASHBOARD))
        assertNull(c.graph.deepFind(AdminRoutes.COSTING))
        assertNotNull(c.graph.deepFind(AdminRoutes.SALES))
        assertNotNull(c.graph.deepFind(AdminRoutes.SETTINGS))
        val onBuild = host(analytics)
        assertNotNull(onBuild.graph.deepFind(AdminRoutes.DASHBOARD))
        assertNotNull(onBuild.graph.deepFind(AdminRoutes.COSTING))
    }

    @Test fun aSalesRouteCannotReachAnAdminRouteAndTheHostBringsItBackToTheSalesHome() {
        val c = host(release)
        c.navigate(AdminRoutes.SALES) // something tries to cross
        assertEquals(AdminRoutes.SALES, c.currentDestination?.route)
        c.settle(Area.SALES, release) // the Sales area is open: the policy refuses it
        assertEquals(Routes.SALES_HOME, c.currentDestination?.route)
    }

    @Test fun anAdminRouteCannotReachASalesRouteAndTheHostBringsItBackToTheAdminStart() {
        val c = host(release)
        c.settle(Area.ADMIN, release) // the explicit switch happened: the host restarts in the Admin area
        assertEquals(startDestinationFor(Area.ADMIN, release), c.currentDestination?.route)
        assertEquals(AdminRoutes.SALES, c.currentDestination?.route) // version 1: the Admin area opens on Sales
        c.navigate(WorkerRoutes.TODAY)
        c.settle(Area.ADMIN, release)
        assertEquals(AdminRoutes.SALES, c.currentDestination?.route)
    }

    @Test fun onlyTheExplicitAreaSwitchMovesBetweenTheAreas() {
        val c = host(release)
        assertEquals(Routes.SALES_HOME, c.currentDestination?.route)
        // Without a switch the Admin area never opens, whatever route is asked for.
        c.navigate(AdminRoutes.SETTINGS)
        c.settle(Area.SALES, release)
        assertEquals(Routes.SALES_HOME, c.currentDestination?.route)
        // The switch changes the open area; the policy then moves the host to the new area's start, and back again.
        c.settle(Area.ADMIN, release)
        assertEquals(AdminRoutes.SALES, c.currentDestination?.route)
        c.settle(Area.SALES, release)
        assertEquals(Routes.SALES_HOME, c.currentDestination?.route)
    }

    @Test fun withAnalyticsOffTheDashboardIsRefusedEvenIfSomethingTriesToOpenIt() {
        val c = host(analytics) // a graph that DOES have the Dashboard...
        c.settle(Area.ADMIN, analytics)
        assertEquals(AdminRoutes.DASHBOARD, c.currentDestination?.route)
        // ...but the policy of a release build (flag off) never lets the Admin area sit on it.
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, null, AdminRoutes.DASHBOARD))
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, null, AdminRoutes.COSTING))
    }

    @Test fun aGateInFrontKeepsTheAppAtTheGateUntilItOpensThenGoesToTheOpenArea() {
        val closed = GateState("gate-screen", open = false)
        val c = host(release, closed)
        assertEquals("gate-screen", c.currentDestination?.route)
        assertNull(navTarget(Area.SALES, release, closed, "gate-screen"))
        assertEquals("gate-screen", navTarget(Area.SALES, release, closed, Routes.SALES_HOME)) // anything else goes back to the gate
        val open = GateState("gate-screen", open = true)
        assertEquals(Routes.SALES_HOME, navTarget(Area.SALES, release, open, "gate-screen"))
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, open, "gate-screen"))
        assertFalse(navTarget(Area.SALES, release, open, Routes.SALES_HOME) != null)
    }
}
