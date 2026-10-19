package com.mamre.billing.ui

import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.worker.WorkerRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PIN screen is the SINGLE gate (Doc 2 s6, owner addition A) and is drawn INSTEAD of the content, not as a route inside it:
 * screenFor says which of the gate, the PIN screen and the content is drawn, and the content is drawn only when the app is
 * unlocked and any entry gate is open. navTarget keeps the area rules inside the content.
 */
class NavPolicyLockTest {
    private val release = Features(showAnalytics = false)
    private val analytics = Features(showAnalytics = true)

    @Test fun lockedMeansThePinScreenWhateverElseIsTrue() {
        for (hasGate in listOf(false, true)) {
            assertEquals(AppScreen.PIN, screenFor(hasGate, gateOpen = true, locked = true))
        }
        assertEquals(AppScreen.PIN, screenFor(hasGate = false, gateOpen = false, locked = true)) // no gate: gateOpen is moot
    }

    @Test fun theContentIsDrawnOnlyWhenUnlockedAndTheGateIsOpen() {
        assertEquals(AppScreen.CONTENT, screenFor(hasGate = false, gateOpen = true, locked = false))
        assertEquals(AppScreen.CONTENT, screenFor(hasGate = true, gateOpen = true, locked = false))
        for (hasGate in listOf(false, true)) for (gateOpen in listOf(false, true)) for (locked in listOf(false, true)) {
            val shown = screenFor(hasGate, gateOpen, locked)
            val open = (!hasGate || gateOpen) && !locked
            assertEquals("hasGate=$hasGate gateOpen=$gateOpen locked=$locked", open, shown == AppScreen.CONTENT)
        }
    }

    @Test fun aClosedGateComesBeforeThePin() {
        assertEquals(AppScreen.GATE, screenFor(hasGate = true, gateOpen = false, locked = true))
        assertEquals(AppScreen.GATE, screenFor(hasGate = true, gateOpen = false, locked = false))
    }

    @Test fun thePinScreenIsNotARouteOfEitherArea() {
        val everyRoute = AdminRoutes.all + listOf(Routes.SALES_HOME, WorkerRoutes.TODAY, WorkerRoutes.INVOICE_CUSTOMER, WorkerRoutes.RECEIPT)
        assertTrue(everyRoute.none { it == "pin" || it.endsWith("/pin") })    }

    @Test fun insideTheContentTheNormalAreaRulesApply() {
        assertNull(navTarget(Area.SALES, release, WorkerRoutes.TODAY))
        assertEquals(Routes.SALES_HOME, navTarget(Area.SALES, release, AdminRoutes.SETTINGS))
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, WorkerRoutes.TODAY))
        assertEquals(AdminRoutes.SALES, navTarget(Area.ADMIN, release, AdminRoutes.DASHBOARD))
        assertEquals(AdminRoutes.DASHBOARD, navTarget(Area.ADMIN, analytics, "somewhere/else"))
        assertEquals(Routes.SALES_HOME, navTarget(Area.SALES, release, null))
    }
}
