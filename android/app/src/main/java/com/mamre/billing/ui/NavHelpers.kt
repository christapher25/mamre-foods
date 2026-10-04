package com.mamre.billing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/** A flow's ViewModel lives as long as its nested graph, so Back between steps keeps the input. */
@Composable
internal inline fun <reified VM : ViewModel> NavController.graphViewModel(entry: NavBackStackEntry, graph: String): VM {
    val parent = remember(entry) { getBackStackEntry(graph) }
    return hiltViewModel(parent)
}
