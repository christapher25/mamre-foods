package com.mamre.billing.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one-tap switch to the Admin area can carry a first screen to open there (the Export card on the Sales Home). */
class AreaSwitchTest {
    @Test fun switchingToAdminWithARouteHandsItOverExactlyOnce() {
        val area = AreaState()
        area.open(Area.ADMIN, then = "admin/more/settings/export")
        assertEquals(Area.ADMIN, area.area.value)
        assertEquals("admin/more/settings/export", area.takeOpeningRoute())
        assertNull(area.takeOpeningRoute())
    }

    @Test fun aPlainSwitchHasNoOpeningRoute() {
        val area = AreaState()
        area.open(Area.ADMIN)
        assertNull(area.takeOpeningRoute())
    }

    @Test fun switchingBackToSalesDropsAnUnusedOpeningRoute() {
        val area = AreaState()
        area.open(Area.ADMIN, then = "admin/more/settings/export")
        area.open(Area.SALES)
        assertNull(area.takeOpeningRoute())
    }

    @Test fun theOpeningRouteIsAnAllowedAdminRouteSoNoSecondLoginIsNeeded() {
        val features = Features(showAnalytics = false)
        assertEquals(true, routeAllowed(Area.ADMIN, "admin/more/settings/export", features))
        assertEquals(false, routeAllowed(Area.SALES, "admin/more/settings/export", features))
    }
}
