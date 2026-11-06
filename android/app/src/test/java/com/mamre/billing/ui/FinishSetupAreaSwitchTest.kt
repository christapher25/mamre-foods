package com.mamre.billing.ui

import com.mamre.billing.data.local.MutableClock
import com.mamre.billing.data.startup.AppReadiness
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.AreaState
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.routeAllowed
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.ui.admin.AdminRoutes
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Doc 1 s2.1 FR-3 and Doc 2 s6: a Finish setup card tap enters the Admin area through the explicit area switch (the same one the
 * "Admin area" tile and the Export card use) and only then opens Settings or Prices; the Sales graph can never navigate into an
 * Admin route itself.
 */
class FinishSetupAreaSwitchTest {
    private val ready = object : AppReadiness {
        override val ready: StateFlow<Boolean> = MutableStateFlow(true)
    }
    private val features = Features(showAnalytics = false)

    private fun vm(areas: AreaState) = AppViewModel(areas, Optional.empty(), features, ready, AppLock(MutableClock()), com.mamre.billing.domain.security.CurrentUser())

    @Test fun aCardTapSwitchesToTheAdminAreaAndHandsOverTheScreenToOpenOnce() {
        for (route in listOf(AdminRoutes.SETTINGS, AdminRoutes.PRICES)) {
            val areas = AreaState()
            val vm = vm(areas)
            assertEquals(Area.SALES, vm.area.value)
            vm.openAdminRoute(route)
            assertEquals(Area.ADMIN, vm.area.value) // through the area switch, not a navigate inside the Sales graph
            assertEquals(route, areas.takeOpeningRoute())
            assertNull(areas.takeOpeningRoute())
        }
    }

    @Test fun theGuardStillRefusesTheSettingsAndPricesRoutesInsideTheSalesArea() {
        assertEquals(false, routeAllowed(Area.SALES, AdminRoutes.SETTINGS, features))
        assertEquals(false, routeAllowed(Area.SALES, AdminRoutes.PRICES, features))
        assertEquals(true, routeAllowed(Area.ADMIN, AdminRoutes.SETTINGS, features))
        assertEquals(true, routeAllowed(Area.ADMIN, AdminRoutes.PRICES, features))
    }
}
