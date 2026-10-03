package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.Expense
import com.mamre.billing.domain.admin.ExpenseCategory
import com.mamre.billing.domain.admin.ExpenseKind
import com.mamre.billing.domain.admin.InvoiceItem
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.MaterialUnit
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.PriceEntry
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.domain.admin.WorkerAccount
import com.mamre.billing.domain.admin.DEFAULT_WASTAGE_BP
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.InvoiceStatus
import com.mamre.billing.domain.worker.customPacketPriceCents
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnResolution
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Ids the tests and screens refer to. DEMO DATA. */
object SeedIds {
    // Products and customer types use the worker's ids because the price table is shared (DECISIONS 2026-10-03).
    const val FRESH = DemoIds.FRESH
    const val CHAPATHI = DemoIds.CHAPATHI
    const val RESTAURANT = DemoIds.RESTAURANT_TYPE
    const val SHOP = DemoIds.SHOP_TYPE
    const val RETAIL = DemoIds.RETAIL_TYPE
    const val WHEAT = "i-wheat"
    const val OIL = "i-oil"
    const val SUGAR = "i-sugar"
    const val SALT = "i-salt"
    const val BAKING_POWDER = "i-baking-powder"
    const val SORBATE = "i-sorbate"
    const val PACKING = "i-packing"
    const val CATERING = DemoIds.CATERING_TYPE
    // Customers use the worker's ids where both sides had one, so each customer exists once (change set C1).
    const val SPICE_GARDEN = DemoIds.RESTAURANT
    const val PATEL_MART = DemoIds.SHOP
    const val RAO_FAMILY = DemoIds.RETAIL_CUSTOMER
    const val ROYAL_BANQUETS = DemoIds.CATERING
    const val CORNER_SHOP = "c-corner-shop"
    const val CAT_ELECTRICITY = "x-electricity"
    const val CAT_MACHINE = "x-machine"
    const val CAT_LABOUR = "x-labour"
}

private const val PACKING_CENTS = 15L
private const val LAUNCH_AFTER_MONTHS = 2L // Mamre Chapathi is sold from the third month of the demo data

/** How a customer behaves in the demo history. Name, type and payment mode come from the shared catalog. */
private data class Behaviour(
    val id: String,
    val baseQty: Int,
    val everyDays: Int,
    val offset: Int,
    val opening: Long = 0,
    val payPercent: Int = 60,
    val stoppedPayingDaysAgo: Int? = null,
)

private data class CustomerSeed(
    val id: String,
    val name: String,
    val typeId: String,
    val mode: PaymentMode,
    val baseQty: Int,
    val everyDays: Int,
    val offset: Int,
    val opening: Long = 0,
    /** Share of the balance a credit customer pays on each pay day. */
    val payPercent: Int = 60,
    /** A customer who stopped paying this many days before today (their old invoices age). */
    val stoppedPayingDaysAgo: Int? = null,
)

/**
 * Builds the Admin's demo data deterministically from [today]: seven months of invoices, payments,
 * returns, purchases, production damage and expenses that obey the ledger rules of Doc 1 s6 and s11.
 * No random numbers: the same day always gives the same data, so tests can pin it.
 *
 * Demo choices (not in the docs): Mamre Chapathi is sold from the third month, so the first two
 * months have complete costs and the later ones show INCOMPLETE (potassium sorbate quantity,
 * Doc 1 P-2); the material prices are the invented ones of Doc 1 s9.5 (P-3 is pending).
 */
object AdminSeed {
    private val behaviours = listOf(
        Behaviour(SeedIds.SPICE_GARDEN, 80, 3, 0),
        Behaviour("c-curry-house", 64, 3, 1, opening = 15_000, payPercent = 70),
        Behaviour("c-taj-kitchen", 72, 4, 2, stoppedPayingDaysAgo = 45),
        Behaviour("c-masala-bistro", 48, 4, 3),
        Behaviour(SeedIds.PATEL_MART, 48, 5, 0, payPercent = 80),
        Behaviour(SeedIds.CORNER_SHOP, 36, 5, 2, stoppedPayingDaysAgo = 120),
        Behaviour("c-desi-grocers", 40, 6, 1),
        Behaviour(SeedIds.RAO_FAMILY, 12, 9, 4),
        Behaviour("c-sharma-family", 16, 10, 5, payPercent = 40),
        Behaviour(SeedIds.ROYAL_BANQUETS, 96, 6, 3, payPercent = 60),
    )

