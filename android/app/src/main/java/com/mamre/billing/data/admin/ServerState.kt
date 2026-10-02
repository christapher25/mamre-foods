package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.Expense
import com.mamre.billing.domain.admin.ExpenseCategory
import com.mamre.billing.domain.admin.Ingredient
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.PriceEntry
import com.mamre.billing.domain.admin.WorkerAccount
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnResolution
import java.time.LocalDate

// DEMO DATA. This is the stand-in for the server database (Doc 2 s1.1: the server is the source of
// truth for prices, costing and reports). It is the Admin's own data set and is not linked to the
// worker's DemoStore (owner decision). Nothing here is real: prices, quantities and names are invented.

/** A customer return as the server stores it (Doc 2 s4.2 ReturnRecord). */
data class ReturnRow(
    val id: String,
    val date: LocalDate,
    val customerId: String,
    val customerName: String,
    val typeName: String,
    val invoiceId: String?,
    val productId: String,
    val productName: String,
    val qtyPackets: Int,
    val reason: ReturnReason,
    val resolution: ReturnResolution,
    val unitPriceCents: Long,
    val creditCents: Long,
)

/** One production batch (Doc 2 s4.2). Its ingredient cost is recomputed from recipe x price, never typed. */
data class BatchRow(
    val id: String,
    val date: LocalDate,
    val productId: String,
    val wheatKg: Int,
    val packetsPacked: Int,
    val packetsDamaged: Int,
) {
    val goodPackets: Int get() = packetsPacked - packetsDamaged
}

/** Quantity of one ingredient per 1 kg of wheat, in thousandths of the base unit; null while pending (Doc 1 P-2). */
data class RecipeLine(val ingredientId: String, val milliPerKgWheat: Long?)

data class ServerState(
    val types: List<AdminCustomerType>,
    val products: List<AdminProduct>,
    val customers: List<AdminCustomer>,
    val invoices: List<AdminInvoice>,
    val payments: List<AdminPayment>,
    /** Invoice a payment was taken with, when it was (Doc 2 s4.2 Payment.invoice_id). */
    val paymentInvoiceIds: Map<String, String>,
    val returns: List<ReturnRow>,
    val batches: List<BatchRow>,
    val categories: List<ExpenseCategory>,
    val expenses: List<Expense>,
    val priceEntries: List<PriceEntry>,
    val overrides: List<OverridePrice>,
    val ingredients: List<Ingredient>,
    val recipes: Map<String, List<RecipeLine>>,
    val settings: BusinessSettings,
    val workers: List<WorkerAccount>,
    val today: LocalDate,
)
