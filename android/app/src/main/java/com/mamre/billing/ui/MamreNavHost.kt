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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.domain.auth.routeAllowed
import com.mamre.billing.domain.auth.startDestinationFor
import com.mamre.billing.ui.admin.AdminHomeScreen
import com.mamre.billing.ui.login.LoginScreen
import com.mamre.billing.ui.worker.BuilderScreen
import com.mamre.billing.ui.worker.ConfirmScreen
import com.mamre.billing.ui.worker.CustomerScreen
import com.mamre.billing.ui.worker.InvoiceFlowViewModel
import com.mamre.billing.ui.worker.PaymentCustomerScreen
import com.mamre.billing.ui.worker.PaymentFormScreen
import com.mamre.billing.ui.worker.PaymentScreen
import com.mamre.billing.ui.worker.ReceiptScreen
import com.mamre.billing.ui.worker.ReturnCustomerScreen
import com.mamre.billing.ui.worker.ReturnDoneScreen
import com.mamre.billing.ui.worker.ReturnFormScreen
import com.mamre.billing.ui.worker.HomeScreen
import com.mamre.billing.ui.worker.HomeTile
import com.mamre.billing.ui.worker.SyncScreen
import com.mamre.billing.ui.worker.TodayScreen
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
                    navController.navigate(
                        when (tile) {
                            HomeTile.NEW_INVOICE -> WorkerRoutes.INVOICE_GRAPH
                            HomeTile.RECORD_PAYMENT -> WorkerRoutes.PAYMENT_GRAPH
                            HomeTile.RETURN -> WorkerRoutes.RETURN_GRAPH
                            HomeTile.TODAYS_INVOICES -> WorkerRoutes.TODAY
                            HomeTile.SYNC_STATUS -> WorkerRoutes.SYNC
                        },
                    )
                })
            }
            composable(WorkerRoutes.SYNC) { SyncScreen(onBack = { navController.popBackStack() }) }
            composable(WorkerRoutes.TODAY) {
                TodayScreen(
                    onBack = { navController.popBackStack() },
                    // Reprint: the bill opens marked DUPLICATE COPY (Doc 1 s5.4).
                    onOpen = {
                        navController.navigate(WorkerRoutes.receipt(WorkerRoutes.KIND_INVOICE, it.id, duplicate = true))
                    },
                )
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
            navigation(route = WorkerRoutes.PAYMENT_GRAPH, startDestination = WorkerRoutes.PAYMENT_CUSTOMER) {
                composable(WorkerRoutes.PAYMENT_CUSTOMER) { entry ->
                    PaymentCustomerScreen(
                        vm = navController.graphViewModel(entry, WorkerRoutes.PAYMENT_GRAPH),
                        onBack = { navController.popBackStack() },
                        onChosen = { navController.navigate(WorkerRoutes.PAYMENT_FORM) },
                    )
                }
                composable(WorkerRoutes.PAYMENT_FORM) { entry ->
                    PaymentFormScreen(
                        vm = navController.graphViewModel(entry, WorkerRoutes.PAYMENT_GRAPH),
                        onBack = { navController.popBackStack() },
                        onRecorded = { id ->
                            navController.navigate(WorkerRoutes.receipt(WorkerRoutes.KIND_PAYMENT, id)) {
                                popUpTo(WorkerRoutes.HOME)
                            }
                        },
                    )
                }
            }
            navigation(route = WorkerRoutes.RETURN_GRAPH, startDestination = WorkerRoutes.RETURN_CUSTOMER) {
                composable(WorkerRoutes.RETURN_CUSTOMER) { entry ->
                    ReturnCustomerScreen(
                        vm = navController.graphViewModel(entry, WorkerRoutes.RETURN_GRAPH),
                        onBack = { navController.popBackStack() },
                        onChosen = { navController.navigate(WorkerRoutes.RETURN_FORM) },
                    )
                }
                composable(WorkerRoutes.RETURN_FORM) { entry ->
                    ReturnFormScreen(
                        vm = navController.graphViewModel(entry, WorkerRoutes.RETURN_GRAPH),
                        onBack = { navController.popBackStack() },
                        // Stay inside the return graph: popping it would drop the ViewModel that holds the saved return.
                        onRecorded = {
                            navController.navigate(WorkerRoutes.RETURN_DONE) {
                                popUpTo(WorkerRoutes.RETURN_CUSTOMER) { inclusive = true }
                            }
                        },
                    )
                }
                composable(WorkerRoutes.RETURN_DONE) { entry ->
                    ReturnDoneScreen(
                        vm = navController.graphViewModel(entry, WorkerRoutes.RETURN_GRAPH),
                        onDone = { navController.popBackStack(WorkerRoutes.HOME, inclusive = false) },
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

/** A flow's ViewModel lives as long as its nested graph, so Back between steps keeps the input. */
@Composable
private inline fun <reified VM : ViewModel> NavController.graphViewModel(entry: NavBackStackEntry, graph: String): VM {
    val parent = remember(entry) { getBackStackEntry(graph) }
    return hiltViewModel(parent)
}

@Composable
private fun NavController.invoiceFlow(entry: NavBackStackEntry): InvoiceFlowViewModel =
    graphViewModel(entry, WorkerRoutes.INVOICE_GRAPH)

/** Clears the back stack and opens the start of this role, or Login when there is none. */
private fun NavController.goToStart(role: Role?) {
    val target = role?.let(::startDestinationFor) ?: Routes.LOGIN
    navigate(target) { popUpTo(0) { inclusive = true } }
}
