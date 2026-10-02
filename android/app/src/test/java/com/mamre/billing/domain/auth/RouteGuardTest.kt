package com.mamre.billing.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGuardTest {
    @Test fun startDestinationDependsOnlyOnTheRole() {
        assertEquals("worker/home", startDestinationFor(Role.WORKER))
        assertEquals("admin/home", startDestinationFor(Role.ADMIN))
    }

    @Test fun eachStartDestinationIsAllowedForItsOwnRoleOnly() {
        assertTrue(routeAllowed(Role.WORKER, startDestinationFor(Role.WORKER)))
        assertTrue(routeAllowed(Role.ADMIN, startDestinationFor(Role.ADMIN)))
        assertFalse(routeAllowed(Role.WORKER, startDestinationFor(Role.ADMIN)))
        assertFalse(routeAllowed(Role.ADMIN, startDestinationFor(Role.WORKER)))
    }

    @Test fun aWorkerCanNeverReachAnAdminRoute() {
        for (r in listOf("admin", "admin/home", "admin/costing", "admin/sales/INV-1")) {
            assertFalse(r, routeAllowed(Role.WORKER, r))
        }
        assertTrue(routeAllowed(Role.WORKER, "worker/invoice/customer"))
    }

    @Test fun anAdminCanNeverReachAWorkerRoute() {
        for (r in listOf("worker", "worker/home", "worker/invoice/customer")) {
            assertFalse(r, routeAllowed(Role.ADMIN, r))
        }
        assertTrue(routeAllowed(Role.ADMIN, "admin/prices"))
    }

    @Test fun prefixLookalikesDoNotSlipThrough() {
        assertFalse(routeAllowed(Role.WORKER, "workers/home"))
        assertFalse(routeAllowed(Role.ADMIN, "administrator"))
        assertFalse(routeAllowed(Role.ADMIN, "admin-secret"))
    }

    @Test fun signedOutOnlyReachesLoginAndSignedInNeverSitsOnIt() {
        assertTrue(routeAllowed(null, Routes.LOGIN))
        assertFalse(routeAllowed(null, "worker/home"))
        assertFalse(routeAllowed(null, "admin/home"))
        assertFalse(routeAllowed(Role.WORKER, Routes.LOGIN))
        assertFalse(routeAllowed(Role.ADMIN, Routes.LOGIN))
        assertFalse(routeAllowed(Role.ADMIN, null))
    }

    @Test fun roleParsingAcceptsOnlyTheTwoWireValues() {
        assertEquals(Role.WORKER, Role.parse("worker"))
        assertEquals(Role.ADMIN, Role.parse("admin"))
        for (bad in listOf(null, "", "Admin", "WORKER", "owner", " admin")) assertNull(Role.parse(bad))
    }
}
