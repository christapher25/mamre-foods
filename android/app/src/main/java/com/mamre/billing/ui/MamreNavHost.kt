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
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.domain.auth.routeAllowed
import com.mamre.billing.domain.auth.startDestinationFor
import com.mamre.billing.ui.admin.AdminBottomBar
import com.mamre.billing.ui.admin.AdminRoutes
import com.mamre.billing.ui.admin.adminGraph
import com.mamre.billing.ui.admin.showsBottomBar
import com.mamre.billing.ui.admin.tabOf
import com.mamre.billing.ui.login.LoginScreen
import com.mamre.billing.ui.worker.workerGraph
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(session: SessionManager) : ViewModel() {
    val role = session.role
}

/**
 * Login when there is no session, otherwise the home of the stored role, so a restart lands on
 * the right experience (Doc 2 s10). The worker and the admin each have their own graph, and
 * the guard sends anyone who ends up on a route of the other graph (or on Login while signed
 * in) back to their own start. When the session ends (logout, or a refresh refused after a
 * 401) the whole back stack is cleared to Login. The admin also gets a bottom bar of five tabs.
 */
@Composable
fun MamreNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val role by appViewModel.role.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val start = remember {
        when (appViewModel.role.value) {
            Role.WORKER -> Routes.WORKER_GRAPH
            Role.ADMIN -> Routes.ADMIN_GRAPH
            null -> Routes.LOGIN
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    LaunchedEffect(role) {
        navController.currentBackStackEntryFlow.collect { entry ->
            if (!routeAllowed(role, entry.destination.route)) navController.goToStart(role)
        }
    }

    Column(Modifier.fillMaxSize()) {
        NavHost(navController, startDestination = start, modifier = Modifier.weight(1f)) {
            composable(Routes.LOGIN) { LoginScreen() }
            workerGraph(navController)
            adminGraph(navController)
        }
        if (role == Role.ADMIN && showsBottomBar(route)) {
            AdminBottomBar(selected = tabOf(route), onSelect = { tab ->
                navController.navigate(tab.route) {
                    popUpTo(AdminRoutes.DASHBOARD) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            })
        }
    }
}

/** Clears the back stack and opens the start of this role, or Login when there is none. */
private fun NavController.goToStart(role: Role?) {
    val target = role?.let(::startDestinationFor) ?: Routes.LOGIN
    navigate(target) { popUpTo(0) { inclusive = true } }
}
