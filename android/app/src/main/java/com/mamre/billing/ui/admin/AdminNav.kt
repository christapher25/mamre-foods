package com.mamre.billing.ui.admin

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.navigation
import com.mamre.billing.domain.auth.Routes

/** The admin experience: Dashboard, Sales, Customers, Prices and More (owner brief, Stage B). */
fun NavGraphBuilder.adminGraph(navController: NavController, onOpenSales: () -> Unit) {
    navigation(route = Routes.ADMIN_GRAPH, startDestination = AdminRoutes.DASHBOARD) {
        composable(AdminRoutes.DASHBOARD) { DashboardScreen() }
        composable(AdminRoutes.SALES) { SalesListScreen(onOpen = { navController.navigate(AdminRoutes.salesDetail(it)) }) }
        composable(AdminRoutes.SALES_DETAIL, arguments = idArgs()) { SalesDetailScreen(onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.CUSTOMERS) {
            CustomerListScreen(
                onOpen = { navController.navigate(AdminRoutes.customerDetail(it)) },
                onAdd = { navController.navigate(AdminRoutes.CUSTOMER_NEW) },
            )
        }
        composable(AdminRoutes.CUSTOMER_NEW) {
            CustomerFormScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(AdminRoutes.CUSTOMER_DETAIL, arguments = idArgs()) {
            CustomerDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(AdminRoutes.customerEdit(it)) },
                onOverride = { c, p -> navController.navigate(AdminRoutes.overrideSet(c, p)) },
            )
        }
        composable(AdminRoutes.CUSTOMER_EDIT, arguments = idArgs()) {
            CustomerFormScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(AdminRoutes.OVERRIDE_SET, arguments = idArgs(AdminRoutes.ARG_ID, AdminRoutes.ARG_PRODUCT)) {
            OverrideScreen(onBack = { navController.popBackStack() })
        }
        composable(AdminRoutes.PRICES) { PricesScreen(onSetPrice = { p, t -> navController.navigate(AdminRoutes.priceSet(p, t)) }) }
        composable(AdminRoutes.PRICE_SET, arguments = idArgs(AdminRoutes.ARG_PRODUCT, AdminRoutes.ARG_TYPE)) {
            PriceSetScreen(onBack = { navController.popBackStack() })
        }
        composable(AdminRoutes.MORE) { MoreScreen(onOpen = { navController.navigate(it) }, onSalesArea = onOpenSales) }
        composable(AdminRoutes.COSTING) { CostingScreen(onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.EXPENSES) {
            ExpensesScreen(
                onAddPurchase = { navController.navigate(AdminRoutes.PURCHASE_ADD) },
                onAddExpense = { navController.navigate(AdminRoutes.EXPENSE_ADD) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(AdminRoutes.PURCHASE_ADD) { AddPurchaseScreen(onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.EXPENSE_ADD) { AddExpenseScreen(onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.RETURNS) {
            ReturnsScreen(onBack = { navController.popBackStack() }, onAddDamage = { navController.navigate(AdminRoutes.DAMAGE_ADD) })
        }
        composable(AdminRoutes.DAMAGE_ADD) { AddDamageScreen(onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.BALANCES) {
            BalancesScreen(
                onBack = { navController.popBackStack() },
                onOpenCustomer = { navController.navigate(AdminRoutes.customerDetail(it)) },
            )
        }
        composable(AdminRoutes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}

private fun idArgs(vararg names: String): List<NamedNavArgument> =
    (names.toList().ifEmpty { listOf(AdminRoutes.ARG_ID) }).map { navArgument(it) { type = NavType.StringType } }