    fun build(today: LocalDate, prices: SharedPriceTable = SharedPriceTable.seeded(today)): ServerState {
        val sharedCustomers = prices.customers().associateBy { it.id }
        val customerSeeds = behaviours.map { b ->
            val row = sharedCustomers.getValue(b.id)
            CustomerSeed(b.id, row.name, row.typeId, row.paymentMode, b.baseQty, b.everyDays, b.offset, b.opening, b.payPercent, b.stoppedPayingDaysAgo)
        }
        val last = YearMonth.from(today)
        val first = last.minusMonths(6)
        val start = first.atDay(1)
        val launch = first.plusMonths(LAUNCH_AFTER_MONTHS).atDay(1)

        val types = prices.types().map { AdminCustomerType(it.id, it.name, it.workerCanEditPrice) }
        val typeName = types.associate { it.id to it.name }
        val products = prices.products().map { AdminProduct(it.id, it.code, it.name, it.standardPacketSize, PACKING_CENTS) }
        val productName = products.associate { it.id to it.name }
        val customers = customerSeeds.map {
            AdminCustomer(
                id = it.id, name = it.name, typeId = it.typeId, typeName = typeName.getValue(it.typeId),
                phone = "", address = "", paymentMode = it.mode, notes = "", isActive = true,
                openingBalanceCents = it.opening,
            )
        }

        val priceEntries = prices.all().map { PriceEntry(it.id, it.productId, it.customerTypeId, it.unitPriceCents, it.effectiveFrom) }
        val overrideNotes = mapOf("po-1" to "Volume customer", "po-2" to "Agreed at signup")
        val overrides = prices.overrides().map { OverridePrice(it.id, it.customerId, it.productId, it.unitPriceCents, it.effectiveFrom, it.isActive, overrideNotes[it.id].orEmpty()) }

        fun unitPrice(c: CustomerSeed?, productId: String, typeId: String, date: LocalDate): Long? {
            val o = overrides.filter {
                c != null && it.customerId == c.id && it.productId == productId && it.isActive && !it.effectiveFrom.isAfter(date)
            }.maxByOrNull { it.effectiveFrom }
            if (o != null) return o.unitPriceCents
            return priceEntries
                .filter { it.productId == productId && it.typeId == typeId && !it.effectiveFrom.isAfter(date) }
                .maxByOrNull { it.effectiveFrom }?.unitPriceCents
        }

        val stdSize = products.associate { it.id to it.unitsPerPacket }
        fun item(productId: String, qty: Int, listStd: Long, size: Int, changePercent: Int = 0): InvoiceItem {
            val std = stdSize.getValue(productId)
            val list = customPacketPriceCents(listStd, size, std)
            val charged = list - list * changePercent / 100
            return InvoiceItem(productId, productName.getValue(productId), qty, charged, qty * charged, size, list, size != std)
        }

        // --- invoices, payments and returns, day by day ---
        val invoices = mutableListOf<AdminInvoice>()
        val payments = mutableListOf<AdminPayment>()
        val paymentInvoice = mutableMapOf<String, String>()
        val returns = mutableListOf<ReturnRow>()
        val balances = customerSeeds.associate { it.id to it.opening }.toMutableMap()
        var invoiceSeq = 0
        var receiptSeq = 0
        val methods = listOf(PaymentMethod.CASH, PaymentMethod.ZELLE, PaymentMethod.CHECK)
        val reasons = ReturnReason.entries

        fun addPayment(customerId: String?, name: String, date: LocalDate, cents: Long, method: PaymentMethod, invoiceId: String?) {
            receiptSeq++
            val id = "pay-%04d".format(receiptSeq)
            payments += AdminPayment(id, "RCP-W1-%04d".format(receiptSeq), customerId, name, date, cents, method, "")
            if (invoiceId != null) paymentInvoice[id] = invoiceId
            if (customerId != null) balances[customerId] = balances.getValue(customerId) - cents
        }

        var day = start
        var dayIndex = 0
        while (!day.isAfter(today)) {
            customerSeeds.forEachIndexed { ci, c ->
                if ((dayIndex - c.offset) >= 0 && (dayIndex - c.offset) % c.everyDays == 0) {
                    val lines = mutableListOf<InvoiceItem>()
                    fun line(productId: String, qty: Int, size: Int = stdSize.getValue(productId), changePercent: Int = 0) {
                        val price = unitPrice(c, productId, c.typeId, day) ?: return
                        lines += item(productId, qty, price, size, changePercent)
                    }
                    // Types whose flag lets the worker change a price sometimes charge 5% less, and now and then
                    // order a custom packet of 10 chapathis (change set C2 and C3 demo data).
                    val canEdit = types.first { it.id == c.typeId }.workerCanEditPrice
                    line(SeedIds.FRESH, c.baseQty + (dayIndex * 7 + ci * 5) % (c.baseQty / 2 + 1), changePercent = if (canEdit && invoiceSeq % 4 == 0) 5 else 0)
                    if (canEdit && (dayIndex + ci) % 5 == 2) line(SeedIds.FRESH, 3 + dayIndex % 4, size = 10)
                    if (!day.isBefore(launch) && (dayIndex + ci) % 3 != 0) {
                        line(SeedIds.CHAPATHI, maxOf(4, c.baseQty / 2 + (dayIndex * 3 + ci) % (c.baseQty / 4 + 1)))
                    }
                    invoiceSeq++
                    val inv = newInvoice(invoiceSeq, c.id, c.name, typeName.getValue(c.typeId), day, 8 + ci / 3, (ci % 3) * 20, lines)
                    invoices += inv
                    balances[c.id] = balances.getValue(c.id) + inv.totalCents
                    if (c.mode == PaymentMode.CASH) {
                        addPayment(c.id, c.name, day, inv.totalCents, if (invoiceSeq % 4 == 0) PaymentMethod.CARD else PaymentMethod.CASH, inv.id)
                    }
                    // Restaurants return something now and then: credit or free replacement.
                    if (c.typeId == SeedIds.RESTAURANT && invoiceSeq % 9 == 0) {
                        val l = lines.first()
                        val packets = minOf(l.qtyPackets, 2 + invoiceSeq % 4)
                        val resolution = if (invoiceSeq % 2 == 0) ReturnResolution.CREDIT else ReturnResolution.REPLACEMENT
                        val credit = if (resolution == ReturnResolution.CREDIT) packets * l.unitPriceCents else 0L
                        val on = if (day.isBefore(today)) day.plusDays(1) else day
                        returns += ReturnRow(
                            "ret-%03d".format(returns.size + 1), on, c.id, c.name, typeName.getValue(c.typeId), inv.id,
                            l.productId, l.productName, packets, l.chapathisPerPacket, reasons[invoiceSeq % reasons.size], resolution,
                            l.unitPriceCents, credit,
                        )
                        balances[c.id] = balances.getValue(c.id) - credit
                    }
                }
            }
            // Walk-in sales: anonymous retail, paid in full at once, no ledger.
            if (dayIndex % 2 == 0) {
                val price = unitPrice(null, SeedIds.FRESH, SeedIds.RETAIL, day)!!
                val qty = 4 + dayIndex % 5
                invoiceSeq++
                // Walk-ins follow the Retail rules, so some buy a custom packet of 10 chapathis.
                val walkInItems = listOfNotNull(
                    item(SeedIds.FRESH, qty, price, stdSize.getValue(SeedIds.FRESH)),
                    if (dayIndex % 6 == 0) item(SeedIds.FRESH, 2, price, 10) else null,
                )
                val inv = newInvoice(invoiceSeq, null, "Walk-in", typeName.getValue(SeedIds.RETAIL), day, 13, 0, walkInItems)
                invoices += inv
                addPayment(null, "Walk-in", day, inv.totalCents, PaymentMethod.CASH, inv.id)
            }
            // Credit customers pay part of what they owe in the middle and at the end of each month.
            if (day.dayOfMonth == 15 || day.dayOfMonth == 28) {
                customerSeeds.filter { it.mode == PaymentMode.CREDIT }.forEachIndexed { ci, c ->
                    val owed = balances.getValue(c.id)
                    val stopped = c.stoppedPayingDaysAgo?.let { day.isAfter(today.minusDays(it.toLong())) } ?: false
                    val pay = (owed * c.payPercent / 100) / 500 * 500
                    if (pay > 0 && !stopped) addPayment(c.id, c.name, day, pay, methods[(dayIndex + ci) % methods.size], null)
                }
            }
            day = day.plusDays(1)
            dayIndex++
        }

        // One invoice voided by the Admin in an earlier month, to show VOID in lists and reports.
        val voidAt = invoices.indexOfFirst { it.customerId == SeedIds.CORNER_SHOP && YearMonth.from(it.issuedAt) == last.minusMonths(1) }
        if (voidAt >= 0) {
            val v = invoices[voidAt]
            invoices[voidAt] = v.copy(
                status = InvoiceStatus.VOID,
                voidReason = "Entered for the wrong customer",
                voidedBy = "Test Admin",
                voidedAt = v.issuedAt.plusDays(1),
            )
        }

        // --- shared materials and recipes (owner costing spec; the quantities are the owner's seed values) ---
        val kg = MaterialUnit("kg", 1_000_000)
        val g = MaterialUnit("g", 1_000)
        val litre = MaterialUnit("L", 1_000_000)
        val ml = MaterialUnit("ml", 1_000)
        val piece = MaterialUnit("piece", 1_000)
        val materials = listOf(
            Material(SeedIds.WHEAT, "Whole wheat flour", "g", "kg", false, listOf(g, kg)),
            Material(SeedIds.OIL, "Oil", "ml", "L", false, listOf(ml, litre)),
            Material(SeedIds.SUGAR, "Sugar", "g", "kg", false, listOf(g, kg)),
            Material(SeedIds.SALT, "Salt", "g", "kg", false, listOf(g, kg)),
            Material(SeedIds.BAKING_POWDER, "Baking powder", "g", "kg", false, listOf(g, kg)),
            Material(SeedIds.SORBATE, "Potassium sorbate", "g", "kg", false, listOf(g, kg)),
            Material(SeedIds.PACKING, "Packing", "piece", "piece", true, listOf(piece)),
        )
        // The recipe is per 1 kg of wheat (yield 32 chapathis per kg): wheat 1000 g, oil 80 ml, sugar 20 g, salt 15 g,
        // baking powder 2 g. Packing is one piece per packet whatever its size.
        val base = listOf(
            RecipeEntry(SeedIds.WHEAT, 1_000_000), RecipeEntry(SeedIds.OIL, 80_000), RecipeEntry(SeedIds.SUGAR, 20_000),
            RecipeEntry(SeedIds.SALT, 15_000), RecipeEntry(SeedIds.BAKING_POWDER, 2_000),
        )
        val packingLine = RecipeEntry(SeedIds.PACKING, 1_000)
        val recipes = mapOf(
            SeedIds.FRESH to base + packingLine,
            // Potassium sorbate applies to Mamre Chapathi only; its quantity is not decided yet (Doc 1 P-2).
            SeedIds.CHAPATHI to base + RecipeEntry(SeedIds.SORBATE, null) + packingLine,
        )

        // --- production damage: about 1.5% of the chapathis sold, one entry a month per product ---
        val damage = mutableListOf<DamageRow>()
        var month = first
        while (!month.isAfter(last)) {
            for (p in products) {
                val sold = invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }
                    .sumOf { inv -> inv.items.filter { it.productId == p.id }.sumOf { it.chapathis } }
                if (sold == 0) continue
                val date = minOf(month.atDay(10), today)
                damage += DamageRow("dm-%03d".format(damage.size + 1), date, p.id, maxOf(6, sold * 15 / 1000), "Burnt in the oven", "Test Admin")
            }
            month = month.plusMonths(1)
        }

