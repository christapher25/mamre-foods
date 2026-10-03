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
import com.mamre.billing.domain.admin.Ingredient
import com.mamre.billing.domain.admin.IngredientPrice
import com.mamre.billing.domain.admin.InvoiceItem
import com.mamre.billing.domain.admin.OverridePrice
import com.mamre.billing.domain.admin.PriceEntry
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.domain.admin.WorkerAccount
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.InvoiceStatus
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
    const val SPICE_GARDEN = "c-spice-garden"
    const val PATEL_MART = "c-patel-mart"
    const val CORNER_SHOP = "c-corner-shop"
    const val CAT_ELECTRICITY = "x-electricity"
    const val CAT_MACHINE = "x-machine"
    const val CAT_LABOUR = "x-labour"
}

private const val UNIT_PACKET = 12
private const val YIELD_PER_KG = 32 // chapathis per kg of wheat (Doc 1 s9.2)
private const val PACKING_CENTS = 15L
private const val LAUNCH_AFTER_MONTHS = 2L // Mamre Chapathi is sold from the third month of the demo data

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
 * returns, production batches and expenses that obey the ledger rules of Doc 1 s6 and s11.
 * No random numbers: the same day always gives the same data, so tests can pin it.
 *
 * Demo choices (not in the docs): Mamre Chapathi is sold from the third month, so the first two
 * months have complete costs and the later ones show INCOMPLETE (potassium sorbate quantity,
 * Doc 1 P-2); the ingredient prices are the invented ones of Doc 1 s9.5 (P-3 is pending).
 */
object AdminSeed {
    private val customerSeeds = listOf(
        CustomerSeed(SeedIds.SPICE_GARDEN, "Spice Garden", SeedIds.RESTAURANT, PaymentMode.CREDIT, 80, 3, 0),
        CustomerSeed("c-curry-house", "Curry House", SeedIds.RESTAURANT, PaymentMode.CREDIT, 64, 3, 1, opening = 15_000, payPercent = 70),
        CustomerSeed("c-taj-kitchen", "Taj Kitchen", SeedIds.RESTAURANT, PaymentMode.CREDIT, 72, 4, 2, stoppedPayingDaysAgo = 45),
        CustomerSeed("c-masala-bistro", "Masala Bistro", SeedIds.RESTAURANT, PaymentMode.CASH, 48, 4, 3),
        CustomerSeed(SeedIds.PATEL_MART, "Patel Mart", SeedIds.SHOP, PaymentMode.CREDIT, 48, 5, 0, payPercent = 80),
        CustomerSeed(SeedIds.CORNER_SHOP, "Corner Shop", SeedIds.SHOP, PaymentMode.CREDIT, 36, 5, 2, stoppedPayingDaysAgo = 120),
        CustomerSeed("c-desi-grocers", "Desi Grocers", SeedIds.SHOP, PaymentMode.CASH, 40, 6, 1),
        CustomerSeed("c-rao-family", "Rao Family", SeedIds.RETAIL, PaymentMode.CASH, 12, 9, 4),
        CustomerSeed("c-sharma-family", "Sharma Family", SeedIds.RETAIL, PaymentMode.CREDIT, 16, 10, 5, payPercent = 40),
    )

