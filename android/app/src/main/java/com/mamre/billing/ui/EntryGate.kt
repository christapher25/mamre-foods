package com.mamre.billing.ui

import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.flow.StateFlow

/**
 * An optional screen in front of the app. A release build has none and starts at the Sales Home; another build may bind
 * one (Hilt optional binding), and the navigation then starts at its [route] until it is [open].
 */
interface EntryGate {
    /** The route of the screen the gate shows. */
    val route: String

    /** True while the gate lets the Owner through. */
    val open: StateFlow<Boolean>

    /** Adds the gate's screen to the navigation graph. */
    fun register(builder: NavGraphBuilder)

    /** Closes the gate again (log out): the navigation goes back to its screen. */
    fun close()
}
