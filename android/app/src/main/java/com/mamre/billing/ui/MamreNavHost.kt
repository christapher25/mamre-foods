package com.mamre.billing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.ui.home.ComingSoonScreen
import com.mamre.billing.ui.home.HomeScreen
import com.mamre.billing.ui.home.HomeTile
import com.mamre.billing.ui.home.SyncStatusScreen
import com.mamre.billing.ui.login.LoginScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(session: SessionManager) : ViewModel() {
    val signedIn = session.signedIn
}

private const val LOGIN = "login"
private const val HOME = "home"
private const val SYNC = "sync"
private const val SOON = "soon/{tile}"

/**
 * Login when there is no session, Home otherwise. When the session ends (logout, or a
 * refresh that fails after a 401) the stack is cleared back to Login (task 4d).
 */
@Composable
fun MamreNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val signedIn by appViewModel.signedIn.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val start = remember { if (appViewModel.signedIn.value) HOME else LOGIN }

    LaunchedEffect(signedIn) {
        val route = navController.currentDestination?.route ?: return@LaunchedEffect
        if (signedIn && route == LOGIN) {
            navController.navigate(HOME) { popUpTo(LOGIN) { inclusive = true } }
        } else if (!signedIn && route != LOGIN) {
            navController.navigate(LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController, startDestination = start) {
        composable(LOGIN) { LoginScreen() }
        composable(HOME) {
            HomeScreen(onTile = { tile ->
                if (tile == HomeTile.SYNC_STATUS) navController.navigate(SYNC)
                else navController.navigate("soon/${tile.name}")
            })
        }
        composable(SYNC) { SyncStatusScreen(onBack = { navController.popBackStack() }) }
        composable(SOON, arguments = listOf(navArgument("tile") { type = NavType.StringType })) {
            val tile = it.arguments?.getString("tile")?.let(HomeTile::valueOf)
            ComingSoonScreen(
                title = tile?.title.orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
