package com.mamre.billing.domain.admin

import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.InvoiceStatus
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnResolution
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

// Admin-side models (Doc 1 s2, s11; Doc 2 s4.2, s9). Money is Long cents (Doc 2 I-1); cost per
// packet is a Long in ten-thousandths of a dollar (6975 is $0.6975). These types carry numbers
// the server computed: no costing or profit formula lives in domain/admin or ui/admin (Doc 2 s1.1).
// Confirmed invoices, payments, returns and expenses are only ever read or appended to (Doc 3 N3).

/** A number the server may not be able to give yet. Missing input is never treated as zero (Doc 3 N5). */
sealed interface Figure {
    data class Known(val value: Long) : Figure

    /** INCOMPLETE: [missing] names the inputs still needed, for example the potassium sorbate quantity (Doc 1 P-2). */
    data class Incomplete(val missing: List<String>) : Figure
}

val Figure.valueOrNull: Long? get() = (this as? Figure.Known)?.value

data class AdminCustomerType(val id: String, val name: String)

data class AdminProduct(
    val id: String,
    val code: String,
    val name: String,
    val unitsPerPacket: Int,
    val packingCostCents: Long,
)

data class AdminCustomer(
    val id: String,
    val name: String,
    val typeId: String,
    val typeName: String,
    val phone: String,
    val address: String,
    val paymentMode: PaymentMode,
    val notes: String,
    val isActive: Boolean,
    val openingBalanceCents: Long,
)

/** What the Admin types in the add and edit customer forms. */
data class CustomerForm(
    val name: String,
    val typeId: String,
    val phone: String,
    val address: String,
    val paymentMode: PaymentMode,
    val notes: String,
    val isActive: Boolean,
    /** Only used when adding: a balance is computed afterwards, never edited (Doc 1 s6.3). */
    val openingBalanceCents: Long = 0,
)

data class InvoiceItem(
    val productId: String,
    val productName: String,
    val qtyPackets: Int,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
)

data class AdminInvoice(
    val id: String,
    val number: String,
    /** Null for a walk-in sale. */
    val customerId: String?,
    val customerName: String,
    val typeName: String,
    val deviceCode: String,
    val issuedAt: LocalDateTime,
    val items: List<InvoiceItem>,
    val totalCents: Long,
    val status: InvoiceStatus,
    val voidReason: String? = null,
    val voidedBy: String? = null,
    val voidedAt: LocalDateTime? = null,
) {
    val isVoid: Boolean get() = status == InvoiceStatus.VOID
    val packets: Int get() = items.sumOf { it.qtyPackets }
}

data class AdminPayment(
    val id: String,
    val receiptNumber: String,
    val customerId: String?,
    val customerName: String,
    val date: LocalDate,
    val amountCents: Long,
    val method: PaymentMethod,
    val note: String,
)

/** Part of a payment the server applied to an invoice, oldest first (Doc 1 s6.2). */
data class AppliedPayment(
    val receiptNumber: String,
    val date: LocalDate,
    val method: PaymentMethod,
    val amountCents: Long,
)

data class InvoiceCredit(
    val date: LocalDate,
    val productName: String,
    val qtyPackets: Int,
    val creditCents: Long,
    val reason: ReturnReason,
)

/** Read-only detail of one invoice: items, payments, returns and credits (B2). */
data class InvoiceDetail(
    val invoice: AdminInvoice,
    val payments: List<AppliedPayment>,
    val credits: List<InvoiceCredit>,
    val amountDueCents: Long,
)

enum class InvoiceStatusFilter(val label: String) { ALL("All"), ACTIVE("Active"), VOID("Void") }

data class InvoiceFilter(
    val query: String = "",
    val month: YearMonth? = null,
    val customerId: String? = null,
    val status: InvoiceStatusFilter = InvoiceStatusFilter.ALL,
)

data class CustomerMonthSummary(
    val customerId: String,
    val month: YearMonth,
    val openingCents: Long,
    val invoicedCents: Long,
    val creditsCents: Long,
    val payments: List<AdminPayment>,
    val closingCents: Long,
)