    fun build(today: LocalDate, prices: SharedPriceTable = SharedPriceTable.seeded(today)): ServerState {
        val last = YearMonth.from(today)
        val first = last.minusMonths(6)
        val start = first.atDay(1)
        val launch = first.plusMonths(LAUNCH_AFTER_MONTHS).atDay(1)

        val types = listOf(
            AdminCustomerType(SeedIds.RESTAURANT, "Restaurant"),
            AdminCustomerType(SeedIds.SHOP, "Shop"),
            AdminCustomerType(SeedIds.RETAIL, "Retail"),
        )
        val typeName = types.associate { it.id to it.name }
        val products = listOf(
            AdminProduct(SeedIds.FRESH, "FRESH", "Mamre Fresh Chapathi", UNIT_PACKET, PACKING_CENTS),
            AdminProduct(SeedIds.CHAPATHI, "CHAPATHI", "Mamre Chapathi", UNIT_PACKET, PACKING_CENTS),
        )
        val productName = products.associate { it.id to it.name }
        val customers = customerSeeds.map {
            AdminCustomer(
                id = it.id, name = it.name, typeId = it.typeId, typeName = typeName.getValue(it.typeId),
                phone = "", address = "", paymentMode = it.mode, notes = "", isActive = true,
                openingBalanceCents = it.opening,
            )
        }

        val priceEntries = prices.all().map { PriceEntry(it.id, it.productId, it.customerTypeId, it.unitPriceCents, it.effectiveFrom) }
        val overrides = listOf(
            OverridePrice("po-1", SeedIds.SPICE_GARDEN, SeedIds.CHAPATHI, 240, launch, true, "Volume customer"),
            OverridePrice("po-2", SeedIds.PATEL_MART, SeedIds.FRESH, 285, start, true, "Agreed at signup"),
        )

        fun unitPrice(c: CustomerSeed?, productId: String, typeId: String, date: LocalDate): Long? {
            val o = overrides.filter {
                c != null && it.customerId == c.id && it.productId == productId && it.isActive && !it.effectiveFrom.isAfter(date)
            }.maxByOrNull { it.effectiveFrom }
            if (o != null) return o.unitPriceCents
            return priceEntries
                .filter { it.productId == productId && it.typeId == typeId && !it.effectiveFrom.isAfter(date) }
                .maxByOrNull { it.effectiveFrom }?.unitPriceCents
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
                    fun line(productId: String, qty: Int) {
                        val price = unitPrice(c, productId, c.typeId, day) ?: return
                        lines += InvoiceItem(productId, productName.getValue(productId), qty, price, qty * price)
                    }
                    line(SeedIds.FRESH, c.baseQty + (dayIndex * 7 + ci * 5) % (c.baseQty / 2 + 1))
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
                            l.productId, l.productName, packets, reasons[invoiceSeq % reasons.size], resolution,
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
                val inv = newInvoice(
                    invoiceSeq, null, "Walk-in", typeName.getValue(SeedIds.RETAIL), day, 13, 0,
                    listOf(InvoiceItem(SeedIds.FRESH, productName.getValue(SeedIds.FRESH), qty, price, qty * price)),
                )
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

        // --- ingredients and recipes (Doc 1 s9.2; prices are the invented ones of s9.5) ---
        fun ingredient(id: String, name: String, base: String, purchase: String, vararg prices: Pair<Long, LocalDate>) =
            Ingredient(id, name, base, purchase, 1000, prices.mapIndexed { i, (c, d) -> IngredientPrice("ip-$id-$i", c, d) })
        val ingredients = listOf(
            ingredient(SeedIds.WHEAT, "Whole wheat flour", "g", "kg", 100L to start, 110L to first.plusMonths(3).atDay(1)),
            ingredient(SeedIds.OIL, "Oil", "ml", "L", 400L to start),
            ingredient(SeedIds.SUGAR, "Sugar", "g", "kg", 100L to start),
            ingredient(SeedIds.SALT, "Salt", "g", "kg", 80L to start),
            ingredient(SeedIds.BAKING_POWDER, "Baking powder", "g", "kg", 400L to start),
            ingredient(SeedIds.SORBATE, "Potassium sorbate", "g", "kg"), // no price and no quantity yet (P-2, P-3)
        )
        val base = listOf(
            RecipeLine(SeedIds.WHEAT, 1_000_000), RecipeLine(SeedIds.OIL, 80_000), RecipeLine(SeedIds.SUGAR, 20_000),
            RecipeLine(SeedIds.SALT, 15_000), RecipeLine(SeedIds.BAKING_POWDER, 2_000),
        )
        val recipes = mapOf(
            SeedIds.FRESH to base,
            SeedIds.CHAPATHI to base + RecipeLine(SeedIds.SORBATE, null),
        )

        // --- production batches: about 3% more than was sold, four a month, ~2% damaged ---
        val batches = mutableListOf<BatchRow>()
        var month = first
        while (!month.isAfter(last)) {
            val monthEnd = if (month == last) today else month.atEndOfMonth()
            for (p in products) {
                if (p.id == SeedIds.CHAPATHI && month.atEndOfMonth().isBefore(launch)) continue
                val sold = invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }
                    .sumOf { inv -> inv.items.filter { it.productId == p.id }.sumOf { it.qtyPackets } }
                if (sold == 0) continue
                val count = if (month == last) 1 else 4
                val perBatch = (sold * 103 / 100 + count - 1) / count
                val kg = (perBatch * UNIT_PACKET + YIELD_PER_KG - 1) / YIELD_PER_KG
                val packed = kg * YIELD_PER_KG / UNIT_PACKET
                for (i in 0 until count) {
                    val date = minOf(month.atDay(3 + i * 7), monthEnd)
                    batches += BatchRow("b-${p.id}-$month-$i", date, p.id, kg, packed, maxOf(1, packed * 22 / 1000))
                }
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
            batches = batches.toList(),
            categories = categories,
            expenses = expenses.toList(),
            overrides = overrides,
            ingredients = ingredients,
            recipes = recipes,
            settings = BusinessSettings("Mamre Foods", "Address pending (Doc 1 P-6)", "Phone pending", "Thank you!"),
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
