package com.mamre.billing.data.admin

import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.RoomUnitOfWork
import com.mamre.billing.data.repo.BooksRepository
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.ExpenseRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.StockRepository
import com.mamre.billing.data.repo.toAdmin
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
import com.mamre.billing.domain.admin.PriceEntry
import com.mamre.billing.domain.admin.PriceMatrix
import com.mamre.billing.domain.admin.ProductCost
import com.mamre.billing.domain.admin.ProductRecipe
import com.mamre.billing.domain.admin.ProductionDamage
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.admin.RecipeLine
import com.mamre.billing.domain.admin.ReturnsReport
import com.mamre.billing.domain.admin.StockReport
import com.mamre.billing.domain.books.BooksLogic
import com.mamre.billing.domain.usecase.AddCustomer
import com.mamre.billing.domain.usecase.AddExpense
import com.mamre.billing.domain.usecase.AddProductionDamage
import com.mamre.billing.domain.usecase.AddPurchase
import com.mamre.billing.domain.usecase.ClearOverridePrice
import com.mamre.billing.domain.usecase.EditCustomer
import com.mamre.billing.domain.usecase.ReverseExpense
import com.mamre.billing.domain.usecase.ReversePurchase
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.SaveBusinessSettings
import com.mamre.billing.domain.usecase.SetDefaultPrice
import com.mamre.billing.domain.usecase.SetOverridePrice
import com.mamre.billing.domain.usecase.SetRecipeQuantity
import com.mamre.billing.domain.usecase.SetSalesmanCanEditPrice
import com.mamre.billing.domain.usecase.SetStandardPacketSize
import com.mamre.billing.domain.usecase.SetWastage
import com.mamre.billing.domain.usecase.SetYieldPerKg
import com.mamre.billing.domain.usecase.VoidBill
import com.mamre.billing.domain.worker.MAX_PACKET_SIZE
import com.mamre.billing.domain.worker.isValidPacketSize
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Admin area on the phone's database (Doc 2 s9). Every read loads a [com.mamre.billing.domain.books.BooksSnapshot]
 * and calls BooksLogic; every write goes through a use case in one transaction (Doc 2 s5.1) and then raises
 * [revision], so open screens reload. There is no call that edits or deletes a bill, payment, return, purchase or
 * expense (Doc 3 N3, Doc 2 I-9). Corporate accounts are included here: their balance is shown in the Admin area only.
 */
