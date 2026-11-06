package com.mamre.billing.domain.auth

import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.navTargetFor
import com.mamre.billing.ui.worker.WorkerRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B8 item e: a SALESMAN reaches the Sales area only; an ADMIN reaches both. Enforced by the guard, not only by hidden buttons. */
class RoleGuardTest {
    private val on = Features(showAnalytics = true)
    private val off = Features(showAnalytics = false)

    @Test fun aSalesmanCanReachEverySalesRoute() {
        for (r in listOf(Routes.SALES_GRAPH, Routes.SALES_HOME, WorkerRoutes.INVOICE_BUILD, WorkerRoutes.TODAY, WorkerRoutes.RECEIPT)) {
            assertTrue(r, routeAllowedFor(Role.SALESMAN, Area.SALES, r, on))
        }
    }

    @Test fun aSalesmanCannotReachAnyAdminRouteEvenWithTheAdminAreaOpen() {
        for (features in listOf(on, off)) {
            for (r in AdminRoutes.all + listOf(Routes.ADMIN_GRAPH, Routes.ADMIN_SALES, AdminRoutes.SETTINGS, AdminRoutes.USERS)) {
                assertFalse(r, routeAllowedFor(Role.SALESMAN, Area.ADMIN, r, features))
                assertFalse(r, routeAllowedFor(Role.SALESMAN, Area.SALES, r, features))
            }
        }
    }

    @Test fun anAdminKeepsTheAreaRulesOfToday() {
        assertTrue(routeAllowedFor(Role.ADMIN, Area.SALES, Routes.SALES_HOME, on))
        assertTrue(routeAllowedFor(Role.ADMIN, Area.ADMIN, AdminRoutes.SETTINGS, on))
        assertFalse(routeAllowedFor(Role.ADMIN, Area.SALES, AdminRoutes.SETTINGS, on))
        assertFalse(routeAllowedFor(Role.ADMIN, Area.ADMIN, Routes.SALES_HOME, on))
        assertFalse(routeAllowedFor(Role.ADMIN, Area.ADMIN, null, on))
    }

    @Test fun aSalesmanWhoIsInTheAdminAreaIsSentBackToSalesHome() {
        assertEquals(Routes.SALES_HOME, navTargetFor(Role.SALESMAN, Area.ADMIN, on, AdminRoutes.SETTINGS))
        assertNull(navTargetFor(Role.SALESMAN, Area.SALES, on, Routes.SALES_HOME))
    }

    @Test fun theAreaASalesmanMayOpenIsOnlySales() {
        assertTrue(Role.SALESMAN.mayOpen(Area.SALES))
        assertFalse(Role.SALESMAN.mayOpen(Area.ADMIN))
        assertTrue(Role.ADMIN.mayOpen(Area.SALES))
        assertTrue(Role.ADMIN.mayOpen(Area.ADMIN))
    }

    @Test fun theRoleIsReadFromItsStoredNameAndAnythingElseFailsClosed() {
        assertEquals(Role.ADMIN, Role.fromStored("ADMIN"))
        assertEquals(Role.SALESMAN, Role.fromStored("SALESMAN"))
        assertNull(Role.fromStored("admin "))
        assertNull(Role.fromStored(""))
    }

    @Test fun aSalesmanWhoLogsInWhileTheAreaIsStillAdminStillReachesTheSalesRoutes() {
        // Found on the emulator: the admin logged out from the Admin area, the salesman logged in, and every Sales tile did nothing.
        for (r in listOf(Routes.SALES_HOME, WorkerRoutes.INVOICE_CUSTOMER, WorkerRoutes.INVOICE_BUILD, WorkerRoutes.TODAY)) {
            assertTrue(r, routeAllowedFor(Role.SALESMAN, Area.ADMIN, r, on))
            assertEquals(null, navTargetFor(Role.SALESMAN, Area.ADMIN, on, r))
        }
        assertFalse(routeAllowedFor(Role.SALESMAN, Area.ADMIN, AdminRoutes.SETTINGS, on))
    }

    @Test fun aSalesmanCannotReachAdminProductsInAnyWay() {
        // B5: Admin > More > Products, Add product and Edit product are Admin routes.
        val routes = listOf(AdminRoutes.PRODUCTS, AdminRoutes.PRODUCT_NEW, AdminRoutes.productEdit("some-id"), AdminRoutes.PRODUCT_EDIT)
        for (features in listOf(on, off)) for (r in routes) {
            assertFalse(r, routeAllowedFor(Role.SALESMAN, Area.ADMIN, r, features))
            assertFalse(r, routeAllowedFor(Role.SALESMAN, Area.SALES, r, features))
            assertEquals(Routes.SALES_HOME, navTargetFor(Role.SALESMAN, Area.ADMIN, features, r))
            assertTrue(r, routeAllowedFor(Role.ADMIN, Area.ADMIN, r, features))
        }
        assertTrue(AdminRoutes.PRODUCTS in com.mamre.billing.ui.admin.moreRoutes(off))
    }
}
