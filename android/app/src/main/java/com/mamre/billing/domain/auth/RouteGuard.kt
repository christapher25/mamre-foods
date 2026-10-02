package com.mamre.billing.domain.auth

/** Route names. Each role has its own graph; every destination lives under its graph prefix. */
object Routes {
    const val LOGIN = "login"
    const val WORKER_GRAPH = "worker"
    const val ADMIN_GRAPH = "admin"
    const val WORKER_HOME = "worker/home"
    const val ADMIN_HOME = "admin/home"
}

/** Where a signed-in role lands, also after a restart (Doc 2 s10). */
fun startDestinationFor(role: Role): String = when (role) {
    Role.WORKER -> Routes.WORKER_HOME
    Role.ADMIN -> Routes.ADMIN_HOME
}

/**
 * The guard: a worker can reach only worker routes and an admin only admin routes. With no
 * role (signed out) only Login is reachable. A signed-in role never sits on Login.
 * Doc 2 s8: roles are also enforced by the server; this is the UI layer of that rule.
 */
fun routeAllowed(role: Role?, route: String?): Boolean {
    if (route == null) return false
    return when (role) {
        null -> route == Routes.LOGIN
        Role.WORKER -> inGraph(route, Routes.WORKER_GRAPH)
        Role.ADMIN -> inGraph(route, Routes.ADMIN_GRAPH)
    }
}

private fun inGraph(route: String, graph: String) = route == graph || route.startsWith("$graph/")
