package com.mamre.billing.ui.admin

import com.mamre.billing.domain.auth.Routes

/** Destinations of the admin graph. Every one lives under "admin/" so the route guard can see it. */
object AdminRoutes {
    const val DASHBOARD = Routes.ADMIN_HOME

    const val SALES = "admin/sales"
    const val SALES_DETAIL = "admin/sales/{id}"

    const val CUSTOMERS = "admin/customers"
    const val CUSTOMER_NEW = "admin/customers/new"
    const val CUSTOMER_DETAIL = "admin/customers/{id}"
    const val CUSTOMER_EDIT = "admin/customers/{id}/edit"
    const val OVERRIDE_SET = "admin/customers/{id}/override/{productId}"

    const val PRICES = "admin/prices"
    const val PRICE_SET = "admin/prices/set/{productId}/{typeId}"

    const val MORE = "admin/more"
    const val COSTING = "admin/more/costing"
    const val INGREDIENT_PRICE = "admin/more/costing/ingredient/{id}"
    const val EXPENSES = "admin/more/expenses"
    const val EXPENSE_ADD = "admin/more/expenses/add"
    const val PURCHASE_ADD = "admin/more/expenses/purchase"
    const val RETURNS = "admin/more/returns"
    const val BALANCES = "admin/more/balances"
    const val SETTINGS = "admin/more/settings"

    const val ARG_ID = "id"
    const val ARG_PRODUCT = "productId"
    const val ARG_TYPE = "typeId"

    fun salesDetail(id: String) = "admin/sales/$id"
    fun customerDetail(id: String) = "admin/customers/$id"
    fun customerEdit(id: String) = "admin/customers/$id/edit"
    fun overrideSet(customerId: String, productId: String) = "admin/customers/$customerId/override/$productId"
    fun priceSet(productId: String, typeId: String) = "admin/prices/set/$productId/$typeId"
    fun ingredientPrice(id: String) = "admin/more/costing/ingredient/$id"

    val all = listOf(
        DASHBOARD, SALES, SALES_DETAIL, CUSTOMERS, CUSTOMER_NEW, CUSTOMER_DETAIL, CUSTOMER_EDIT, OVERRIDE_SET,
        PRICES, PRICE_SET, MORE, COSTING, INGREDIENT_PRICE, EXPENSES, EXPENSE_ADD, PURCHASE_ADD, RETURNS, BALANCES, SETTINGS,
    )
}

/** The five bottom tabs; More opens Costing, Expenses, Returns and damage, Balances and Settings. */
enum class AdminTab(val label: String, val route: String) {
    DASHBOARD("Dashboard", AdminRoutes.DASHBOARD),
    SALES("Sales", AdminRoutes.SALES),
    CUSTOMERS("Customers", AdminRoutes.CUSTOMERS),
    PRICES("Prices", AdminRoutes.PRICES),
    MORE("More", AdminRoutes.MORE),
}

/** Which tab a route belongs to, so the bar highlights it on detail screens too. */
fun tabOf(route: String?): AdminTab? = when {
    route == null -> null
    route == AdminRoutes.DASHBOARD -> AdminTab.DASHBOARD
    route.startsWith(AdminRoutes.SALES) -> AdminTab.SALES
    route.startsWith(AdminRoutes.CUSTOMERS) -> AdminTab.CUSTOMERS
    route.startsWith(AdminRoutes.PRICES) -> AdminTab.PRICES
    route.startsWith(AdminRoutes.MORE) -> AdminTab.MORE
    else -> null
}

private val ROUTES_WITH_BAR = setOf(
    AdminRoutes.DASHBOARD, AdminRoutes.SALES, AdminRoutes.CUSTOMERS, AdminRoutes.PRICES, AdminRoutes.MORE,
    AdminRoutes.COSTING, AdminRoutes.EXPENSES, AdminRoutes.RETURNS, AdminRoutes.BALANCES, AdminRoutes.SETTINGS,
)

/** The bar stays on the tab roots and the More screens; details and forms use the whole screen. */
fun showsBottomBar(route: String?): Boolean = route in ROUTES_WITH_BAR
