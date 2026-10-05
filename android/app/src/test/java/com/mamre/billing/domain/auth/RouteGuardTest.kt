package com.mamre.billing.domain.auth

import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.admin.AdminTab
import com.mamre.billing.ui.admin.adminTabs
import com.mamre.billing.ui.admin.moreRoutes
import com.mamre.billing.ui.worker.WorkerRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The area guard (Doc 2 s6, s10): version 1 has one Owner and two areas, and the guard works on the open AREA. */
class RouteGuardTest {
    private val on = Features(showAnalytics = true)
    private val off = Features(showAnalytics = false)
    private val salesRoutes = listOf(
        Routes.SALES_GRAPH, Routes.SALES_HOME, WorkerRoutes.INVOICE_GRAPH, WorkerRoutes.INVOICE_CUSTOMER, WorkerRoutes.INVOICE_BUILD,
        WorkerRoutes.INVOICE_PAYMENT, WorkerRoutes.INVOICE_CONFIRM, WorkerRoutes.PAYMENT_GRAPH, WorkerRoutes.PAYMENT_CUSTOMER,
        WorkerRoutes.PAYMENT_FORM, WorkerRoutes.RETURN_GRAPH, WorkerRoutes.RETURN_CUSTOMER, WorkerRoutes.RETURN_FORM,
        WorkerRoutes.RETURN_DONE, WorkerRoutes.TODAY, WorkerRoutes.RECEIPT, WorkerRoutes.receipt(WorkerRoutes.KIND_INVOICE, "x"),
    )

    @Test fun startDestinationDependsOnlyOnTheArea() {
        assertEquals("sales/home", startDestinationFor(Area.SALES, on))
        assertEquals("admin/home", startDestinationFor(Area.ADMIN, on))
        assertEquals(WorkerRoutes.HOME, startDestinationFor(Area.SALES, on))
        assertEquals(AdminRoutes.DASHBOARD, startDestinationFor(Area.ADMIN, on))
    }

    @Test fun eachStartDestinationIsAllowedForItsOwnAreaOnly() {
        assertTrue(routeAllowed(Area.SALES, startDestinationFor(Area.SALES, on), on))
        assertTrue(routeAllowed(Area.ADMIN, startDestinationFor(Area.ADMIN, on), on))
        assertFalse(routeAllowed(Area.SALES, startDestinationFor(Area.ADMIN, on), on))
        assertFalse(routeAllowed(Area.ADMIN, startDestinationFor(Area.SALES, on), on))
    }

    @Test fun aSalesAreaRouteCanNeverNavigateIntoAnAdminRoute() {
        for (r in AdminRoutes.all + listOf("admin", "admin/home", "admin/costing", "admin/sales/INV-1")) {
            assertFalse(r, routeAllowed(Area.SALES, r, on))
        }
        for (r in salesRoutes) assertTrue(r, routeAllowed(Area.SALES, r, on))
    }

    @Test fun anAdminAreaRouteCanNeverNavigateIntoASalesRoute() {
        for (r in salesRoutes) assertFalse(r, routeAllowed(Area.ADMIN, r, on))
        for (r in AdminRoutes.all) assertTrue(r, routeAllowed(Area.ADMIN, r, on))
    }

    @Test fun prefixLookalikesDoNotSlipThrough() {
        assertFalse(routeAllowed(Area.SALES, "salesman/home", on))
        assertFalse(routeAllowed(Area.SALES, "sales-secret", on))
        assertFalse(routeAllowed(Area.ADMIN, "administrator", on))
        assertFalse(routeAllowed(Area.ADMIN, "admin-secret", on))
    }

    @Test fun noRouteMeansNothingIsAllowed() {
        assertFalse(routeAllowed(Area.SALES, null, on))
        assertFalse(routeAllowed(Area.ADMIN, null, on))
    }

    @Test fun theOnlyWayAcrossIsTheExplicitAreaSwitch() {
        val state = AreaState()
        assertEquals(Area.SALES, state.area.value) // version 1 opens the Sales area
        state.open(Area.ADMIN)
        assertEquals(Area.ADMIN, state.area.value)
        assertTrue(routeAllowed(state.area.value, AdminRoutes.SETTINGS, on))
        assertFalse(routeAllowed(state.area.value, WorkerRoutes.HOME, on))
        state.open(Area.SALES)
        assertEquals(Area.SALES, state.area.value)
        assertTrue(routeAllowed(state.area.value, WorkerRoutes.HOME, on))
        assertFalse(routeAllowed(state.area.value, AdminRoutes.SETTINGS, on))
    }

    // ---------------------------------------------------------------- SHOW_ANALYTICS (Doc 2 s9, A-28): hidden in version 1

    private val analyticsRoutes = listOf(Routes.ADMIN_HOME, Routes.ADMIN_COSTING)

    @Test fun withTheFlagOffTheGuardBlocksTheDashboardAndCostingInTheAdminArea() {
        for (r in analyticsRoutes) {
            assertFalse(r, routeAllowed(Area.ADMIN, r, off))
            assertTrue(r, routeAllowed(Area.ADMIN, r, on))
        }
        for (r in AdminRoutes.all - analyticsRoutes.toSet()) assertTrue(r, routeAllowed(Area.ADMIN, r, off))
    }

    @Test fun withTheFlagOffTheAdminAreaOpensOnSales() {
        assertEquals(AdminRoutes.SALES, startDestinationFor(Area.ADMIN, off))
        assertTrue(routeAllowed(Area.ADMIN, startDestinationFor(Area.ADMIN, off), off))
        assertEquals(AdminRoutes.DASHBOARD, startDestinationFor(Area.ADMIN, on))
        assertEquals(Routes.SALES_HOME, startDestinationFor(Area.SALES, off)) // the Sales area is unaffected
    }

    @Test fun noRouteTabOrMenuItemReachesTheDashboardOrCostingWithTheFlagOff() {
        assertTrue(AdminTab.DASHBOARD !in adminTabs(off))
        assertTrue(AdminTab.DASHBOARD in adminTabs(on))
        assertEquals(listOf(AdminTab.SALES, AdminTab.CUSTOMERS, AdminTab.PRICES, AdminTab.MORE), adminTabs(off))
        assertTrue(AdminRoutes.COSTING !in moreRoutes(off))
        assertTrue(AdminRoutes.COSTING in moreRoutes(on))
        // Every route a tab or the More list can open is one the guard allows.
        for (r in adminTabs(off).map { it.route } + moreRoutes(off)) assertTrue(r, routeAllowed(Area.ADMIN, r, off))
    }

    @Test fun costAndProfitFiguresAreShownOnlyWithTheFlagOn() {
        assertFalse(off.showCosts)
        assertTrue(on.showCosts)
    }
}
