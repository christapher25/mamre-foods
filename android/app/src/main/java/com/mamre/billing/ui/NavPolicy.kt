package com.mamre.billing.ui

import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.domain.auth.routeAllowed
import com.mamre.billing.domain.auth.startDestinationFor

/** What the window shows. Exactly one of these is drawn; the content is never drawn under or behind the other two. */
enum class AppScreen {
    /** The debug build's entry screen (its login), in front of everything. */
    GATE,

    /** The PIN screen: the single gate in front of the Sales and Admin areas (Doc 2 s6). */
    PIN,

    /** The Sales or Admin area with its navigation. */
    CONTENT,
}

/**
 * Which screen to draw. A closed entry gate comes first (the debug login comes before the PIN); while the app is LOCKED the PIN
 * screen is drawn INSTEAD of the content: the content (the navigation host, its screens and their view models) is not composed
 * at all, so no frame of it can show, and nothing of it starts, at a process start with a restored back stack or after an idle lock.
 */
fun screenFor(hasGate: Boolean, gateOpen: Boolean, locked: Boolean): AppScreen = when {
    hasGate && !gateOpen -> AppScreen.GATE
    locked -> AppScreen.PIN
    else -> AppScreen.CONTENT
}

/** The nested graph an area lives in: the navigation host starts there (a start destination must be a direct child of the host). */
fun areaGraph(area: Area): String = when (area) {
    Area.SALES -> Routes.SALES_GRAPH
    Area.ADMIN -> Routes.ADMIN_GRAPH
}

/**
 * The navigation policy inside the content, one pure function the host calls after every change (a move or an area switch).
 * Returns the route to go to, clearing the back stack, or null to stay where we are: a route outside the open area, or one that
 * analytics-off hides (Dashboard, Costing), goes back to the area's start. The only way from one area to the other is the
 * explicit area switch, which changes [area] (AreaState.open).
 */
fun navTarget(area: Area, features: Features, current: String?): String? {
    val start = startDestinationFor(area, features)
    return if (routeAllowed(area, current, features)) null else start.takeIf { it != current }
}
