package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.Expense
import com.mamre.billing.domain.admin.ExpenseCategory
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.admin.WorkerAccount
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnResolution
import java.time.LocalDate

// DEMO DATA. This is the stand-in for the server database (Doc 2 s1.1: the server is the source of
// truth for prices, costing and reports). It is the Admin's own data set and is not linked to the
// worker's DemoStore (owner decision); the only link is the shared selling price table. Nothing here
// is real: prices, quantities and names are invented. Selling prices are not stored here: they live
// in the shared table.

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
    /** Chapathis in each returned packet; a replacement is made of this many chapathis per packet. */
    val chapathisPerPacket: Int = 6,
    val reason: ReturnReason,
    val resolution: ReturnResolution,
    val unitPriceCents: Long,
    val creditCents: Long,
)

/** Chapathis damaged in production, entered by the Admin (add only). */
data class DamageRow(
    val id: String,
    val date: LocalDate,
    val productId: String,
    val chapathis: Int,
    val note: String,
    val enteredBy: String,
)

/**
 * One material of a product's recipe: thousandths of the base unit per 1 kg of wheat, or null while unset
 * (Doc 1 P-2). The packing line is the exception: one piece per packet, whatever the packet size.
 */
data class RecipeEntry(val materialId: String, val qtyMb: Long?)

/** Stock held before the first month of data, with the money it was worth. */
data class OpeningStock(val qtyMb: Long, val valueCents: Long)

data class ServerState(
    val types: List<AdminCustomerType>,
    val products: List<AdminProduct>,
    val customers: List<AdminCustomer>,
    val invoices: List<AdminInvoice>,
    val payments: List<AdminPayment>,
    /** Invoice a payment was taken with, when it was (Doc 2 s4.2 Payment.invoice_id). */
    val paymentInvoiceIds: Map<String, String>,
    val returns: List<ReturnRow>,
    val categories: List<ExpenseCategory>,
    val expenses: List<Expense>,
    /** Admin-only notes on override prices, by override id. The prices themselves live in the shared catalog. */
    val overrideNotes: Map<String, String>,
    /** The shared materials list: wheat, oil, sugar, salt, baking powder, potassium sorbate, packing. */
    val materials: List<Material>,
    /** Quantity of each material per packet, per product. One recipe applies to every month. */
    val recipes: Map<String, List<RecipeEntry>>,
    val purchases: List<Purchase>,
    val damage: List<DamageRow>,
    val openingStock: Map<String, OpeningStock>,
    /** Wastage in basis points (200 is 2%), 0 to 500. */
    val wastageBp: Int,
    val settings: BusinessSettings,
    val workers: List<WorkerAccount>,
    val today: LocalDate,
)
