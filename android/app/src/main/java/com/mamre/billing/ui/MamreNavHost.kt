package com.mamre.billing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.domain.auth.routeAllowed
import com.mamre.billing.domain.auth.startDestinationFor
import com.mamre.billing.ui.admin.AdminHomeScreen
import com.mamre.billing.ui.login.LoginScreen
import com.mamre.billing.ui.worker.BuilderScreen
import com.mamre.billing.ui.worker.ComingSoonScreen
import com.mamre.billing.ui.worker.ConfirmScreen
import com.mamre.billing.ui.worker.CustomerScreen
import com.mamre.billing.ui.worker.InvoiceFlowViewModel
import com.mamre.billing.ui.worker.PaymentScreen
import com.mamre.billing.ui.worker.ReceiptScreen
import com.mamre.billing.ui.worker.HomeScreen
import com.mamre.billing.ui.worker.HomeTile
import com.mamre.billing.ui.worker.SyncScreen
import com.mamre.billing.ui.worker.WorkerRoutes
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
 * 401) the whole back stack is cleared to Login.
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

    LaunchedEffect(role) {
        navController.currentBackStackEntryFlow.collect { entry ->
            if (!routeAllowed(role, entry.destination.route)) navController.goToStart(role)
        }
    }

    NavHost(navController, startDestination = start) {
        composable(Routes.LOGIN) { LoginScreen() }
        navigation(route = Routes.WORKER_GRAPH, startDestination = WorkerRoutes.HOME) {
            composable(WorkerRoutes.HOME) {
                HomeScreen(onTile = { tile ->
                    when (tile) {
                        HomeTile.NEW_INVOICE -> navController.navigate(WorkerRoutes.INVOICE_GRAPH)
                        HomeTile.SYNC_STATUS -> navController.navigate(WorkerRoutes.SYNC)
                        else -> navController.navigate(WorkerRoutes.soon(tile))
                    }
                })
            }
            composable(WorkerRoutes.SYNC) { SyncScreen(onBack = { navController.popBackStack() }) }
            composable(
                WorkerRoutes.SOON,
                arguments = listOf(navArgument("tile") { type = NavType.StringType }),
            ) {
                val tile = it.arguments?.getString("tile")?.let(HomeTile::valueOf)
                ComingSoonScreen(title = tile?.title.orEmpty(), onBack = { navController.popBackStack() })
            }
            navigation(route = WorkerRoutes.INVOICE_GRAPH, startDestination = WorkerRoutes.INVOICE_CUSTOMER) {
                composable(WorkerRoutes.INVOICE_CUSTOMER) { entry ->
                    CustomerScreen(
                        vm = navController.invoiceFlow(entry),
                        onBack = { navController.popBackStack() },
                        onChosen = { navController.navigate(WorkerRoutes.INVOICE_BUILD) },
                    )
                }
                composable(WorkerRoutes.INVOICE_BUILD) { entry ->
                    val vm = navController.invoiceFlow(entry)
                    BuilderScreen(
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        onContinue = {
                            vm.preparePayment()
                            navController.navigate(WorkerRoutes.INVOICE_PAYMENT)
                        },
                    )
                }
                composable(WorkerRoutes.INVOICE_PAYMENT) { entry ->
                    PaymentScreen(
                        vm = navController.invoiceFlow(entry),
                        onBack = { navController.popBackStack() },
                        onContinue = { navController.navigate(WorkerRoutes.INVOICE_CONFIRM) },
                    )
                }
                composable(WorkerRoutes.INVOICE_CONFIRM) { entry ->
                    ConfirmScreen(
                        vm = navController.invoiceFlow(entry),
                        onBack = { navController.popBackStack() },
                        onConfirmed = { id ->
                            // The invoice is saved; the bill replaces the whole flow, so Back goes Home.
                            navController.navigate(WorkerRoutes.receipt(WorkerRoutes.KIND_INVOICE, id)) {
                                popUpTo(WorkerRoutes.HOME)
                            }
                        },
                    )
                }
            }
            composable(
                WorkerRoutes.RECEIPT,
                arguments = listOf(
                    navArgument(WorkerRoutes.ARG_KIND) { type = NavType.StringType },
                    navArgument(WorkerRoutes.ARG_ID) { type = NavType.StringType },
                    navArgument(WorkerRoutes.ARG_DUPLICATE) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) {
                ReceiptScreen(
                    onDone = { navController.popBackStack(WorkerRoutes.HOME, inclusive = false) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        navigation(route = Routes.ADMIN_GRAPH, startDestination = Routes.ADMIN_HOME) {
            composable(Routes.ADMIN_HOME) { AdminHomeScreen() }
        }
    }
}

/** The invoice ViewModel lives as long as the invoice graph, so Back between steps keeps the input. */
@Composable
private fun NavController.invoiceFlow(entry: NavBackStackEntry): InvoiceFlowViewModel {
    val parent = remember(entry) { getBackStackEntry(WorkerRoutes.INVOICE_GRAPH) }
    return hiltViewModel(parent)
}

/** Clears the back stack and opens the start of this role, or Login when there is none. */
private fun NavController.goToStart(role: Role?) {
    val target = role?.let(::startDestinationFor) ?: Routes.LOGIN
    navigate(target) { popUpTo(0) { inclusive = true } }
}
