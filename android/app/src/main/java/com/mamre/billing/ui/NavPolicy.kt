package com.mamre.billing.ui

import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.routeAllowed
import com.mamre.billing.domain.auth.startDestinationFor

/** The entry gate's state as the navigation sees it: its screen and whether it has let the Owner through. */
data class GateState(val route: String, val open: Boolean)

/**
 * THE navigation policy, one pure function the navigation host calls after every change (a move, an area switch, the gate
 * opening or closing). Returns the route to go to, clearing the back stack, or null to stay where we are:
 *  - a closed gate keeps the app on the gate's screen;
 *  - an open gate sends the Owner on to the start of the open area;
 *  - a route outside the open area, or one that analytics-off hides (Dashboard, Costing), goes back to the area's start.
 * The only way from one area to the other is therefore the explicit area switch, which changes [area] (AreaState.open).
 */
fun navTarget(area: Area, features: Features, gate: GateState?, current: String?): String? {
    val start = startDestinationFor(area, features)
    if (gate != null && !gate.open) return gate.route.takeIf { current != gate.route }
    if (gate != null && current == gate.route) return start
    return if (routeAllowed(area, current, features)) null else start.takeIf { it != current }
}
