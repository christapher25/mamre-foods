package com.mamre.billing.data.demo

import com.mamre.billing.data.admin.SeedIds
import com.mamre.billing.data.admin.AdminSeed
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.RoomUnitOfWork
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.UnitOfWork
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.ExpenseRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.StockRepository
import com.mamre.billing.data.startup.StartupTask
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.books.BooksSnapshot
import com.mamre.billing.domain.usecase.AddCustomer
import com.mamre.billing.domain.usecase.AddExpense
import com.mamre.billing.domain.usecase.AddProductionDamage
import com.mamre.billing.domain.usecase.AddPurchase
import com.mamre.billing.domain.usecase.BillDraft
import com.mamre.billing.domain.usecase.MakeBill
import com.mamre.billing.domain.usecase.PaymentDraft
import com.mamre.billing.domain.usecase.RecordPayment
import com.mamre.billing.domain.usecase.RecordReturn
import com.mamre.billing.domain.usecase.ReturnDraft
import com.mamre.billing.domain.usecase.SaveBusinessSettings
import com.mamre.billing.domain.usecase.SetDefaultPrice
import com.mamre.billing.domain.usecase.SetOpeningStock
import com.mamre.billing.domain.usecase.SetOverridePrice
import com.mamre.billing.domain.usecase.VoidBill
import com.mamre.billing.domain.worker.InvoiceLine
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/**
 * DEBUG ONLY. Puts the demo history (seven months of bills, payments, returns, a void, purchases, production damage and
 * expenses, built by AdminSeed) into the REAL database on top of the reference data. It does not insert rows: it replays
 * every event through the real use cases with a clock fixed at that event's time, so every rule applies to the demo data
 * (later effective dates for prices, price limits, the corporate guard, name plus location, the number sequence).
 * An event a rule refuses makes the seeding fail loudly instead of leaving odd data. A release build has no seeder.
 */
class DemoSeeder @Inject constructor(private val db: MamreDatabase) : StartupTask {
    override suspend fun run() {
        if (db.settingDao().get(SEEDED_KEY) != null) return
        val zone = ZoneId.systemDefault()
        seed(LocalDate.now(), zone, until = LocalDateTime.now(zone))
    }

    /** A clock the seeder moves to each event's time; the use cases read it for the date they save. */
    private class SeedClock(private val zone: ZoneId) : Clock() {
        var now: Instant = Instant.EPOCH
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun at(time: LocalDateTime) {
            now = time.atZone(zone).toInstant()
        }
    }

    private class Event(val at: LocalDateTime, val order: Int, val key: String, val run: suspend () -> Unit)

    /**
     * The whole replay is ONE transaction: it is all or nothing. A run that stops halfway (a rule refuses an event, or the
     * process is killed) rolls back, the marker stays unset, and the next start tries again on a clean database. The use
     * cases inside join this transaction (Room nests them).
     *
     * [until] is "now": an event of today that would fall after it is stamped at [until] instead, so the sample data never
     * holds an entry from the future (the change log is ordered by time, and a later edit must sort after the seed).
     */
    suspend fun seed(today: LocalDate, zone: ZoneId, until: LocalDateTime? = null) {
        RoomUnitOfWork(db).run { replay(today, zone, until) }
    }

