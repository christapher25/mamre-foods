package com.mamre.billing.ui.worker

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.mamre.billing.domain.auth.Routes
import com.mamre.billing.ui.graphViewModel

/** The worker experience (Doc 2 s10): Home and the screens W2 to W10. */
fun NavGraphBuilder.workerGraph(navController: NavController) {
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
}

@androidx.compose.runtime.Composable
private fun NavController.invoiceFlow(entry: androidx.navigation.NavBackStackEntry): InvoiceFlowViewModel =
    graphViewModel(entry, WorkerRoutes.INVOICE_GRAPH)
