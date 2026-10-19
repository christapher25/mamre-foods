package com.mamre.billing.domain.auth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which area is open. Version 1 opens the Sales area; [open] is the one explicit switch between the two. */
@Singleton
class AreaState @Inject constructor() {
    private val _area = MutableStateFlow(Area.SALES)
    val area: StateFlow<Area> = _area.asStateFlow()

    private var openingRoute: String? = null

    /** Switches area. [then] is a route of that area to open first (the Export card on the Sales Home); a plain switch has none. */
    fun open(area: Area, then: String? = null) {
        openingRoute = then.takeIf { area == Area.ADMIN }
        _area.value = area
    }

    /** The route to open after the last switch, once; the next call returns null. */
    fun takeOpeningRoute(): String? = openingRoute.also { openingRoute = null }
}
