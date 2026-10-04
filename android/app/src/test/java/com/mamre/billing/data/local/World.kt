package com.mamre.billing.data.local

import com.mamre.billing.data.repo.BooksRepository
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.ExpenseRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.StockRepository
import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.AddCustomer
import com.mamre.billing.domain.usecase.AddExpense
import com.mamre.billing.domain.usecase.AddProductionDamage
import com.mamre.billing.domain.usecase.AddPurchase
import com.mamre.billing.domain.usecase.BillDraft
import com.mamre.billing.domain.usecase.ClearOverridePrice
import com.mamre.billing.domain.usecase.EditCustomer
import com.mamre.billing.domain.usecase.MakeBill
import com.mamre.billing.domain.usecase.PaymentDraft
import com.mamre.billing.domain.usecase.RecordPayment
import com.mamre.billing.domain.usecase.RecordReturn
import com.mamre.billing.domain.usecase.ReturnDraft
import com.mamre.billing.domain.usecase.ReverseExpense
import com.mamre.billing.domain.usecase.ReversePurchase
import com.mamre.billing.domain.usecase.SaveBusinessSettings
import com.mamre.billing.domain.usecase.SetDefaultPrice
import com.mamre.billing.domain.usecase.SetOpeningStock
import com.mamre.billing.domain.usecase.SetOverridePrice
import com.mamre.billing.domain.usecase.SetRecipeQuantity
import com.mamre.billing.domain.usecase.SetSalesmanCanEditPrice
import com.mamre.billing.domain.usecase.SetStandardPacketSize
import com.mamre.billing.domain.usecase.SetWastage
import com.mamre.billing.domain.usecase.SetYieldPerKg
import com.mamre.billing.domain.usecase.VoidBill
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock the tests move by hand: every use case reads it for the bill, payment and return date. */
class MutableClock(start: String = "2026-10-04T10:00:00Z") : Clock() {
    private var now: Instant = Instant.parse(start)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun set(iso: String) {
        now = Instant.parse(iso)
    }

    fun advanceMinutes(minutes: Long) {
        now = now.plusSeconds(minutes * 60)
    }
}

/** Every repository and use case on one real database. Build it again on a reopened database to see what was stored. */
class World(val db: MamreDatabase, val clock: MutableClock = MutableClock()) {
    val unitOfWork = RoomUnitOfWork(db)
    private val zone = { ZoneOffset.UTC as ZoneId }
    val settings = SettingsRepository(db.settingDao())
    val customers = CustomerRepository(db)
    val prices = PriceRepository(db)
    val sales = SalesRepository(db, zone)
    val stock = StockRepository(db)
    val expenses = ExpenseRepository(db)
    val changeLog = ChangeLogRepository(db)
    val books = BooksRepository(db, zone)

    val makeBill = MakeBill(unitOfWork, sales, customers, prices, settings, clock)
    val recordPayment = RecordPayment(unitOfWork, sales, customers, settings, clock)
    val recordReturn = RecordReturn(unitOfWork, sales, customers, prices, clock)
    val voidBill = VoidBill(unitOfWork, sales, changeLog, clock)
    val addCustomer = AddCustomer(unitOfWork, customers, changeLog, clock)
    val editCustomer = EditCustomer(unitOfWork, customers, changeLog, clock)
    val setDefaultPrice = SetDefaultPrice(unitOfWork, customers, prices, changeLog, clock)
    val setOverride = SetOverridePrice(unitOfWork, customers, prices, changeLog, clock)
    val clearOverride = ClearOverridePrice(unitOfWork, customers, prices, changeLog, clock)
    val setCanEditPrice = SetSalesmanCanEditPrice(unitOfWork, customers, changeLog, clock)
    val addPurchase = AddPurchase(unitOfWork, stock, changeLog, clock)
    val reversePurchase = ReversePurchase(unitOfWork, stock, changeLog, clock)
    val addExpense = AddExpense(unitOfWork, expenses, changeLog, clock)
    val reverseExpense = ReverseExpense(unitOfWork, expenses, changeLog, clock)
    val addDamage = AddProductionDamage(unitOfWork, stock, changeLog, clock)
    val setOpeningStock = SetOpeningStock(unitOfWork, stock, changeLog, clock)
    val setRecipe = SetRecipeQuantity(unitOfWork, stock, changeLog, clock)
    val setWastage = SetWastage(unitOfWork, settings, changeLog, clock)
    val setPacketSize = SetStandardPacketSize(unitOfWork, stock, changeLog, clock)
    val setYield = SetYieldPerKg(unitOfWork, stock, changeLog, clock)
    val saveSettings = SaveBusinessSettings(unitOfWork, settings, changeLog, clock)

    val today: LocalDate get() = LocalDate.now(clock)

    suspend fun seed() = ReferenceSeed(db, unitOfWork).run()

    /** A price for both products for a type, effective [from]. */
    suspend fun price(typeId: String, fresh: Long, chapathi: Long, from: LocalDate = LocalDate.of(2026, 1, 1)) {
        setDefaultPrice(ReferenceIds.PRODUCT_FRESH, typeId, fresh, from)
        setDefaultPrice(ReferenceIds.PRODUCT_CHAPATHI, typeId, chapathi, from)
    }

    suspend fun customer(
        name: String,
        location: String = "Irving",
        typeId: String = ReferenceIds.TYPE_RESTAURANT,
        mode: PaymentMode = PaymentMode.CREDIT,
        corporate: Boolean = false,
        opening: Long = 0,
    ): AdminCustomer = addCustomer(
        CustomerForm(name, typeId, "", "", mode, "", true, opening, location, corporate),
    )

    fun line(
        product: String = ReferenceIds.PRODUCT_FRESH,
        name: String = "Mamre Fresh Chapathi",
        qty: Int = 10,
        unit: Long,
        list: Long = unit,
        size: Int = 12,
    ) = InvoiceLine(product, name, qty, unit, size, list)

    fun draft(customerId: String?, lines: List<InvoiceLine>, paid: Long = 0, method: PaymentMethod? = null, id: String = UUID.randomUUID().toString()) =
        BillDraft(id, customerId, lines, paid, method)

    fun payment(customerId: String, cents: Long, method: PaymentMethod = PaymentMethod.CASH, note: String = "", id: String = UUID.randomUUID().toString()) =
        PaymentDraft(id, customerId, cents, method, note)

    fun returnOf(customerId: String, qty: Int, unit: Long, credit: Boolean = true, id: String = UUID.randomUUID().toString()) = ReturnDraft(
        id, customerId, ReferenceIds.PRODUCT_FRESH, qty, com.mamre.billing.domain.worker.ReturnReason.DAMAGED,
        if (credit) com.mamre.billing.domain.worker.ReturnResolution.CREDIT else com.mamre.billing.domain.worker.ReturnResolution.REPLACEMENT, unit,
    )
}
