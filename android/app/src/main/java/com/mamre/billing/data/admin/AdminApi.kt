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
import com.mamre.billing.domain.admin.Ingredient
import com.mamre.billing.domain.admin.InvoiceDetail
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.PriceMatrix
import com.mamre.billing.domain.admin.ReturnsReport
import com.mamre.billing.domain.admin.WorkerAccount
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.StateFlow

/** A rule the server refused (a missing reason, a price that is not above zero, a date that is not later...). */
class AdminRuleException(message: String) : Exception(message)

/** The months the data covers: [first] to [last], the month in progress. */
data class DataSpan(val first: YearMonth, val last: YearMonth)

/**
 * What the Admin screens ask of the server (Doc 2 s9). There is no call that edits or deletes an
 * invoice, a payment, a return or an expense, because the server has none (Doc 3 N3, Doc 2 I-9):
 * an invoice is only voided with a reason, and expenses are only added. Every edit also appends
 * to the change log, as the server AuditLog does.
 *
 * The real endpoints do not exist yet (QUESTIONS); [FakeAdminApi] stands in with DEMO DATA.
 */
interface AdminApi {
    val today: LocalDate

    /** Counts up after every change so open screens reload. */
    val revision: StateFlow<Long>
    val changeLog: StateFlow<List<ChangeLogEntry>>

    suspend fun span(): DataSpan
    suspend fun dashboard(month: YearMonth): DashboardReport

    suspend fun invoices(): List<AdminInvoice>
    suspend fun invoiceDetail(id: String): InvoiceDetail?
    suspend fun voidInvoice(id: String, reason: String, by: String): InvoiceDetail

    suspend fun customerTypes(): List<AdminCustomerType>
    suspend fun customers(): List<AdminCustomer>
    suspend fun customer(id: String): AdminCustomer?
    suspend fun customerBalance(id: String): Long
    suspend fun customerSummary(id: String, month: YearMonth): CustomerMonthSummary
    suspend fun addCustomer(form: CustomerForm, by: String): AdminCustomer
    suspend fun updateCustomer(id: String, form: CustomerForm, by: String): AdminCustomer

    suspend fun products(): List<AdminProduct>
    suspend fun priceMatrix(): PriceMatrix
    suspend fun setDefaultPrice(productId: String, typeId: String, priceCents: Long, from: LocalDate, by: String)

    /** Every override row of a customer, with history. */
    suspend fun overrides(customerId: String): List<OverridePrice>
    suspend fun setOverride(customerId: String, productId: String, priceCents: Long, from: LocalDate, note: String, by: String)
    suspend fun clearOverride(customerId: String, productId: String, by: String)

    suspend fun costing(month: YearMonth): CostingReport
    suspend fun ingredients(): List<Ingredient>
    suspend fun setIngredientPrice(ingredientId: String, priceCents: Long, from: LocalDate, by: String)

    suspend fun expenseCategories(): List<ExpenseCategory>
    suspend fun expenses(month: YearMonth): ExpensesReport
    suspend fun addExpense(categoryId: String, date: LocalDate, amountCents: Long, description: String, by: String): Expense

    suspend fun returnsReport(month: YearMonth): ReturnsReport
    suspend fun balances(): List<BalanceRow>

    suspend fun settings(): BusinessSettings
    suspend fun saveSettings(settings: BusinessSettings, by: String)
    suspend fun workers(): List<WorkerAccount>
}
