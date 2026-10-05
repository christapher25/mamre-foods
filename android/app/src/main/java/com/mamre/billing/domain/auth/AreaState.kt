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

    fun open(area: Area) {
        _area.value = area
    }
}