data class PriceEntry(
    val id: String,
    val productId: String,
    val typeId: String,
    val unitPriceCents: Long,
    val effectiveFrom: LocalDate,
)

data class OverridePrice(
    val id: String,
    val customerId: String,
    val productId: String,
    val unitPriceCents: Long,
    val effectiveFrom: LocalDate,
    val isActive: Boolean,
    val note: String,
)

/** Default prices with their history; the current price is the latest entry not after today. */
data class PriceMatrix(
    val products: List<AdminProduct>,
    val types: List<AdminCustomerType>,
    val entries: List<PriceEntry>,
) {
    fun history(productId: String, typeId: String): List<PriceEntry> =
        entries.filter { it.productId == productId && it.typeId == typeId }.sortedByDescending { it.effectiveFrom }

    fun current(productId: String, typeId: String, today: LocalDate): PriceEntry? =
        history(productId, typeId).firstOrNull { !it.effectiveFrom.isAfter(today) }
}

/** One way to enter a quantity of a material: a label and how many thousandths of the base unit one is. */
data class MaterialUnit(val label: String, val mbPerUnit: Long)

/**
 * One entry of the shared materials list (owner decision): wheat, oil, sugar, salt, baking powder,
 * potassium sorbate and packing. Quantities are Long thousandths of the base unit ("mb"): 375 g of
 * wheat is 375,000 and 1 packing piece is 1,000. Prices come from purchases, not from a price list.
 */
data class Material(
    val id: String,
    val name: String,
    /** g, ml or piece: the unit recipes are written in. */
    val baseUnit: String,
    /** kg, L or piece: the unit stock and prices are shown in. */
    val purchaseUnit: String,
    val isPacking: Boolean,
    val units: List<MaterialUnit>,
) {
    val purchaseMbPerUnit: Long get() = units.first { it.label == purchaseUnit }.mbPerUnit
}

/** One line of a product's recipe: the quantity per packet in thousandths of the base unit, or null while unset. */
data class RecipeLine(
    val materialId: String,
    val materialName: String,
    val baseUnit: String,
    val qtyMb: Long?,
)

data class ProductRecipe(val productId: String, val productName: String, val lines: List<RecipeLine>)

/** A purchase of a material. Add only: never edited or deleted (Doc 3 N3). */
data class Purchase(
    val id: String,
    val date: LocalDate,
    val materialId: String,
    val materialName: String,
    val qtyMb: Long,
    val totalCents: Long,
    val note: String,
    val enteredBy: String,
    /** Set on a reversing entry: the purchase it cancels. Its quantity and total are negative. */
    val reversesId: String? = null,
    /** Required on a reversing entry. */
    val reason: String = "",
) {
    val isReversal: Boolean get() = reversesId != null
}

/**
 * One material for one month (B6 Materials tab). The server calculates every figure; [Figure.Incomplete]
 * says what is missing (no price, an unset recipe quantity). Price is per purchase unit in
 * ten-thousandths of a dollar.
 */
data class StockRow(
    val material: Material,
    val openingQtyMb: Figure,
    val openingValueCents: Figure,
    val boughtQtyMb: Long,
    val boughtCents: Long,
    val usedMb: Figure,
    val closingQtyMb: Figure,
    val closingValueCents: Figure,
    val avgPriceTt: Figure,
    val costConsumedCents: Figure,
) {
    /** More was used than was held: shown with a warning chip. */
    val negativeStock: Boolean get() = ((closingQtyMb as? Figure.Known)?.value ?: 0L) < 0L
}

data class StockReport(
    val month: YearMonth,
    val rows: List<StockRow>,
    val boughtTotalCents: Long,
    val openingValueTotal: Figure,
    /** Month direct expense: cost consumed of every material including packing. */
    val costConsumedTotal: Figure,
    val closingValueTotal: Figure,
)

/** One material's share of the cost of one packet, in ten-thousandths of a dollar. */
data class CostLine(val materialId: String, val materialName: String, val tt: Long)

/** Cost per packet from the server, in ten-thousandths of a dollar (6975 is $0.6975). */
sealed interface ProductCost {
    val productId: String
    val productName: String

