package com.mamre.billing.ui.admin

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.mamre.billing.domain.auth.Routes

/** The admin experience: Dashboard, Sales, Customers, Prices and More (owner brief, Stage B). */
fun NavGraphBuilder.adminGraph(navController: NavController) {
    navigation(route = Routes.ADMIN_GRAPH, startDestination = AdminRoutes.DASHBOARD) {
        composable(AdminRoutes.DASHBOARD) { DashboardScreen() }
        composable(AdminRoutes.SALES) { AdminPlaceholderScreen("Sales") }
        composable(AdminRoutes.CUSTOMERS) { AdminPlaceholderScreen("Customers") }
        composable(AdminRoutes.PRICES) { AdminPlaceholderScreen("Prices") }
        composable(AdminRoutes.MORE) { MoreScreen(onOpen = { navController.navigate(it) }) }
        composable(AdminRoutes.COSTING) { AdminPlaceholderScreen("Costing", onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.EXPENSES) { AdminPlaceholderScreen("Expenses", onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.RETURNS) { AdminPlaceholderScreen("Returns and damage", onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.BALANCES) { AdminPlaceholderScreen("Balances", onBack = { navController.popBackStack() }) }
        composable(AdminRoutes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