        // --- stock before the first month, and one purchase of each priced material a month ---
        // Invented prices (Doc 1 s9.5 / P-3): wheat $1.00 per kg, $1.10 from the fourth month.
        val openingStock = mapOf(
            SeedIds.WHEAT to OpeningStock(100_000_000, 10_000), // 100 kg at $1.00
            SeedIds.OIL to OpeningStock(20_000_000, 8_000), // 20 L at $4.00
            SeedIds.SUGAR to OpeningStock(25_000_000, 2_500),
            SeedIds.SALT to OpeningStock(25_000_000, 2_000),
            SeedIds.BAKING_POWDER to OpeningStock(5_000_000, 2_000),
            SeedIds.PACKING to OpeningStock(1_000_000, 15_000), // 1,000 pieces at 15 cents
        )
        // material -> bag size in thousandths of the base unit, and price in cents per bag
        class Bag(val sizeMb: Long, val centsAt: (YearMonth) -> Long)
        val bags = mapOf(
            SeedIds.WHEAT to Bag(25_000_000) { m -> if (m.isBefore(first.plusMonths(3))) 2_500L else 2_750L }, // 25 kg bag
            SeedIds.OIL to Bag(20_000_000) { 8_000L }, // 20 L can at $4.00 per L
            SeedIds.SUGAR to Bag(25_000_000) { 2_500L },
            SeedIds.SALT to Bag(25_000_000) { 2_000L },
            SeedIds.BAKING_POWDER to Bag(5_000_000) { 2_000L },
            SeedIds.PACKING to Bag(1_000_000) { 15_000L }, // 1,000 pieces at 15 cents
        )
        val purchases = mutableListOf<Purchase>()
        month = first
        while (!month.isAfter(last)) {
            val made = mutableMapOf<String, Long>() // chapathis made per product
            val bagged = mutableMapOf<String, Long>() // packets that left in a bag per product
            invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }.flatMap { it.items }.forEach {
                made.merge(it.productId, it.chapathis.toLong(), Long::plus)
                bagged.merge(it.productId, it.qtyPackets.toLong(), Long::plus)
            }
            returns.filter { it.resolution == ReturnResolution.REPLACEMENT && YearMonth.from(it.date) == month }.forEach {
                made.merge(it.productId, it.qtyPackets.toLong() * it.chapathisPerPacket, Long::plus)
                bagged.merge(it.productId, it.qtyPackets.toLong(), Long::plus)
            }
            damage.filter { YearMonth.from(it.date) == month }.forEach { made.merge(it.productId, it.chapathis.toLong(), Long::plus) }
            for ((materialId, bag) in bags) {
                val m = materials.first { it.id == materialId }
                // Ingredients: chapathis x quantity per kg of wheat / yield; packing: one piece per packet bagged.
                val perPacket = products.sumOf { p ->
                    val q = recipes.getValue(p.id).firstOrNull { it.materialId == materialId }?.qtyMb ?: 0L
                    if (m.isPacking) q * (bagged[p.id] ?: 0L) else q * (made[p.id] ?: 0L) / p.yieldPerKg
                }
                val withWastage = if (m.isPacking) perPacket else perPacket * 102 / 100
                // Demo: sugar is bought short two months ago, so the negative stock warning has something to show.
                val bought = if (materialId == SeedIds.SUGAR && month == last.minusMonths(2)) withWastage * 80 / 100 else withWastage
                val bagCount = (bought + bag.sizeMb - 1) / bag.sizeMb
                if (bagCount == 0L) continue
                val date = minOf(month.atDay(2), today)
                purchases += Purchase(
                    "pu-%03d".format(purchases.size + 1), date, materialId, m.name, bagCount * bag.sizeMb,
                    bagCount * bag.centsAt(month), "Monthly purchase", "Test Admin",
                )
            }
            month = month.plusMonths(1)
        }

        // --- expenses ---
        val categories = listOf(
            ExpenseCategory(SeedIds.CAT_ELECTRICITY, "Electricity", ExpenseKind.INDIRECT),
            ExpenseCategory("x-water", "Water", ExpenseKind.INDIRECT),
            ExpenseCategory(SeedIds.CAT_MACHINE, "Machine", ExpenseKind.INDIRECT),
            ExpenseCategory(SeedIds.CAT_LABOUR, "Labour", ExpenseKind.INDIRECT),
            ExpenseCategory("x-maintenance", "Maintenance", ExpenseKind.INDIRECT),
            ExpenseCategory("x-delivery", "Delivery charge", ExpenseKind.INDIRECT),
            ExpenseCategory("x-fuel", "Fuel", ExpenseKind.INDIRECT),
            ExpenseCategory("x-other", "Other", ExpenseKind.INDIRECT),
        )
        val catName = categories.associate { it.id to it.name }
        val expenses = mutableListOf<Expense>()
        fun expense(cat: String, date: LocalDate, cents: Long, text: String) {
            if (date.isAfter(today)) return
            expenses += Expense("ex-%03d".format(expenses.size + 1), cat, catName.getValue(cat), ExpenseKind.INDIRECT, date, cents, text, "Test Admin")
        }
        month = first
        while (!month.isAfter(last)) {
            val m = month.monthValue
            expense(SeedIds.CAT_MACHINE, month.atDay(1), 25_000, "Machine lease")
            expense(SeedIds.CAT_ELECTRICITY, month.atDay(5), 15_000 + m * 300L, "Electricity bill")
            expense("x-water", month.atDay(6), 4_000 + m * 100L, "Water bill")
            expense(SeedIds.CAT_LABOUR, month.atDay(28), 40_000, "Helper wages")
            if (m % 2 == 0) expense("x-maintenance", month.atDay(12), 4_000, "Oven service")
            for (w in 0 until 4) {
                expense("x-delivery", month.atDay(2 + w * 7), 2_000, "Delivery charge")
                expense("x-fuel", month.atDay(4 + w * 7), 2_500 + (w % 2) * 300L, "Fuel")
            }
            expense("x-other", month.atDay(20), 2_500, "Packaging tape and labels")
            month = month.plusMonths(1)
        }

        return ServerState(
            types = types,
            products = products,
            customers = customers,
            invoices = invoices.toList(),
            payments = payments.toList(),
            paymentInvoiceIds = paymentInvoice.toMap(),
            returns = returns.toList(),
            categories = categories,
            expenses = expenses.toList(),
            materials = materials,
            recipes = recipes,
            purchases = purchases.toList(),
            damage = damage.toList(),
            openingStock = openingStock,
            wastageBp = DEFAULT_WASTAGE_BP,
            settings = BusinessSettings("Mamre Foods", "Address pending (Doc 1 P-6)", "Phone pending", "Thank you!"),
            overrideNotes = overrideNotes,
            workers = listOf(
                WorkerAccount("w-1", "Test Worker", "user1", "W1", true),
                WorkerAccount("w-2", "Second Worker", "user2", "W2", false),
            ),
            today = today,
        )
    }

    private fun newInvoice(
        seq: Int,
        customerId: String?,
        name: String,
        typeName: String,
        day: LocalDate,
        hour: Int,
        minute: Int,
        items: List<InvoiceItem>,
    ) = AdminInvoice(
        id = "inv-%04d".format(seq),
        number = "MAM-W1-%04d".format(seq),
        customerId = customerId,
        customerName = name,
        typeName = typeName,
        deviceCode = "W1",
        issuedAt = LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)),
        items = items,
        totalCents = items.sumOf { it.lineTotalCents },
        status = InvoiceStatus.ACTIVE,
    )
}