    data class Complete(
        override val productId: String,
        override val productName: String,
        /** One line per ingredient material, packing and wastage not included. */
        val lines: List<CostLine>,
        val packingTt: Long,
        /** Wastage on the ingredients and the packing. */
        val wastageTt: Long,
        val directTt: Long,
        val indirectTt: Long,
        val fullTt: Long,
    ) : ProductCost

    data class Incomplete(
        override val productId: String,
        override val productName: String,
        val missing: List<String>,
    ) : ProductCost
}

data class CostingReport(
    val month: YearMonth,
    val wastageBp: Int,
    val products: List<ProductCost>,
    val indirectTotalCents: Long,
    val netPackets: Int,
)

enum class ExpenseKind(val label: String) { DIRECT("Direct"), INDIRECT("Indirect") }

data class ExpenseCategory(val id: String, val name: String, val kind: ExpenseKind)

data class Expense(
    val id: String,
    val categoryId: String,
    val categoryName: String,
    val kind: ExpenseKind,
    val date: LocalDate,
    val amountCents: Long,
    val description: String,
    val enteredBy: String,
    /** Set on a reversing entry: the expense it cancels. Its amount is negative. */
    val reversesId: String? = null,
    /** Required on a reversing entry. */
    val reason: String = "",
) {
    val isReversal: Boolean get() = reversesId != null
}

data class CategoryTotal(val category: ExpenseCategory, val totalCents: Long, val entries: List<Expense>)

data class ExpensesReport(
    val month: YearMonth,
    val categories: List<CategoryTotal>,
    val indirectTotalCents: Long,
    /** Direct expense: the cost of the materials consumed, calculated by the server, never typed. */
    val directExpense: Figure,
)

data class AdminReturn(
    val id: String,
    val date: LocalDate,
    val customerName: String,
    val productName: String,
    val qtyPackets: Int,
    val reason: ReturnReason,
    val resolution: ReturnResolution,
    val creditCents: Long,
    /** Information only: what the replacement packets cost. It is already inside direct expense. */
    val replacementCost: Figure,
)

/** Packets damaged in production, entered by the Admin. Add only; it counts as usage of materials. */
data class ProductionDamage(
    val id: String,
    val date: LocalDate,
    val productId: String,
    val productName: String,
    val packets: Int,
    val note: String,
    val enteredBy: String,
)

data class ReturnsReport(
    val month: YearMonth,
    val returns: List<AdminReturn>,
    val damage: List<ProductionDamage>,
    val creditsTotalCents: Long,
    val replacementPackets: Int,
    val damagedPackets: Int,
)

data class BalanceRow(
    val customerId: String,
    val customerName: String,
    val typeName: String,
    val balanceCents: Long,
    val currentCents: Long,
    val over30Cents: Long,
    val over60Cents: Long,
)

data class BusinessSettings(val businessName: String, val address: String, val phone: String, val footerText: String)

data class WorkerAccount(
    val id: String,
    val fullName: String,
    val username: String,
    val deviceCode: String,
    val isActive: Boolean,
)

/** Who changed what, before and after: mirrors the server AuditLog (Doc 2 s4.2). */
data class ChangeLogEntry(
    val id: Long,
    val at: LocalDateTime,
    val who: String,
    val what: String,
    val before: String,
    val after: String,
)

data class NamedAmount(val label: String, val cents: Long)

data class MonthSales(val month: YearMonth, val netSalesCents: Long)

/** One month at a glance (B1). The cost based figures are [Figure.Incomplete] while an input is missing. */
data class DashboardReport(
    val month: YearMonth,
    val inProgress: Boolean,
    val netSalesCents: Long,
    val cashCollectedCents: Long,
    val outstandingCents: Long,
    val directCost: Figure,
    val grossProfit: Figure,
    val indirectExpensesCents: Long,
    val netProfit: Figure,
    val sixMonthSales: List<MonthSales>,
    val salesByProduct: List<NamedAmount>,
    val salesByCustomerType: List<NamedAmount>,
    /** Everything the cost figures are waiting for, shown once under the cards. */
    val missingInputs: List<String>,
)
