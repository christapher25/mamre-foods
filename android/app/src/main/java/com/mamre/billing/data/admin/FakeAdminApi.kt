package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.SharedPriceTable
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
import com.mamre.billing.domain.admin.ExpenseCheck
import com.mamre.billing.domain.admin.ExpensesReport
import com.mamre.billing.domain.admin.InvoiceDetail
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.PriceCheck
import com.mamre.billing.domain.admin.PriceEntry
import com.mamre.billing.domain.admin.PriceMatrix
import com.mamre.billing.domain.admin.ProductRecipe
import com.mamre.billing.domain.admin.ProductionDamage
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.admin.RecipeLine
import com.mamre.billing.domain.admin.ReturnsReport
import com.mamre.billing.domain.admin.StockReport
import com.mamre.billing.domain.admin.formatRecipeQuantity
import com.mamre.billing.domain.admin.MAX_WASTAGE_BP
import com.mamre.billing.domain.admin.formatWastage
import com.mamre.billing.domain.admin.formatQuantity
import com.mamre.billing.domain.admin.WorkerAccount
import com.mamre.billing.domain.admin.cleanVoidReason
import com.mamre.billing.domain.admin.priceProblemMessage
import com.mamre.billing.domain.admin.validateCustomerForm
import com.mamre.billing.domain.admin.validateExpense
import com.mamre.billing.domain.admin.validateNewPrice
import com.mamre.billing.domain.admin.validateSettings
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.money.centsToPlain
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.InvoiceStatus
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * DEMO DATA. The server stand-in for the Admin screens (Doc 2 s1.1), used only when USE_FAKE_API is
 * true; the release build does not contain it. Its own seeded data set, not linked to the worker's
 * DemoStore (owner decision), resets when the app restarts. It enforces the same rules the real
 * server will: price edits need a later date, a void needs a reason, expenses are only added, and
 * every edit appends to the change log (who, what, before, after).
 */
