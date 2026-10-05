package com.mamre.billing.domain.auth

/**
 * The two areas of the app (Doc 1 s2, Doc 2 s1.1): the Owner is both salesman and admin, and switches between the Sales
 * area and the Admin area with one tap and no second login. The areas are kept apart in code, so version 2 can give
 * them to different people.
 */
enum class Area { SALES, ADMIN }

/** Route names. Each area has its own graph; every destination lives under its graph prefix. */
object Routes {
    const val SALES_GRAPH = "sales"
    const val ADMIN_GRAPH = "admin"
    const val SALES_HOME = "sales/home"

    /** The Dashboard: the first screen of the Admin area while analytics are shown (version 2). */
    const val ADMIN_HOME = "admin/home"
    const val ADMIN_SALES = "admin/sales"
    const val ADMIN_COSTING = "admin/more/costing"
}

/**
 * What this build shows. Version 1 hides the Dashboard, the Costing screen and every cost or profit figure (Doc 1 A-28,
 * Doc 2 s9, s11): [showAnalytics] is the build flag SHOW_ANALYTICS, true in the debug build and false in release. The code
 * of those screens stays for version 2.
 */
data class Features(val showAnalytics: Boolean) {
    /** Cost, profit and expense-derived figures (Doc 2 I-8: none in version 1). */
    val showCosts: Boolean get() = showAnalytics
}

/** The routes only an analytics build may open. */
private val ANALYTICS_ROUTES = setOf(Routes.ADMIN_HOME, Routes.ADMIN_COSTING)

/** Where an area opens. */
fun startDestinationFor(area: Area, features: Features): String = when (area) {
    Area.SALES -> Routes.SALES_HOME
    Area.ADMIN -> if (features.showAnalytics) Routes.ADMIN_HOME else Routes.ADMIN_SALES
}

/**
 * The guard works on the OPEN area: a Sales-area screen can reach only Sales routes and an Admin-area screen only Admin
 * routes. The only way across is the explicit area switch (AreaState.open), which restarts the navigation in the other
 * area. Doc 2 s6: the areas are separate navigation graphs with a route guard.
 */
fun routeAllowed(area: Area, route: String?, features: Features): Boolean {
    if (route == null) return false
    // With analytics off the Dashboard and Costing do not exist for the guard, whatever area is open.
    if (!features.showAnalytics && route in ANALYTICS_ROUTES) return false
    return when (area) {
        Area.SALES -> inGraph(route, Routes.SALES_GRAPH)
        Area.ADMIN -> inGraph(route, Routes.ADMIN_GRAPH)
    }
}

private fun inGraph(route: String, graph: String) = route == graph || route.startsWith("$graph/")

/**
 * Where the app starts. Version 1 starts at the Sales Home with no screen in front of it (the PIN lock comes in the next
 * step). A build may install an entry gate that puts one screen in front (the debug build's login screen): then the app
 * starts at the gate's route, and the gate decides when the Sales Home is reached.
 */
fun appStartDestination(gateRoute: String?): String = gateRoute ?: Routes.SALES_GRAPH