    private suspend fun replay(today: LocalDate, zone: ZoneId, until: LocalDateTime?) {
        val clock = SeedClock(zone)
        val u: UnitOfWork = RoomUnitOfWork(db)
        val settings = SettingsRepository(db.settingDao())
        val customers = CustomerRepository(db)
        val prices = PriceRepository(db)
        val sales = SalesRepository(db) { zone }
        val stock = StockRepository(db)
        val expenses = ExpenseRepository(db)
        val log = ChangeLogRepository(db)
        val makeBill = MakeBill(u, sales, customers, prices, settings, clock)
        val recordPayment = RecordPayment(u, sales, customers, settings, clock)
        val recordReturn = RecordReturn(u, sales, customers, prices, clock)
        val voidBill = VoidBill(u, sales, log, clock)

        val history: BooksSnapshot = AdminSeed.build(today)
        val first = YearMonth.from(today).minusMonths(6).atDay(1)
        clock.at(first.atStartOfDay())

        // The Owner's setup: name, business details, prices, overrides, customers and opening stock.
        settings.put(SettingKeys.OWNER_NAME, DemoCatalog.OWNER_NAME)
        SaveBusinessSettings(u, settings, log, clock)(DemoCatalog.settings)
        val setPrice = SetDefaultPrice(u, customers, prices, log, clock)
        DemoCatalog.prices(today).sortedBy { it.from }.forEach { setPrice(it.productId, it.typeId, it.cents, it.from) }
        val addCustomer = AddCustomer(u, customers, log, clock)
        history.customers.forEach { c ->
            addCustomer(CustomerForm(c.name, c.typeId, c.phone, c.address, c.paymentMode, c.notes, c.isActive, c.openingBalanceCents, c.location, c.isCorporate), c.id)
        }
        val setOverride = SetOverridePrice(u, customers, prices, log, clock)
        DemoCatalog.overrides(today).forEach { setOverride(it.customerId, it.productId, it.cents, it.from, it.note) }
        val setOpening = SetOpeningStock(u, stock, log, clock)
        history.openingStock.forEach { (materialId, o) -> setOpening(materialId, YearMonth.from(first), o.qtyMb, o.valueCents) }

        // Everything that happened, in the order it happened.
        val paymentsByInvoice = history.payments.filter { history.paymentInvoiceIds.containsKey(it.id) }
            .associateBy { history.paymentInvoiceIds.getValue(it.id) }
        val events = mutableListOf<Event>()
        history.invoices.forEach { inv ->
            val paid = paymentsByInvoice[inv.id]
            events += Event(inv.issuedAt, 1, inv.number) {
                makeBill(BillDraft(inv.id, inv.customerId, inv.lines(), paid?.amountCents ?: 0L, paid?.method))
            }
            if (inv.isVoid) {
                events += Event(inv.voidedAt!!, 2, inv.number) { voidBill(inv.id, inv.voidReason!!) }
            }
        }
        history.payments.filter { !history.paymentInvoiceIds.containsKey(it.id) }.forEach { p ->
            events += Event(p.date.atTime(12, 0), 3, p.receiptNumber) {
                recordPayment(PaymentDraft(p.id, p.customerId!!, p.amountCents, p.method, p.note))
            }
        }
        history.returns.forEach { r ->
            events += Event(r.date.atTime(14, 0), 4, r.id) {
                recordReturn(ReturnDraft(r.id, r.customerId, r.productId, r.qtyPackets, r.chapathisPerPacket, r.reason, r.resolution, r.invoiceId))
            }
        }
        val addPurchase = AddPurchase(u, stock, log, clock)
        history.purchases.forEach { p ->
            events += Event(p.date.atTime(9, 0), 5, p.id) { addPurchase(p.materialId, p.date, p.qtyMb, p.totalCents, p.note) }
        }
        val addDamage = AddProductionDamage(u, stock, log, clock)
        history.damage.forEach { d ->
            events += Event(d.date.atTime(16, 0), 6, d.id) { addDamage(d.productId, d.date, d.chapathis, d.note) }
        }
        val addExpense = AddExpense(u, expenses, log, clock)
        history.expenses.forEach { e ->
            events += Event(e.date.atTime(10, 0), 7, e.id) { addExpense(e.categoryId, e.date, e.amountCents, e.description) }
        }
        events.sortedWith(compareBy({ it.at }, { it.order }, { it.key })).forEach { e ->
            clock.at(if (until != null && e.at.isAfter(until)) until else e.at)
            e.run()
        }
        clock.at(until ?: today.atTime(12, 0))
        settings.put(SEEDED_KEY, "1")
    }

    private fun AdminInvoice.lines(): List<InvoiceLine> = items.map {
        InvoiceLine(it.productId, it.productName, it.qtyPackets, it.unitPriceCents, it.chapathisPerPacket, it.listPriceCents, it.isCustomPacket)
    }

    companion object {
        /** Marks a database that already holds the sample data, so it is made once. */
        const val SEEDED_KEY = "demo_data_seeded"
    }
}