class FakeAdminApi(
    private val clock: Clock = Clock.systemDefaultZone(),
    /** The one price table shared with the worker's FakeApi (DECISIONS 2026-10-03). */
    private val prices: SharedPriceTable = SharedPriceTable.seeded(LocalDate.now(clock)),
    seed: ServerState = AdminSeed.build(LocalDate.now(clock), prices),
) : AdminApi {
    private val state = MutableStateFlow(seed)
    private val _revision = MutableStateFlow(0L)
    private val _log = MutableStateFlow<List<ChangeLogEntry>>(emptyList())
    private var counter = 0L

    override val today: LocalDate = seed.today
    override val revision: StateFlow<Long> = _revision.asStateFlow()
    override val changeLog: StateFlow<List<ChangeLogEntry>> = _log.asStateFlow()

    private val s: ServerState get() = state.value

    private fun changed(who: String, what: String, before: String, after: String, change: (ServerState) -> ServerState) {
        state.update(change)
        counter++
        _log.update { listOf(ChangeLogEntry(counter, LocalDateTime.now(clock), who, what, before, after)) + it }
        _revision.update { it + 1 }
    }

    private fun refuse(message: String): Nothing = throw AdminRuleException(message)

    override suspend fun span(): DataSpan {
        val first = s.invoices.minOf { YearMonth.from(it.issuedAt) }
        return DataSpan(first, YearMonth.from(today))
    }

    override suspend fun dashboard(month: YearMonth): DashboardReport = ServerLogic.dashboard(s, month, span().first)

    // ------------------------------------------------------------------ sales

    override suspend fun invoices(): List<AdminInvoice> = s.invoices

    override suspend fun invoiceDetail(id: String): InvoiceDetail? = ServerLogic.invoiceDetail(s, id)

    override suspend fun voidInvoice(id: String, reason: String, by: String): InvoiceDetail {
        val clean = cleanVoidReason(reason) ?: refuse("A void needs a reason")
        val inv = s.invoices.firstOrNull { it.id == id } ?: refuse("Invoice not found")
        if (inv.isVoid) refuse("Invoice ${inv.number} is already void")
        val at = LocalDateTime.now(clock)
        changed(by, "Void invoice ${inv.number}", "Active, ${formatCents(inv.totalCents)}", "Void: $clean") { st ->
            st.copy(
                invoices = st.invoices.map {
                    if (it.id == id) it.copy(status = InvoiceStatus.VOID, voidReason = clean, voidedBy = by, voidedAt = at) else it
                },
            )
        }
        return ServerLogic.invoiceDetail(s, id)!!
    }

    // ------------------------------------------------------------------ customers

    override suspend fun customerTypes(): List<AdminCustomerType> = s.types
    override suspend fun customers(): List<AdminCustomer> = s.customers.sortedBy { it.name.lowercase() }
    override suspend fun customer(id: String): AdminCustomer? = s.customers.firstOrNull { it.id == id }
    override suspend fun customerBalance(id: String): Long = ServerLogic.balance(s, id)
    override suspend fun customerSummary(id: String, month: YearMonth): CustomerMonthSummary =
        ServerLogic.customerSummary(s, id, month)

    private fun checkForm(form: CustomerForm) {
        val problems = validateCustomerForm(form)
        if (problems.isNotEmpty()) refuse("Check the customer: ${problems.joinToString { it.name.lowercase().replace('_', ' ') }}")
        if (s.types.none { it.id == form.typeId }) refuse("Unknown customer type")
    }

    private fun describe(c: AdminCustomer) =
        "${c.name}, ${c.typeName}, ${if (c.paymentMode == PaymentMode.CREDIT) "Credit" else "Cash"}, " +
            "phone '${c.phone}', address '${c.address}', notes '${c.notes}', ${if (c.isActive) "active" else "inactive"}"

    override suspend fun addCustomer(form: CustomerForm, by: String): AdminCustomer {
        checkForm(form)
        val c = AdminCustomer(
            id = "c-new-${s.customers.size + 1}",
            name = form.name.trim(), typeId = form.typeId,
            typeName = s.types.first { it.id == form.typeId }.name,
            phone = form.phone.trim(), address = form.address.trim(), paymentMode = form.paymentMode,
            notes = form.notes.trim(), isActive = form.isActive, openingBalanceCents = form.openingBalanceCents,
        )
        changed(by, "Add customer ${c.name}", "-", describe(c)) { it.copy(customers = it.customers + c) }
        return c
    }

    override suspend fun updateCustomer(id: String, form: CustomerForm, by: String): AdminCustomer {
        checkForm(form)
        val old = s.customers.firstOrNull { it.id == id } ?: refuse("Customer not found")
        val updated = old.copy(
            name = form.name.trim(), typeId = form.typeId, typeName = s.types.first { it.id == form.typeId }.name,
            phone = form.phone.trim(), address = form.address.trim(), paymentMode = form.paymentMode,
            notes = form.notes.trim(), isActive = form.isActive,
        )
        changed(by, "Edit customer ${old.name}", describe(old), describe(updated)) { st ->
            st.copy(customers = st.customers.map { if (it.id == id) updated else it })
        }
        return updated
    }

    // ------------------------------------------------------------------ prices

    override suspend fun products(): List<AdminProduct> = s.products
    override suspend fun priceMatrix() = PriceMatrix(
        s.products, s.types,
        prices.all().map { PriceEntry(it.id, it.productId, it.customerTypeId, it.unitPriceCents, it.effectiveFrom) },
    )

    override suspend fun setDefaultPrice(productId: String, typeId: String, priceCents: Long, from: LocalDate, by: String) {
        val product = s.products.firstOrNull { it.id == productId } ?: refuse("Unknown product")
        val type = s.types.firstOrNull { it.id == typeId } ?: refuse("Unknown customer type")
        val latest = prices.all().filter { it.productId == productId && it.customerTypeId == typeId }.maxByOrNull { it.effectiveFrom }
        val check = validateNewPrice(centsToPlain(priceCents), from, latest?.effectiveFrom)
        if (check is PriceCheck.Invalid) refuse(check.problems.joinToString { priceProblemMessage(it, latest?.effectiveFrom) })
        val before = latest?.let { "${formatCents(it.unitPriceCents)} from ${it.effectiveFrom}" } ?: "no price"
        prices.add(productId, typeId, priceCents, from) // workers receive it at their next sync
        changed(by, "Price ${product.name} / ${type.name}", before, "${formatCents(priceCents)} from $from") { it }
    }

    override suspend fun overrides(customerId: String): List<OverridePrice> =
        s.overrides.filter { it.customerId == customerId }.sortedByDescending { it.effectiveFrom }

    override suspend fun setOverride(customerId: String, productId: String, priceCents: Long, from: LocalDate, note: String, by: String) {
        val customer = s.customers.firstOrNull { it.id == customerId } ?: refuse("Customer not found")
        val product = s.products.firstOrNull { it.id == productId } ?: refuse("Unknown product")
        val latest = s.overrides.filter { it.customerId == customerId && it.productId == productId && it.isActive }.maxByOrNull { it.effectiveFrom }
        val check = validateNewPrice(centsToPlain(priceCents), from, latest?.effectiveFrom)
        if (check is PriceCheck.Invalid) refuse(check.problems.joinToString { priceProblemMessage(it, latest?.effectiveFrom) })
        val before = latest?.let { "${formatCents(it.unitPriceCents)} from ${it.effectiveFrom}" } ?: "no override"
        changed(by, "Override ${customer.name} / ${product.name}", before, "${formatCents(priceCents)} from $from") { st ->
            st.copy(overrides = st.overrides + OverridePrice("po-${st.overrides.size + 1}", customerId, productId, priceCents, from, true, note.trim()))
        }
    }

    override suspend fun clearOverride(customerId: String, productId: String, by: String) {
        val customer = s.customers.firstOrNull { it.id == customerId } ?: refuse("Customer not found")
        val product = s.products.firstOrNull { it.id == productId } ?: refuse("Unknown product")
        val active = s.overrides.filter { it.customerId == customerId && it.productId == productId && it.isActive }
        if (active.isEmpty()) refuse("There is no override to clear")
        val latest = active.maxByOrNull { it.effectiveFrom }!!
        // The row is kept and switched off: price history is never deleted (Doc 2 s4.2).
        changed(by, "Clear override ${customer.name} / ${product.name}", "${formatCents(latest.unitPriceCents)} from ${latest.effectiveFrom}", "no override") { st ->
            st.copy(overrides = st.overrides.map { if (it in active) it.copy(isActive = false) else it })
        }
    }

    // ------------------------------------------------------------------ costing, materials, purchases

    override suspend fun costing(month: YearMonth): CostingReport = ServerLogic.costing(s, month)
    override suspend fun materials(): List<Material> = s.materials

    override suspend fun recipes(): List<ProductRecipe> = s.products.map { p ->
        ProductRecipe(
            p.id, p.name,
            s.recipes[p.id].orEmpty().map { e ->
                val m = s.materials.first { it.id == e.materialId }
                RecipeLine(m.id, m.name, m.baseUnit, e.qtyMb)
            },
        )
    }

    override suspend fun setRecipeQuantity(productId: String, materialId: String, qtyMb: Long, by: String) {
        val product = s.products.firstOrNull { it.id == productId } ?: refuse("Unknown product")
        val material = s.materials.firstOrNull { it.id == materialId } ?: refuse("Unknown material")
        val line = s.recipes[productId]?.firstOrNull { it.materialId == materialId } ?: refuse("${material.name} is not in the recipe of ${product.name}")
        if (qtyMb <= 0) refuse("The quantity must be more than zero")
        fun d(q: Long?) = q?.let { formatRecipeQuantity(it, material.baseUnit) } ?: "not set"
        changed(by, "Recipe ${product.name} / ${material.name}", d(line.qtyMb), d(qtyMb)) { st ->
            st.copy(
                recipes = st.recipes.mapValues { (pid, lines) ->
                    if (pid == productId) lines.map { if (it.materialId == materialId) it.copy(qtyMb = qtyMb) else it } else lines
                },
            )
        }
    }

    override suspend fun wastageBp(): Int = s.wastageBp

    override suspend fun setWastageBp(basisPoints: Int, by: String) {
        if (basisPoints < 0 || basisPoints > MAX_WASTAGE_BP) refuse("Wastage must be between 0% and 5%")
        val old = s.wastageBp
        changed(by, "Wastage", "${formatWastage(old)}%", "${formatWastage(basisPoints)}%") { it.copy(wastageBp = basisPoints) }
    }

    override suspend fun stock(month: YearMonth): StockReport = ServerLogic.stock(s, month)

    override suspend fun purchases(month: YearMonth): List<Purchase> =
        s.purchases.filter { YearMonth.from(it.date) == month }.sortedByDescending { it.date }

    override suspend fun addPurchase(materialId: String, date: LocalDate, qtyMb: Long, totalCents: Long, note: String, by: String): Purchase {
        val material = s.materials.firstOrNull { it.id == materialId } ?: refuse("Unknown material")
        if (qtyMb <= 0) refuse("The quantity must be more than zero")
        if (totalCents <= 0) refuse("The total paid must be more than $0.00")
        val p = Purchase(
            id = "pu-%03d".format(s.purchases.size + 1), date = date, materialId = material.id, materialName = material.name,
            qtyMb = qtyMb, totalCents = totalCents, note = note.trim(), enteredBy = by,
        )
        changed(by, "Add purchase ${material.name}", "-", "${formatQuantity(qtyMb, material)} for ${formatCents(totalCents)} on $date") {
            it.copy(purchases = it.purchases + p)
        }
        return p
    }

    override suspend fun reversePurchase(purchaseId: String, reason: String, by: String): Purchase {
        val clean = cleanVoidReason(reason) ?: refuse("A reversal needs a reason")
        val original = s.purchases.firstOrNull { it.id == purchaseId } ?: refuse("Purchase not found")
        if (original.isReversal) refuse("A reversing entry cannot be reversed; add a new purchase instead")
        if (s.purchases.any { it.reversesId == purchaseId }) refuse("This purchase is already reversed")
        val material = s.materials.first { it.id == original.materialId }
        val rev = Purchase(
            id = "pu-%03d".format(s.purchases.size + 1), date = today, materialId = original.materialId,
            materialName = original.materialName, qtyMb = -original.qtyMb, totalCents = -original.totalCents,
            note = "Reverses ${original.id}", enteredBy = by, reversesId = original.id, reason = clean,
        )
        changed(
            by, "Reverse purchase ${material.name}",
            "${formatQuantity(original.qtyMb, material)} for ${formatCents(original.totalCents)} on ${original.date}",
            "reversed on $today: $clean",
        ) { it.copy(purchases = it.purchases + rev) }
        return rev
    }

    override suspend fun addProductionDamage(productId: String, date: LocalDate, packets: Int, note: String, by: String): ProductionDamage {
        val product = s.products.firstOrNull { it.id == productId } ?: refuse("Unknown product")
        if (packets <= 0) refuse("Packets must be more than zero")
        val row = DamageRow("dm-%03d".format(s.damage.size + 1), date, product.id, packets, note.trim(), by)
        changed(by, "Add production damage ${product.name}", "-", "$packets packets on $date ${row.note}".trim()) {
            it.copy(damage = it.damage + row)
        }
        return ProductionDamage(row.id, date, product.id, product.name, packets, row.note, by)
    }

    // ------------------------------------------------------------------ expenses

    override suspend fun expenseCategories(): List<ExpenseCategory> = s.categories
    override suspend fun expenses(month: YearMonth): ExpensesReport = ServerLogic.expensesReport(s, month)

    override suspend fun addExpense(categoryId: String, date: LocalDate, amountCents: Long, description: String, by: String): Expense {
        val check = validateExpense(categoryId, date, centsToPlain(amountCents))
        if (check is ExpenseCheck.Invalid) refuse("Check the expense: ${check.problems.joinToString { it.name.lowercase().replace('_', ' ') }}")
        val category = s.categories.firstOrNull { it.id == categoryId } ?: refuse("Unknown category")
        val e = Expense(
            id = "ex-%03d".format(s.expenses.size + 1), categoryId = category.id, categoryName = category.name,
            kind = category.kind, date = date, amountCents = amountCents, description = description.trim(), enteredBy = by,
        )
        changed(by, "Add expense ${category.name}", "-", "${formatCents(amountCents)} on $date ${e.description}".trim()) {
            it.copy(expenses = it.expenses + e)
        }
        return e
    }

    override suspend fun reverseExpense(expenseId: String, reason: String, by: String): Expense {
        val clean = cleanVoidReason(reason) ?: refuse("A reversal needs a reason")
        val original = s.expenses.firstOrNull { it.id == expenseId } ?: refuse("Expense not found")
        if (original.isReversal) refuse("A reversing entry cannot be reversed; add a new expense instead")
        if (s.expenses.any { it.reversesId == expenseId }) refuse("This expense is already reversed")
        val rev = Expense(
            id = "ex-%03d".format(s.expenses.size + 1), categoryId = original.categoryId, categoryName = original.categoryName,
            kind = original.kind, date = today, amountCents = -original.amountCents, description = "Reverses ${original.id}",
            enteredBy = by, reversesId = original.id, reason = clean,
        )
        changed(
            by, "Reverse expense ${original.categoryName}",
            "${formatCents(original.amountCents)} on ${original.date} ${original.description}".trim(),
            "reversed on $today: $clean",
        ) { it.copy(expenses = it.expenses + rev) }
        return rev
    }

    // ------------------------------------------------------------------ returns, balances, settings

    override suspend fun returnsReport(month: YearMonth): ReturnsReport = ServerLogic.returnsReport(s, month)
    override suspend fun balances(): List<BalanceRow> = ServerLogic.balances(s)

    override suspend fun settings(): BusinessSettings = s.settings

    override suspend fun saveSettings(settings: BusinessSettings, by: String) {
        if (validateSettings(settings).isNotEmpty()) refuse("The business needs a name")
        val old = s.settings
        fun d(b: BusinessSettings) = "${b.businessName} | ${b.address} | ${b.phone} | ${b.footerText}"
        changed(by, "Edit business settings", d(old), d(settings)) { it.copy(settings = settings) }
    }

    override suspend fun workers(): List<WorkerAccount> = s.workers
}
