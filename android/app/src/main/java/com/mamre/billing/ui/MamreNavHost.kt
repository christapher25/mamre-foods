package com.mamre.billing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.AreaState
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.appStartDestination
import com.mamre.billing.domain.auth.startDestinationFor
import com.mamre.billing.ui.admin.AdminBottomBar
import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.admin.adminGraph
import com.mamre.billing.ui.admin.adminTabs
import com.mamre.billing.ui.admin.showsBottomBar
import com.mamre.billing.ui.admin.tabOf
import com.mamre.billing.ui.worker.workerGraph
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class AppViewModel @Inject constructor(
    private val areaState: AreaState,
    gate: Optional<EntryGate>,
    val features: Features,
) : ViewModel() {
    val gate: EntryGate? = gate.orElse(null)
    val area: StateFlow<Area> = areaState.area

    /** True when there is no gate or the gate has let the Owner through. */
    val gateOpen: StateFlow<Boolean> = this.gate?.open ?: MutableStateFlow(true)

    fun openArea(area: Area) = areaState.open(area)
}

/**
 * Version 1 starts at the Sales Home (Doc 2 s10). A build can put an entry gate in front (the debug login screen); the
 * navigation then starts at the gate's screen and goes to the open area once the gate opens. The Sales area and the Admin
 * area are separate graphs with a route guard on the OPEN area: the only way across is the explicit area switch, which
 * restarts the navigation in the other area. The Admin area also gets a bottom bar.
 */
@Composable
fun MamreNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val area by appViewModel.area.collectAsStateWithLifecycle()
    val gateOpen by appViewModel.gateOpen.collectAsStateWithLifecycle()
    val gate = appViewModel.gate
    val features = appViewModel.features
    val navController = rememberNavController()
    val start = remember { appStartDestination(gate?.route) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    // Switching area, opening the gate or closing it, and every move afterwards: the one policy (navTarget) decides.
    val gateState = gate?.let { GateState(it.route, gateOpen) }
    LaunchedEffect(area, gateOpen) {
        navTarget(area, features, gateState, navController.currentDestination?.route ?: gate?.route)?.let { navController.goTo(it) }
        navController.currentBackStackEntryFlow.collect { entry ->
            navTarget(area, features, gateState, entry.destination.route)?.let { navController.goTo(it) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        NavHost(navController, startDestination = start, modifier = Modifier.weight(1f)) {
            gate?.register(this)
            workerGraph(navController, onOpenAdmin = { appViewModel.openArea(Area.ADMIN) })
            adminGraph(navController, features, onOpenSales = { appViewModel.openArea(Area.SALES) })
        }
        if (area == Area.ADMIN && gateOpen && showsBottomBar(route)) {
            AdminBottomBar(tabs = adminTabs(features), selected = tabOf(route), onSelect = { tab ->
                navController.navigate(tab.route) {
                    popUpTo(startDestinationFor(Area.ADMIN, features)) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            })
        }
    }
}

/** Clears the back stack and opens [target]. */
private fun NavController.goTo(target: String) {
    if (currentDestination?.route == target) return
    navigate(target) { popUpTo(0) { inclusive = true } }
}
