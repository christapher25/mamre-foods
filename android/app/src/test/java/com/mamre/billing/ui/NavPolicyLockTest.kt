package com.mamre.billing.ui

import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.domain.auth.appStartDestination
import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.worker.WorkerRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PIN screen is the SINGLE gate (Doc 2 s6, owner addition A): while the app is locked, every route of the Sales area and
 * of the Admin area, any unknown route and "no route" all resolve to the PIN screen; so no deep link, task switch or back-stack
 * entry can show another screen. The policy is the one function the navigation host calls.
 */
class NavPolicyLockTest {
    private val pin = Routes.PIN
    private val locked = LockGate(pin, locked = true)
    private val unlocked = LockGate(pin, locked = false)
    private val release = Features(showAnalytics = false)
    private val analytics = Features(showAnalytics = true)

    private val everyRoute: List<String?> = buildList {
        addAll(AdminRoutes.all)
        addAll(
            listOf(
                Routes.SALES_GRAPH, Routes.SALES_HOME, Routes.ADMIN_GRAPH, Routes.ADMIN_HOME, Routes.ADMIN_COSTING,
                WorkerRoutes.INVOICE_GRAPH, WorkerRoutes.INVOICE_CUSTOMER, WorkerRoutes.INVOICE_BUILD, WorkerRoutes.INVOICE_PAYMENT,
                WorkerRoutes.INVOICE_CONFIRM, WorkerRoutes.PAYMENT_GRAPH, WorkerRoutes.PAYMENT_CUSTOMER, WorkerRoutes.PAYMENT_FORM,
                WorkerRoutes.RETURN_GRAPH, WorkerRoutes.RETURN_CUSTOMER, WorkerRoutes.RETURN_FORM, WorkerRoutes.RETURN_DONE,
                WorkerRoutes.TODAY, WorkerRoutes.RECEIPT, WorkerRoutes.receipt(WorkerRoutes.KIND_INVOICE, "some-id"),
                "admin/sales/some-id", "somewhere/else", "", "login",
            ),
        )
        add(null)
    }

    @Test fun withTheLockOnEveryRouteOfBothAreasResolvesToThePinScreen() {
        for (area in Area.entries) for (features in listOf(release, analytics)) for (route in everyRoute) {
            assertEquals("$area $route", pin, navTarget(area, features, null, locked, route))
        }
    }

    @Test fun theAreaThatIsOpenMakesNoDifferenceWhileLocked() {
        for (route in everyRoute) assertEquals(navTarget(Area.SALES, release, null, locked, route), navTarget(Area.ADMIN, release, null, locked, route))
    }

    @Test fun onThePinScreenWhileLockedThereIsNowhereToGo() {
        for (area in Area.entries) assertNull(navTarget(area, release, null, locked, pin))
    }

    @Test fun anOpenGateInFrontStillSendsALockedAppToThePinScreen() {
        val open = GateState("login", open = true)
        for (route in everyRoute + "login") assertEquals(pin, navTarget(Area.SALES, release, open, locked, route).let { it ?: pin })
        assertEquals(pin, navTarget(Area.SALES, release, open, locked, "login")) // the login hands over to the PIN, not to a screen
    }

    @Test fun aClosedGateComesBeforeThePin() {
        val closed = GateState("login", open = false)
        for (route in everyRoute) assertEquals("login", navTarget(Area.SALES, release, closed, locked, route).let { it ?: "login" })
        assertEquals("login", navTarget(Area.SALES, release, closed, locked, pin)) // the PIN screen cannot be reached before the demo login
        assertNull(navTarget(Area.SALES, release, closed, locked, "login"))
    }

    @Test fun onceUnlockedThePinScreenHandsOverToTheStartOfTheOpenArea() {
        assertEquals(Routes.SALES_HOME, navTarget(Area.SALES, release, null, unlocked, pin))
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, null, unlocked, pin))
        assertEquals(AdminRoutes.DASHBOARD, navTarget(Area.ADMIN, analytics, null, unlocked, pin))
    }

    @Test fun whenUnlockedTheNormalAreaRulesStillApply() {
        assertNull(navTarget(Area.SALES, release, null, unlocked, WorkerRoutes.TODAY))
        assertEquals(Routes.SALES_HOME, navTarget(Area.SALES, release, null, unlocked, AdminRoutes.SETTINGS))
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, null, unlocked, WorkerRoutes.TODAY))
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, null, unlocked, AdminRoutes.DASHBOARD))
    }

    @Test fun theReleaseStartIsThePinScreenBeforeTheSalesHome() {
        assertEquals(Routes.PIN, appStartDestination(null))
        assertTrue(Routes.PIN != Routes.SALES_HOME && Routes.PIN != Routes.SALES_GRAPH)
        assertEquals("login", appStartDestination("login")) // the debug build: the demo login first, then the PIN
    }

    @Test fun thePinRouteIsInNeitherArea() {
        assertTrue(!Routes.PIN.startsWith("sales") && !Routes.PIN.startsWith("admin"))
    }
}
