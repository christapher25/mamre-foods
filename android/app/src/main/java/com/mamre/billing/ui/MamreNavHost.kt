package com.mamre.billing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mamre.billing.data.startup.AppReadiness
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.AreaState
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.domain.auth.startDestinationFor
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.ui.admin.AdminBottomBar
import com.mamre.billing.ui.admin.adminGraph
import com.mamre.billing.ui.admin.adminTabs
import com.mamre.billing.ui.admin.showsBottomBar
import com.mamre.billing.ui.admin.tabOf
import com.mamre.billing.ui.lock.PinScreen
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
    readiness: AppReadiness,
    private val appLock: AppLock,
) : ViewModel() {
    /** False until the database is open: the first screen is a short loading screen (Doc 2 s5.2). */
    val ready: StateFlow<Boolean> = readiness.ready

    val gate: EntryGate? = gate.orElse(null)
    val area: StateFlow<Area> = areaState.area

    /** True when there is no gate or the gate has let the Owner through. */
    val gateOpen: StateFlow<Boolean> = this.gate?.open ?: MutableStateFlow(true)

    /** True until the right PIN is given; true at every process start (Doc 2 s6). */
    val locked: StateFlow<Boolean> = appLock.locked

    fun openArea(area: Area) = areaState.open(area)
}

/**
 * Version 1 starts at the PIN screen (Doc 2 s6, s10). A build can put an entry gate in front (the debug login screen). The
 * Sales area and the Admin area are separate graphs with a route guard on the OPEN area: the only way across is the explicit
 * area switch, which restarts the navigation in the other area. The Admin area also gets a bottom bar.
 *
 * While the app is locked the PIN screen is drawn INSTEAD of the content ([screenFor], [AppFrame]): the navigation host is not
 * composed, so the Sales and Admin screens and their view models do not exist while locked.
 */
@Composable
fun MamreNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val ready by appViewModel.ready.collectAsStateWithLifecycle()
    if (!ready) {
        StartingScreen()
        return
    }
    val area by appViewModel.area.collectAsStateWithLifecycle()
    val gateOpen by appViewModel.gateOpen.collectAsStateWithLifecycle()
    val locked by appViewModel.locked.collectAsStateWithLifecycle()
    val gate = appViewModel.gate
    AppFrame(
        screen = screenFor(hasGate = gate != null, gateOpen = gateOpen, locked = locked),
        gate = { gate?.let { GateHost(it) } },
        pin = { PinScreen() },
        content = {
            AppContent(
                area = area,
                features = appViewModel.features,
                onOpenAdmin = { appViewModel.openArea(Area.ADMIN) },
                onOpenSales = { appViewModel.openArea(Area.SALES) },
            )
        },
    )
}

/** Draws exactly ONE of the three: a branch, not a layer, so the two that are not shown are not composed. */
@Composable
fun AppFrame(screen: AppScreen, gate: @Composable () -> Unit, pin: @Composable () -> Unit, content: @Composable () -> Unit) {
    val contentStore: ContentStore = viewModel()
    // Not showing the content (locked, or the gate closed): the view models of the content stop and their drafts are gone.
    if (screen != AppScreen.CONTENT) SideEffect { contentStore.clear() }
    when (screen) {
        AppScreen.GATE -> gate()
        AppScreen.PIN -> pin()
        AppScreen.CONTENT -> content()
    }
}

/** The debug login: a navigation host of its own with only the gate's screen. */
@Composable
private fun GateHost(gate: EntryGate) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = gate.route) { gate.register(this) }
}

/**
 * The Sales and Admin areas with their navigation. Their view models live in the activity's [ContentStore]: they survive a
 * rotation, are cleared when the app locks or the gate closes, and a later unlock starts them afresh.
 */
@Composable
fun AppContent(area: Area, features: Features, onOpenAdmin: () -> Unit, onOpenSales: () -> Unit) {
    RetainedContent {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route
        val start = remember { areaGraph(area) }

        // Switching area and every move afterwards: the one policy (navTarget) decides.
        LaunchedEffect(area) {
            navTarget(area, features, navController.currentDestination?.route)?.let { navController.goTo(it) }
            navController.currentBackStackEntryFlow.collect { entry ->
                navTarget(area, features, entry.destination.route)?.let { navController.goTo(it) }
            }
        }

        Column(Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = start, modifier = Modifier.weight(1f)) {
                workerGraph(navController, onOpenAdmin = onOpenAdmin)
                adminGraph(navController, features, onOpenSales = onOpenSales)
            }
            if (area == Area.ADMIN && showsBottomBar(route)) {
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
}

/**
 * The ViewModelStore of the Sales and Admin content. It lives in a view model of the ACTIVITY, so it survives the activity being
 * recreated (rotation, font scale, dark mode, language) and a bill, payment or return in progress is not lost. It is cleared only
 * when the app locks or the gate closes ([AppFrame]) and when the activity is finished for good; the process dying takes it too.
 */
class ContentStore : ViewModel() {
    private val store = ViewModelStore()
    val owner: ViewModelStoreOwner = object : ViewModelStoreOwner { override val viewModelStore: ViewModelStore get() = store }

    /** Stops every view model of the content; the store can be used again for the next unlock. */
    fun clear() = store.clear()

    override fun onCleared() = store.clear()
}

/**
 * Gives [content] the activity's retained [ContentStore] (the navigation host keeps every screen's view model in the store it
 * finds). Leaving the composition does NOT clear it, because that also happens when the activity is recreated; [AppFrame] clears
 * it when the app locks.
 */
@Composable
fun RetainedContent(content: @Composable () -> Unit) {
    val contentStore: ContentStore = viewModel()
    CompositionLocalProvider(LocalViewModelStoreOwner provides contentStore.owner) { content() }
}

/** Clears the back stack and opens [target]. */
private fun NavController.goTo(target: String) {
    if (currentDestination?.route == target) return
    navigate(target) { popUpTo(0) { inclusive = true } }
}
