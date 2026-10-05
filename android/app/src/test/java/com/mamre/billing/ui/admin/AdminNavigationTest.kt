package com.mamre.billing.ui.admin

import com.mamre.billing.domain.admin.sharePercent
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.routeAllowed
import com.mamre.billing.domain.auth.startDestinationFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminNavigationTest {
    @Test fun everyAdminRouteBelongsToTheAdminAreaAndNoSalesRouteCanReachOne() {
        assertTrue(AdminRoutes.all.size >= 15)
        for (r in AdminRoutes.all) {
            assertTrue(r, routeAllowed(Area.ADMIN, r, Features(true)))
            assertFalse(r, routeAllowed(Area.SALES, r, Features(true)))
        }
    }

    @Test fun theAdminStartsOnTheDashboard() {
        assertEquals(AdminRoutes.DASHBOARD, startDestinationFor(Area.ADMIN, Features(true)))
    }

    @Test fun routesAreUniqueSoNoScreenShadowsAnother() {
        assertEquals(AdminRoutes.all.size, AdminRoutes.all.toSet().size)
    }

    @Test fun theFiveTabsFollowTheirScreens() {
        assertEquals(AdminTab.DASHBOARD, tabOf(AdminRoutes.DASHBOARD))
        assertEquals(AdminTab.SALES, tabOf(AdminRoutes.SALES))
        assertEquals(AdminTab.SALES, tabOf(AdminRoutes.SALES_DETAIL))
        assertEquals(AdminTab.CUSTOMERS, tabOf(AdminRoutes.CUSTOMER_DETAIL))
        assertEquals(AdminTab.CUSTOMERS, tabOf(AdminRoutes.CUSTOMER_NEW))
        assertEquals(AdminTab.PRICES, tabOf(AdminRoutes.PRICE_SET))
        for (r in listOf(AdminRoutes.MORE, AdminRoutes.COSTING, AdminRoutes.EXPENSES, AdminRoutes.RETURNS, AdminRoutes.BALANCES, AdminRoutes.SETTINGS)) {
            assertEquals(r, AdminTab.MORE, tabOf(r))
        }
        assertNull(tabOf("sales/home"))
        assertNull(tabOf(null))
    }

    @Test fun theBottomBarShowsOnTabsAndMoreScreensButNotOnDetailsOrForms() {
        for (r in listOf(AdminRoutes.DASHBOARD, AdminRoutes.SALES, AdminRoutes.CUSTOMERS, AdminRoutes.PRICES, AdminRoutes.MORE,
            AdminRoutes.COSTING, AdminRoutes.EXPENSES, AdminRoutes.RETURNS, AdminRoutes.BALANCES, AdminRoutes.SETTINGS)) {
            assertTrue(r, showsBottomBar(r))
        }
        for (r in listOf(AdminRoutes.SALES_DETAIL, AdminRoutes.CUSTOMER_DETAIL, AdminRoutes.CUSTOMER_NEW, AdminRoutes.CUSTOMER_EDIT,
            AdminRoutes.OVERRIDE_SET, AdminRoutes.PRICE_SET, AdminRoutes.PURCHASE_ADD, AdminRoutes.EXPENSE_ADD, AdminRoutes.DAMAGE_ADD)) {
            assertFalse(r, showsBottomBar(r))
        }
        assertFalse(showsBottomBar(null))
        assertFalse(showsBottomBar("login"))
    }

    @Test fun sharePercentUsesIntegersAndNeverDividesByZero() {
        assertEquals(0, sharePercent(0, 0))
        assertEquals(50, sharePercent(500, 1000))
        assertEquals(78, sharePercent(7800, 10_000))
        assertEquals(100, sharePercent(1000, 1000))
        assertEquals(33, sharePercent(1, 3))
        assertEquals(0, sharePercent(-50, 1000)) // a negative slice draws no bar
        assertEquals(100, sharePercent(2000, 1000)) // never past the track
    }
}
