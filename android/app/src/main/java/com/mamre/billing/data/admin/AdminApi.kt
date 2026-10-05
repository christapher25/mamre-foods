package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.BalanceRow
import com.mamre.billing.domain.admin.BusinessSettings
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
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.StateFlow

/** A rule a use case refused (a missing reason, a price that is not above zero, a date that is not later...). */
typealias AdminRuleException = com.mamre.billing.domain.usecase.RuleException

/** The months the data covers: [first] to [last], the month in progress. */
data class DataSpan(val first: YearMonth, val last: YearMonth)

/**
 * What the Admin screens ask of the books (Doc 2 s9). There is no call that edits or deletes a bill, a payment, a
 * return, a purchase or an expense (Doc 3 N3, Doc 2 I-9): a bill is only voided with a reason, and purchases and
 * expenses are only added or reversed by a linked entry. Every change also appends to the change log.
 * [LocalAdminApi] implements it on the phone's database through the use cases.
 */
interface AdminApi {
    val today: LocalDate

    /** Counts up after every change so open screens reload. */
    val revision: StateFlow<Long>

    suspend fun span(): DataSpan
    suspend fun dashboard(month: YearMonth): DashboardReport

    suspend fun invoices(): List<AdminInvoice>
    suspend fun invoiceDetail(id: String): InvoiceDetail?
    suspend fun voidInvoice(id: String, reason: String): InvoiceDetail

    suspend fun customerTypes(): List<AdminCustomerType>
    suspend fun customers(): List<AdminCustomer>
    suspend fun customer(id: String): AdminCustomer?
    suspend fun customerBalance(id: String): Long
    suspend fun customerSummary(id: String, month: YearMonth): CustomerMonthSummary
    suspend fun addCustomer(form: CustomerForm): AdminCustomer
    suspend fun updateCustomer(id: String, form: CustomerForm): AdminCustomer

    suspend fun products(): List<AdminProduct>
    suspend fun priceMatrix(): PriceMatrix
    suspend fun setDefaultPrice(productId: String, typeId: String, priceCents: Long, from: LocalDate)

    /** The "Salesman can edit price" flag of a customer type (change set C3). Change-logged; salesmen get it at their next sync. */
    suspend fun setWorkerCanEditPrice(typeId: String, allowed: Boolean)

    /** Every override row of a customer, with history. */
    suspend fun overrides(customerId: String): List<OverridePrice>
    suspend fun setOverride(customerId: String, productId: String, priceCents: Long, from: LocalDate, note: String)
    suspend fun clearOverride(customerId: String, productId: String)

    suspend fun costing(month: YearMonth): CostingReport

    /** The shared materials list (owner costing spec). Prices come from purchases, not from a price list. */
    suspend fun materials(): List<Material>
    suspend fun recipes(): List<ProductRecipe>
    suspend fun setRecipeQuantity(productId: String, materialId: String, qtyMb: Long)
    suspend fun wastageBp(): Int
    suspend fun setWastageBp(basisPoints: Int)

    /** Opening stock, bought, used, closing stock, average price and cost consumed per material (B6). */
    suspend fun stock(month: YearMonth): StockReport
    suspend fun purchases(month: YearMonth): List<Purchase>
    suspend fun addPurchase(materialId: String, date: LocalDate, qtyMb: Long, totalCents: Long, note: String): Purchase

    /**
     * Corrects a mistake without editing it: adds a linked entry with negative quantity and total, dated
     * today, with a required reason. The original stays; the monthly table nets the two out.
     */
    suspend fun reversePurchase(purchaseId: String, reason: String): Purchase

    /** Production damage is add only and counts as material usage (B7). */
    suspend fun addProductionDamage(productId: String, date: LocalDate, chapathis: Int, note: String): ProductionDamage

    /** Standard packet size (chapathis) of a product. Synced to workers; custom packet prices follow it. */
    suspend fun setStandardPacketSize(productId: String, chapathis: Int)

    /** Chapathis made from 1 kg of wheat. Usage and cost per chapathi follow it. */
    suspend fun setYieldPerKg(productId: String, chapathisPerKg: Int)

    /** Cost of a packet of [chapathis] (a custom packet: per chapathi x N + packing), calculated by the server. */
    suspend fun packetCost(month: YearMonth, productId: String, chapathis: Int): ProductCost

    suspend fun expenseCategories(): List<ExpenseCategory>
    suspend fun expenses(month: YearMonth): ExpensesReport
    suspend fun addExpense(categoryId: String, date: LocalDate, amountCents: Long, description: String): Expense

    /** Same rule as [reversePurchase]: a negative linked entry with a required reason; the original stays. */
    suspend fun reverseExpense(expenseId: String, reason: String): Expense

    suspend fun returnsReport(month: YearMonth): ReturnsReport
    suspend fun balances(): List<BalanceRow>

    suspend fun settings(): BusinessSettings
    suspend fun saveSettings(settings: BusinessSettings)
}