class LocalAdminApi(
    private val books: BooksRepository,
    private val prices: PriceRepository,
    private val stock: StockRepository,
    private val expenseRepo: ExpenseRepository,
    private val settingsRepo: SettingsRepository,
    private val voidBill: VoidBill,
    private val addCustomer: AddCustomer,
    private val editCustomer: EditCustomer,
    private val setDefaultPrice: SetDefaultPrice,
    private val setOverridePrice: SetOverridePrice,
    private val clearOverridePrice: ClearOverridePrice,
    private val setSalesmanCanEditPrice: SetSalesmanCanEditPrice,
    private val addPurchaseUseCase: AddPurchase,
    private val reversePurchaseUseCase: ReversePurchase,
    private val addExpenseUseCase: AddExpense,
    private val reverseExpenseUseCase: ReverseExpense,
    private val addDamage: AddProductionDamage,
    private val setRecipeQuantityUseCase: SetRecipeQuantity,
    private val setWastage: SetWastage,
    private val setStandardPacketSizeUseCase: SetStandardPacketSize,
    private val setYieldPerKgUseCase: SetYieldPerKg,
    private val saveBusinessSettings: SaveBusinessSettings,
    private val clock: Clock = Clock.systemDefaultZone(),
) : AdminApi {
    private val _revision = MutableStateFlow(0L)

    override val today: LocalDate get() = LocalDate.now(clock)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    private suspend fun snapshot() = books.snapshot(today)

    /** Runs a write, then tells the open screens. */
    private suspend fun <T> changed(block: suspend () -> T): T {
        val result = block()
        _revision.update { it + 1 }
        return result
    }

    private fun refuse(message: String): Nothing = throw RuleException(message)

    companion object {
        /** Wires the Admin area onto one database: the repositories and every use case, sharing one clock. */
        fun create(db: MamreDatabase, clock: Clock = Clock.systemDefaultZone(), zone: () -> ZoneId = { ZoneId.systemDefault() }): LocalAdminApi {
            val u = RoomUnitOfWork(db)
            val settings = SettingsRepository(db.settingDao())
            val customers = CustomerRepository(db)
            val prices = PriceRepository(db)
            val stock = StockRepository(db)
            val expenses = ExpenseRepository(db)
            val sales = SalesRepository(db, zone)
            val log = ChangeLogRepository(db)
            return LocalAdminApi(
                books = BooksRepository(db, zone),
                prices = prices,
                stock = stock,
                expenseRepo = expenses,
                settingsRepo = settings,
                voidBill = VoidBill(u, sales, log, clock),
                addCustomer = AddCustomer(u, customers, log, clock),
                editCustomer = EditCustomer(u, customers, log, clock),
                setDefaultPrice = SetDefaultPrice(u, customers, prices, log, clock),
                setOverridePrice = SetOverridePrice(u, customers, prices, log, clock),
                clearOverridePrice = ClearOverridePrice(u, customers, prices, log, clock),
                setSalesmanCanEditPrice = SetSalesmanCanEditPrice(u, customers, log, clock),
                addPurchaseUseCase = AddPurchase(u, stock, log, clock),
                reversePurchaseUseCase = ReversePurchase(u, stock, log, clock),
                addExpenseUseCase = AddExpense(u, expenses, log, clock),
                reverseExpenseUseCase = ReverseExpense(u, expenses, log, clock),
                addDamage = AddProductionDamage(u, stock, log, clock),
                setRecipeQuantityUseCase = SetRecipeQuantity(u, stock, log, clock),
                setWastage = SetWastage(u, settings, log, clock),
                setStandardPacketSizeUseCase = SetStandardPacketSize(u, stock, log, clock),
                setYieldPerKgUseCase = SetYieldPerKg(u, stock, log, clock),
                saveBusinessSettings = SaveBusinessSettings(u, settings, log, clock),
                clock = clock,
            )
        }
    }

    // ------------------------------------------------------------------ the data span

    override suspend fun span(): DataSpan {
        val first = snapshot().invoices.minOfOrNull { YearMonth.from(it.issuedAt) }
        val now = YearMonth.from(today)
        // With no bills yet (a first run) the span is the month in progress.
        return DataSpan(minOf(first ?: now, now), now)
    }

    override suspend fun dashboard(month: YearMonth): DashboardReport = BooksLogic.dashboard(snapshot(), month, span().first)

    // ------------------------------------------------------------------ sales

    override suspend fun invoices(): List<AdminInvoice> = snapshot().invoices

    override suspend fun invoiceDetail(id: String): InvoiceDetail? = BooksLogic.invoiceDetail(snapshot(), id)

    override suspend fun voidInvoice(id: String, reason: String): InvoiceDetail {
        changed { voidBill(id, reason) }
        return BooksLogic.invoiceDetail(snapshot(), id)!!
    }

    // ------------------------------------------------------------------ customers

    override suspend fun customerTypes(): List<AdminCustomerType> = snapshot().types

    override suspend fun customers(): List<AdminCustomer> = snapshot().customers.sortedBy { it.label.lowercase() }

    override suspend fun customer(id: String): AdminCustomer? = snapshot().customers.firstOrNull { it.id == id }

    override suspend fun customerBalance(id: String): Long = BooksLogic.balance(snapshot(), id)

    override suspend fun customerSummary(id: String, month: YearMonth): CustomerMonthSummary =
        BooksLogic.customerSummary(snapshot(), id, month)

    override suspend fun addCustomer(form: CustomerForm): AdminCustomer = changed { addCustomer.invoke(form) }

    override suspend fun updateCustomer(id: String, form: CustomerForm): AdminCustomer = changed { editCustomer(id, form) }

    // ------------------------------------------------------------------ prices

    override suspend fun products(): List<AdminProduct> = snapshot().products

    override suspend fun priceMatrix(): PriceMatrix {
        val s = snapshot()
        return PriceMatrix(
            s.products, s.types,
            prices.defaults().map { PriceEntry(it.id, it.productId, it.customerTypeId, it.unitPriceCents, it.effectiveFrom) },
        )
    }

    override suspend fun setDefaultPrice(productId: String, typeId: String, priceCents: Long, from: LocalDate) =
        changed { setDefaultPrice.invoke(productId, typeId, priceCents, from) }

    override suspend fun setWorkerCanEditPrice(typeId: String, allowed: Boolean) =
        changed { setSalesmanCanEditPrice(typeId, allowed) }

    override suspend fun overrides(customerId: String): List<OverridePrice> =
        prices.overridesOf(customerId).map {
            OverridePrice(it.id, it.customerId, it.productId, it.unitPriceCents, it.effectiveFrom, it.isActive, it.note)
        }.sortedByDescending { it.effectiveFrom }

    override suspend fun setOverride(customerId: String, productId: String, priceCents: Long, from: LocalDate, note: String) =
        changed { setOverridePrice(customerId, productId, priceCents, from, note) }

    override suspend fun clearOverride(customerId: String, productId: String) =
        changed { clearOverridePrice(customerId, productId) }

    // ------------------------------------------------------------------ costing, materials, purchases

    override suspend fun costing(month: YearMonth): CostingReport = BooksLogic.costing(snapshot(), month)

    override suspend fun materials(): List<Material> = snapshot().materials

    override suspend fun recipes(): List<ProductRecipe> {
        val s = snapshot()
        return s.products.map { p ->
            ProductRecipe(
                p.id, p.name,
                s.recipes[p.id].orEmpty().map { e ->
                    val m = s.materials.first { it.id == e.materialId }
                    RecipeLine(m.id, m.name, m.baseUnit, e.qtyMb)
                },
                yieldPerKg = p.yieldPerKg,
                standardPacketSize = p.unitsPerPacket,
            )
        }
    }

    override suspend fun setRecipeQuantity(productId: String, materialId: String, qtyMb: Long) =
        changed { setRecipeQuantityUseCase(productId, materialId, qtyMb) }

    override suspend fun wastageBp(): Int = settingsRepo.wastageBp()

    override suspend fun setWastageBp(basisPoints: Int) = changed { setWastage(basisPoints) }

    override suspend fun stock(month: YearMonth): StockReport = BooksLogic.stock(snapshot(), month)

    override suspend fun purchases(month: YearMonth): List<Purchase> =
        snapshot().purchases.filter { YearMonth.from(it.date) == month }.sortedByDescending { it.date }

    override suspend fun addPurchase(materialId: String, date: LocalDate, qtyMb: Long, totalCents: Long, note: String): Purchase {
        val row = changed { addPurchaseUseCase(materialId, date, qtyMb, totalCents, note) }
        return snapshot().purchases.first { it.id == row.id }
    }

    override suspend fun reversePurchase(purchaseId: String, reason: String): Purchase {
        val row = changed { reversePurchaseUseCase(purchaseId, reason) }
        return snapshot().purchases.first { it.id == row.id }
    }

    override suspend fun addProductionDamage(productId: String, date: LocalDate, chapathis: Int, note: String): ProductionDamage {
        val row = changed { addDamage(productId, date, chapathis, note) }
        val product = stock.product(productId)!!
        return ProductionDamage(row.id, row.damagedOn, productId, product.name, row.chapathis, row.note, owner())
    }

    override suspend fun setStandardPacketSize(productId: String, chapathis: Int) =
        changed { setStandardPacketSizeUseCase(productId, chapathis) }

    override suspend fun setYieldPerKg(productId: String, chapathisPerKg: Int) =
        changed { setYieldPerKgUseCase(productId, chapathisPerKg) }

    override suspend fun packetCost(month: YearMonth, productId: String, chapathis: Int): ProductCost {
        val s = snapshot()
        if (s.products.none { it.id == productId }) refuse("Unknown product")
        if (!isValidPacketSize(chapathis)) refuse("A packet holds 1 to $MAX_PACKET_SIZE chapathis")
        return BooksLogic.packetCost(s, month, productId, chapathis)
    }

    // ------------------------------------------------------------------ expenses

    override suspend fun expenseCategories(): List<ExpenseCategory> = expenseRepo.categories().map { it.toAdmin() }

    override suspend fun expenses(month: YearMonth): ExpensesReport = BooksLogic.expensesReport(snapshot(), month)

    override suspend fun addExpense(categoryId: String, date: LocalDate, amountCents: Long, description: String): Expense {
        val row = changed { addExpenseUseCase(categoryId, date, amountCents, description) }
        return snapshot().expenses.first { it.id == row.id }
    }

    override suspend fun reverseExpense(expenseId: String, reason: String): Expense {
        val row = changed { reverseExpenseUseCase(expenseId, reason) }
        return snapshot().expenses.first { it.id == row.id }
    }

    // ------------------------------------------------------------------ returns, balances, settings

    override suspend fun returnsReport(month: YearMonth): ReturnsReport = BooksLogic.returnsReport(snapshot(), month)

    override suspend fun balances(): List<BalanceRow> = BooksLogic.balances(snapshot())

    override suspend fun settings(): BusinessSettings = snapshot().settings

    override suspend fun saveSettings(settings: BusinessSettings) = changed { saveBusinessSettings(settings) }

    private suspend fun owner(): String = settingsRepo.ownerName().ifEmpty { BooksRepository.OWNER_LABEL }
}
