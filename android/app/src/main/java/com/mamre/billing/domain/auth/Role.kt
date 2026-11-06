package com.mamre.billing.domain.auth

/**
 * The two kinds of account (B8 items a, e). An ADMIN uses the Sales area and the Admin area; a SALESMAN uses the Sales area only.
 * The role is stored by its name; a stored value that is not exactly one of the two names is not a role (fails closed).
 */
enum class Role {
    ADMIN,
    SALESMAN,
    ;

    fun mayOpen(area: Area): Boolean = when (this) {
        ADMIN -> true
        SALESMAN -> area == Area.SALES
    }

    companion object {
        fun fromStored(value: String?): Role? = entries.firstOrNull { it.name == value }
    }
}

/**
 * [routeAllowed] for a signed-in role: a SALESMAN is refused every route outside the Sales graph, whatever area is open, so a
 * salesman cannot reach the Admin area, Settings, Users, price editing or Export by any route.
 */
fun routeAllowedFor(role: Role, area: Area, route: String?, features: Features): Boolean {
    if (!role.mayOpen(area)) return false
    if (role == Role.SALESMAN && route != null && !(route == Routes.SALES_GRAPH || route.startsWith("${Routes.SALES_GRAPH}/"))) return false
    return routeAllowed(area, route, features)
}
