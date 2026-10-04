package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.BalanceRow
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.ChangeLogEntry
import com.mamre.billing.domain.admin.CostingReport
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.admin.CustomerMonthSummary
import com.mamre.billing.domain.admin.DashboardReport
import com.mamre.billing.domain.admin.Expense
import com.mamre.billing.domain.admin.ExpenseCategory
import com.mamre.billing.domain.admin.ExpensesReport
import com.mamre.billing.domain.admin.InvoiceDetail
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.PriceMatrix
import com.mamre.billing.domain.admin.ProductCost
import com.mamre.billing.domain.admin.ProductRecipe
import com.mamre.billing.domain.admin.ProductionDamage
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.admin.ReturnsReport
import com.mamre.billing.domain.admin.StockReport
import com.mamre.billing.domain.admin.WorkerAccount
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** What the Admin is told while the server has no admin endpoints (QUESTIONS; Doc 2 s5, s9). */
const val ADMIN_NOT_AVAILABLE_MESSAGE = "Admin sign-in is not available on the server yet"

/**
 * The AdminApi a RELEASE build binds. The server has no admin API yet, so every call answers "{ADMIN_NOT_AVAILABLE_MESSAGE}"
 * with an [AdminRuleException]. The release build cannot sign an admin in anyway (SessionManager refuses the role),
 * so no screen reaches these calls; this class only guarantees that nothing invented is ever shown. The demo
 * server stand-in lives in the debug source set and is not part of a release build.
 */
class UnavailableAdminApi : AdminApi {
    override val today: LocalDate = LocalDate.now()
    override val revision: StateFlow<Long> = MutableStateFlow(0L)
    override val changeLog: StateFlow<List<ChangeLogEntry>> = MutableStateFlow(emptyList())
    override suspend fun span(): DataSpan = unavailable()
    override suspend fun dashboard(month: YearMonth): DashboardReport = unavailable()
    override suspend fun invoices(): List<AdminInvoice> = unavailable()
    override suspend fun invoiceDetail(id: String): InvoiceDetail? = unavailable()
    override suspend fun voidInvoice(id: String, reason: String, by: String): InvoiceDetail = unavailable()
    override suspend fun customerTypes(): List<AdminCustomerType> = unavailable()
    override suspend fun customers(): List<AdminCustomer> = unavailable()
    override suspend fun customer(id: String): AdminCustomer? = unavailable()
    override suspend fun customerBalance(id: String): Long = unavailable()
    override suspend fun customerSummary(id: String, month: YearMonth): CustomerMonthSummary = unavailable()
    override suspend fun addCustomer(form: CustomerForm, by: String): AdminCustomer = unavailable()
    override suspend fun updateCustomer(id: String, form: CustomerForm, by: String): AdminCustomer = unavailable()
    override suspend fun products(): List<AdminProduct> = unavailable()
    override suspend fun priceMatrix(): PriceMatrix = unavailable()
    override suspend fun setDefaultPrice(productId: String, typeId: String, priceCents: Long, from: LocalDate, by: String) = unavailable()
    override suspend fun setWorkerCanEditPrice(typeId: String, allowed: Boolean, by: String) = unavailable()
    override suspend fun overrides(customerId: String): List<OverridePrice> = unavailable()
    override suspend fun setOverride(customerId: String, productId: String, priceCents: Long, from: LocalDate, note: String, by: String) = unavailable()
    override suspend fun clearOverride(customerId: String, productId: String, by: String) = unavailable()
    override suspend fun costing(month: YearMonth): CostingReport = unavailable()
    override suspend fun materials(): List<Material> = unavailable()
    override suspend fun recipes(): List<ProductRecipe> = unavailable()
    override suspend fun setRecipeQuantity(productId: String, materialId: String, qtyMb: Long, by: String) = unavailable()
    override suspend fun wastageBp(): Int = unavailable()
    override suspend fun setWastageBp(basisPoints: Int, by: String) = unavailable()
    override suspend fun stock(month: YearMonth): StockReport = unavailable()
    override suspend fun purchases(month: YearMonth): List<Purchase> = unavailable()
    override suspend fun addPurchase(materialId: String, date: LocalDate, qtyMb: Long, totalCents: Long, note: String, by: String): Purchase = unavailable()
    override suspend fun reversePurchase(purchaseId: String, reason: String, by: String): Purchase = unavailable()
    override suspend fun addProductionDamage(productId: String, date: LocalDate, chapathis: Int, note: String, by: String): ProductionDamage = unavailable()
    override suspend fun setStandardPacketSize(productId: String, chapathis: Int, by: String) = unavailable()
    override suspend fun setYieldPerKg(productId: String, chapathisPerKg: Int, by: String) = unavailable()
    override suspend fun packetCost(month: YearMonth, productId: String, chapathis: Int): ProductCost = unavailable()
    override suspend fun expenseCategories(): List<ExpenseCategory> = unavailable()
    override suspend fun expenses(month: YearMonth): ExpensesReport = unavailable()
    override suspend fun addExpense(categoryId: String, date: LocalDate, amountCents: Long, description: String, by: String): Expense = unavailable()
    override suspend fun reverseExpense(expenseId: String, reason: String, by: String): Expense = unavailable()
    override suspend fun returnsReport(month: YearMonth): ReturnsReport = unavailable()
    override suspend fun balances(): List<BalanceRow> = unavailable()
    override suspend fun settings(): BusinessSettings = unavailable()
    override suspend fun saveSettings(settings: BusinessSettings, by: String) = unavailable()
    override suspend fun workers(): List<WorkerAccount> = unavailable()
    override suspend fun addSalesman(fullName: String, username: String, by: String): WorkerAccount = unavailable()

    private fun unavailable(): Nothing = throw AdminRuleException(ADMIN_NOT_AVAILABLE_MESSAGE)
}
