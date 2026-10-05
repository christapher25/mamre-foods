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
    const val ADMIN_HOME = "admin/home"
}

/** Where an area opens. */
fun startDestinationFor(area: Area): String = when (area) {
    Area.SALES -> Routes.SALES_HOME
    Area.ADMIN -> Routes.ADMIN_HOME
}

/**
 * The guard works on the OPEN area: a Sales-area screen can reach only Sales routes and an Admin-area screen only Admin
 * routes. The only way across is the explicit area switch (AreaState.open), which restarts the navigation in the other
 * area. Doc 2 s6: the areas are separate navigation graphs with a route guard.
 */
fun routeAllowed(area: Area, route: String?): Boolean {
    if (route == null) return false
    return when (area) {
        Area.SALES -> inGraph(route, Routes.SALES_GRAPH)
        Area.ADMIN -> inGraph(route, Routes.ADMIN_GRAPH)
    }
}

private fun inGraph(route: String, graph: String) = route == graph || route.startsWith("$graph/")

/**
 * Where the app starts. Version 1 starts at the Sales Home with no screen in front of it (the PIN lock comes in the next
 * step). A build may install an entry gate that puts one screen in front (the debug build's demo login): then the app
 * starts at the gate's route, and the gate decides when the Sales Home is reached.
 */
fun appStartDestination(gateRoute: String?): String = gateRoute ?: Routes.SALES_GRAPH
