package com.mamre.billing.domain.auth

import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.worker.WorkerRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The area guard (Doc 2 s6, s10): version 1 has one Owner and two areas, and the guard works on the open AREA. */
class RouteGuardTest {
    private val salesRoutes = listOf(
        Routes.SALES_GRAPH, Routes.SALES_HOME, WorkerRoutes.INVOICE_GRAPH, WorkerRoutes.INVOICE_CUSTOMER, WorkerRoutes.INVOICE_BUILD,
        WorkerRoutes.INVOICE_PAYMENT, WorkerRoutes.INVOICE_CONFIRM, WorkerRoutes.PAYMENT_GRAPH, WorkerRoutes.PAYMENT_CUSTOMER,
        WorkerRoutes.PAYMENT_FORM, WorkerRoutes.RETURN_GRAPH, WorkerRoutes.RETURN_CUSTOMER, WorkerRoutes.RETURN_FORM,
        WorkerRoutes.RETURN_DONE, WorkerRoutes.TODAY, WorkerRoutes.RECEIPT, WorkerRoutes.receipt(WorkerRoutes.KIND_INVOICE, "x"),
    )

    @Test fun startDestinationDependsOnlyOnTheArea() {
        assertEquals("sales/home", startDestinationFor(Area.SALES))
        assertEquals("admin/home", startDestinationFor(Area.ADMIN))
        assertEquals(WorkerRoutes.HOME, startDestinationFor(Area.SALES))
        assertEquals(AdminRoutes.DASHBOARD, startDestinationFor(Area.ADMIN))
    }

    @Test fun eachStartDestinationIsAllowedForItsOwnAreaOnly() {
        assertTrue(routeAllowed(Area.SALES, startDestinationFor(Area.SALES)))
        assertTrue(routeAllowed(Area.ADMIN, startDestinationFor(Area.ADMIN)))
        assertFalse(routeAllowed(Area.SALES, startDestinationFor(Area.ADMIN)))
        assertFalse(routeAllowed(Area.ADMIN, startDestinationFor(Area.SALES)))
    }

    @Test fun aSalesAreaRouteCanNeverNavigateIntoAnAdminRoute() {
        for (r in AdminRoutes.all + listOf("admin", "admin/home", "admin/costing", "admin/sales/INV-1")) {
            assertFalse(r, routeAllowed(Area.SALES, r))
        }
        for (r in salesRoutes) assertTrue(r, routeAllowed(Area.SALES, r))
    }

    @Test fun anAdminAreaRouteCanNeverNavigateIntoASalesRoute() {
        for (r in salesRoutes) assertFalse(r, routeAllowed(Area.ADMIN, r))
        for (r in AdminRoutes.all) assertTrue(r, routeAllowed(Area.ADMIN, r))
    }

    @Test fun prefixLookalikesDoNotSlipThrough() {
        assertFalse(routeAllowed(Area.SALES, "salesman/home"))
        assertFalse(routeAllowed(Area.SALES, "sales-secret"))
        assertFalse(routeAllowed(Area.ADMIN, "administrator"))
        assertFalse(routeAllowed(Area.ADMIN, "admin-secret"))
    }

    @Test fun noRouteMeansNothingIsAllowed() {
        assertFalse(routeAllowed(Area.SALES, null))
        assertFalse(routeAllowed(Area.ADMIN, null))
    }

    @Test fun theOnlyWayAcrossIsTheExplicitAreaSwitch() {
        val state = AreaState()
        assertEquals(Area.SALES, state.area.value) // version 1 opens the Sales area
        state.open(Area.ADMIN)
        assertEquals(Area.ADMIN, state.area.value)
        assertTrue(routeAllowed(state.area.value, AdminRoutes.SETTINGS))
        assertFalse(routeAllowed(state.area.value, WorkerRoutes.HOME))
        state.open(Area.SALES)
        assertEquals(Area.SALES, state.area.value)
        assertTrue(routeAllowed(state.area.value, WorkerRoutes.HOME))
        assertFalse(routeAllowed(state.area.value, AdminRoutes.SETTINGS))
    }
}
