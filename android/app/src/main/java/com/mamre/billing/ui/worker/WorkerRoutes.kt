package com.mamre.billing.ui.worker

import com.mamre.billing.domain.auth.Routes

/** Destinations of the worker graph. Every one lives under "worker/" so the guard can see it. */
object WorkerRoutes {
    const val HOME = Routes.WORKER_HOME
    const val SYNC = "worker/sync"
    const val SOON = "worker/soon/{tile}"

    fun soon(tile: HomeTile) = "worker/soon/${tile.name}"
}
