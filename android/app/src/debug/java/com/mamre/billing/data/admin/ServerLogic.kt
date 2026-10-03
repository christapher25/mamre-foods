package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.AdminReturn
import com.mamre.billing.domain.admin.AppliedPayment
import com.mamre.billing.domain.admin.BalanceRow
import com.mamre.billing.domain.admin.CategoryTotal
import com.mamre.billing.domain.admin.CostLine
import com.mamre.billing.domain.admin.CostingReport
import com.mamre.billing.domain.admin.CustomerMonthSummary
import com.mamre.billing.domain.admin.DashboardReport
import com.mamre.billing.domain.admin.ExpenseKind
import com.mamre.billing.domain.admin.ExpensesReport
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.InvoiceCredit
import com.mamre.billing.domain.admin.InvoiceDetail
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.MonthSales
import com.mamre.billing.domain.admin.NamedAmount
import com.mamre.billing.domain.admin.ProductCost
import com.mamre.billing.domain.admin.ProductionDamage
import com.mamre.billing.domain.admin.ReturnsReport
import com.mamre.billing.domain.admin.StockReport
import com.mamre.billing.domain.admin.StockRow
import com.mamre.billing.domain.admin.monthWindow
import com.mamre.billing.domain.worker.CreditEntry
import com.mamre.billing.domain.worker.InvoiceEntry
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentEntry
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.domain.worker.broughtForward
import com.mamre.billing.domain.worker.ledgerBalance
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * THE SERVER STAND-IN. Doc 2 s1.1 makes the server the source of truth for costing and reports, and
 * the device never recomputes them. These functions exist only so [FakeAdminApi] can give the Admin
 * screens figures that agree with each other; a real server replaces them with API calls, and the
 * release build, which has no fake, does not contain them. Nothing in domain/admin or ui/admin
 * computes cost, usage, profit or balances.
 *
 * Money is Long cents. Cost per packet is a Long in ten-thousandths of a dollar. Material quantities
 * are Long thousandths of the base unit ("mb"). All integer arithmetic, rounding half up.
 *
 * Direct expense (owner change, supersedes Doc 1 A-11): a material's price in a month is the weighted
 * average of opening stock and that month's purchases; usage is (gross invoiced packets + replacement
 * packets + production-damaged packets) x recipe quantity x (1 + wastage), except packing, which has no
 * wastage and is counted for invoiced + replacement packets only (damaged packets are spoiled before packing); direct expense is the cost consumed of every material, packing included.
 */
object ServerLogic {
    private const val TT_PER_CENT = 100L
    private const val DAYS_30 = 30L
    private const val DAYS_60 = 60L
    private const val BP = 10_000L
    private const val SORBATE_NOTE = " (Doc 1 P-2)"

    /** a / b rounded half up, for non-negative a and positive b. */
    fun divHalfUp(a: Long, b: Long): Long = (a * 2 + b) / (b * 2)

    /** a / b rounded half away from zero, for any sign of a. */
    private fun divRound(a: Long, b: Long): Long = if (a < 0) -divHalfUp(-a, b) else divHalfUp(a, b)

    // ------------------------------------------------------------------ ledger and allocation

    fun ledger(s: ServerState, customerId: String): List<LedgerEntry> = buildList {
        s.invoices.filter { it.customerId == customerId }
            .forEach { add(InvoiceEntry(it.issuedAt.toLocalDate(), it.totalCents, it.isVoid)) }
        s.payments.filter { it.customerId == customerId }
            .forEach { add(PaymentEntry(it.date, it.amountCents, it.method)) }
        s.returns.filter { it.customerId == customerId && it.resolution == ReturnResolution.CREDIT }
            .forEach { add(CreditEntry(it.date, it.creditCents)) }
    }

    fun opening(s: ServerState, customerId: String): Long =
        s.customers.firstOrNull { it.id == customerId }?.openingBalanceCents ?: 0L

    /** Balance = opening + non-void invoices - credits - payments up to [end] (Doc 1 s6.3). */
    fun balance(s: ServerState, customerId: String, end: LocalDate? = null): Long {
        val entries = ledger(s, customerId).let { all -> if (end == null) all else all.filter { !it.date.isAfter(end) } }
        return ledgerBalance(opening(s, customerId), entries)
    }

    /** How the server applied one customer's payments: oldest invoice first (Doc 1 s6.2). */
    data class Allocation(
        val dueByInvoice: Map<String, Long>,
        val appliedByInvoice: Map<String, List<AppliedPayment>>,
        val openingDueCents: Long,
        val creditOnAccountCents: Long,
        val dateByInvoice: Map<String, LocalDate>,
    )

    fun allocate(s: ServerState, customerId: String): Allocation {
        val invoices = s.invoices.filter { it.customerId == customerId && !it.isVoid }.sortedBy { it.issuedAt }
        // A Credit return linked to an invoice lowers what is owed on that invoice.
        val linkedCredit = s.returns.filter { it.customerId == customerId && it.resolution == ReturnResolution.CREDIT && it.invoiceId != null }
            .groupBy { it.invoiceId!! }.mapValues { (_, v) -> v.sumOf { it.creditCents } }
        val unlinkedCredit = s.returns.filter { it.customerId == customerId && it.resolution == ReturnResolution.CREDIT && it.invoiceId == null }
        val due = linkedMapOf<String, Long>()
        val dates = mutableMapOf<String, LocalDate>()
        val opening = opening(s, customerId)
        var openingDue = maxOf(opening, 0L)
        invoices.forEach {
            due[it.id] = maxOf(it.totalCents - (linkedCredit[it.id] ?: 0L), 0L)
            dates[it.id] = it.issuedAt.toLocalDate()
        }
        val applied = mutableMapOf<String, MutableList<AppliedPayment>>()
        var surplus = if (opening < 0) -opening else 0L

        data class Pool(val receipt: String, val date: LocalDate, val method: PaymentMethod, val cents: Long, val invoiceId: String?)
        val pool = (
            s.payments.filter { it.customerId == customerId }.map { Pool(it.receiptNumber, it.date, it.method, it.amountCents, s.paymentInvoiceIds[it.id]) } +
                unlinkedCredit.map { Pool("Credit", it.date, PaymentMethod.OTHER, it.creditCents, null) }
            ).sortedBy { it.date }

        for (p in pool) {
            var left = p.cents
            fun take(invoiceId: String) {
                val owed = due[invoiceId] ?: return
                val use = minOf(left, owed)
                if (use > 0) {
                    due[invoiceId] = owed - use
                    applied.getOrPut(invoiceId) { mutableListOf() } += AppliedPayment(p.receipt, p.date, p.method, use)
                    left -= use
                }
            }
            p.invoiceId?.let { take(it) }
            // Opening balance is the oldest thing owed, then the invoices by date.
            if (left > 0 && openingDue > 0) {
                val use = minOf(left, openingDue)
                openingDue -= use
                left -= use
            }
            for (inv in invoices) if (left > 0) take(inv.id)
            surplus += left
        }
        return Allocation(due, applied, openingDue, surplus, dates)
    }

    fun invoiceDetail(s: ServerState, id: String): InvoiceDetail? {
        val inv = s.invoices.firstOrNull { it.id == id } ?: return null
        val credits = s.returns.filter { it.invoiceId == inv.id && it.resolution == ReturnResolution.CREDIT }
            .map { InvoiceCredit(it.date, it.productName, it.qtyPackets, it.creditCents, it.reason) }
        if (inv.customerId == null) {
            // Walk-in: paid in full at the sale, no ledger.
            val paid = s.payments.filter { s.paymentInvoiceIds[it.id] == inv.id }
                .map { AppliedPayment(it.receiptNumber, it.date, it.method, it.amountCents) }
            return InvoiceDetail(inv, paid, credits, if (inv.isVoid) 0L else maxOf(inv.totalCents - paid.sumOf { it.amountCents }, 0L))
        }
        if (inv.isVoid) {
            return InvoiceDetail(inv, s.payments.filter { s.paymentInvoiceIds[it.id] == inv.id }
                .map { AppliedPayment(it.receiptNumber, it.date, it.method, it.amountCents) }, credits, 0L)
        }
        val a = allocate(s, inv.customerId)
        return InvoiceDetail(inv, a.appliedByInvoice[inv.id].orEmpty(), credits, a.dueByInvoice[inv.id] ?: 0L)
    }

    fun customerSummary(s: ServerState, customerId: String, month: YearMonth): CustomerMonthSummary {
        val entries = ledger(s, customerId)
        val opening = broughtForward(opening(s, customerId), entries, month)
        val inMonth = entries.filter { YearMonth.from(it.date) == month }
        val closing = ledgerBalance(opening, inMonth)
        val payments = s.payments.filter { it.customerId == customerId && YearMonth.from(it.date) == month }.sortedBy { it.date }
        return CustomerMonthSummary(
            customerId = customerId,
            month = month,
            openingCents = opening,
            invoicedCents = inMonth.filterIsInstance<InvoiceEntry>().filter { !it.isVoid }.sumOf { it.totalCents },
            creditsCents = inMonth.filterIsInstance<CreditEntry>().sumOf { it.creditCents },
            payments = payments,
            closingCents = closing,
        )
    }

    fun balances(s: ServerState): List<BalanceRow> = s.customers.map { c ->
        val bal = balance(s, c.id)
        val a = allocate(s, c.id)
        var current = 0L
        var over30 = 0L
        var over60 = a.openingDueCents // the opening balance is older than any invoice
        a.dueByInvoice.forEach { (id, owed) ->
            val age = ChronoUnit.DAYS.between(a.dateByInvoice.getValue(id), s.today)
            when {
                age >= DAYS_60 -> over60 += owed
                age >= DAYS_30 -> over30 += owed
                else -> current += owed
            }
        }
        BalanceRow(c.id, c.label, c.typeName, bal, current, over30, over60)
    }.sortedByDescending { it.balanceCents }

    // ------------------------------------------------------------------ packets and chapathis

    /** Packets invoiced (gross, void excluded) in the month, per product. Packing uses these. */
    fun invoicedPackets(s: ServerState, month: YearMonth): Map<String, Long> =
        s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }
            .flatMap { it.items }.groupBy { it.productId }.mapValues { (_, v) -> v.sumOf { it.qtyPackets.toLong() } }

    /** Chapathis invoiced (gross, void excluded) in the month, per product: packets x chapathis per packet. */
    fun invoicedChapathis(s: ServerState, month: YearMonth): Map<String, Long> =
        s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }
            .flatMap { it.items }.groupBy { it.productId }.mapValues { (_, v) -> v.sumOf { it.chapathis.toLong() } }

    private fun replacementRows(s: ServerState, month: YearMonth) =
        s.returns.filter { it.resolution == ReturnResolution.REPLACEMENT && YearMonth.from(it.date) == month }

    private fun replacementPackets(s: ServerState, month: YearMonth): Map<String, Long> =
        replacementRows(s, month).groupBy { it.productId }.mapValues { (_, v) -> v.sumOf { it.qtyPackets.toLong() } }

    private fun replacementChapathis(s: ServerState, month: YearMonth): Map<String, Long> =
        replacementRows(s, month).groupBy { it.productId }.mapValues { (_, v) -> v.sumOf { it.qtyPackets.toLong() * it.chapathisPerPacket } }

    private fun damagedChapathis(s: ServerState, month: YearMonth): Map<String, Long> =
        s.damage.filter { YearMonth.from(it.date) == month }
            .groupBy { it.productId }.mapValues { (_, v) -> v.sumOf { it.chapathis.toLong() } }

    /** Packets that left the business in a bag: invoiced + replacement. Damaged chapathis are spoiled before packing. */
    fun packetsBagged(s: ServerState, month: YearMonth): Map<String, Long> {
        val a = invoicedPackets(s, month)
        val b = replacementPackets(s, month)
        return (a.keys + b.keys).associateWith { (a[it] ?: 0L) + (b[it] ?: 0L) }
    }

    /** Everything made in the month per product, in chapathis: invoiced + replacement + damaged. */
    fun chapathisMade(s: ServerState, month: YearMonth): Map<String, Long> {
        val a = invoicedChapathis(s, month)
        val b = replacementChapathis(s, month)
        val c = damagedChapathis(s, month)
        return (a.keys + b.keys + c.keys).associateWith { (a[it] ?: 0L) + (b[it] ?: 0L) + (c[it] ?: 0L) }
    }

    /**
     * Net chapathis sold: invoiced less credited by returns (Doc 1 s9.4, A-9). Used for the indirect share only;
     * packets of different sizes are not comparable, so the share is spread over chapathis.
     */
    fun netChapathis(s: ServerState, productId: String?, month: YearMonth): Long {
        val sold = s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }
            .sumOf { inv -> inv.items.filter { productId == null || it.productId == productId }.sumOf { it.chapathis.toLong() } }
        val credited = s.returns.filter { it.resolution == ReturnResolution.CREDIT && YearMonth.from(it.date) == month && (productId == null || it.productId == productId) }
            .sumOf { it.qtyPackets.toLong() * it.chapathisPerPacket }
        return sold - credited
    }

    fun indirectTotalCents(s: ServerState, month: YearMonth): Long =
        s.expenses.filter { it.kind == ExpenseKind.INDIRECT && YearMonth.from(it.date) == month }.sumOf { it.amountCents }

    // ------------------------------------------------------------------ materials: usage, stock, price

    private data class Price(val valueCents: Long, val qtyMb: Long)

    /** One material for one month, before it is turned into display figures. A null means unknown. */
    private class MonthMaterial(
        val material: Material,
        val openingQty: Long?,
        val openingValue: Long?,
        val boughtQty: Long,
        val boughtCents: Long,
        val used: Long?,
        val usedMissing: List<String>,
        val price: Price?,
        val cost: Long?,
        val costMissing: List<String>,
        val closingQty: Long?,
        val closingValue: Long?,
    )

    private fun missingQuantity(material: Material, product: String?): String {
        val note = if (material.id == SeedIds.SORBATE) SORBATE_NOTE else ""
        return if (product == null) "${material.name} quantity per kg of wheat$note" else "${material.name} quantity per kg of wheat for $product$note"
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /**
     * Usage of one material in a month in thousandths of its base unit, or what is missing to know it.
     * Ingredients: TOTAL chapathis x quantity per kg of wheat / yield, over all products in ONE step, rounded half
     * up in milli-units, then x (1 + wastage). Packing: one piece per packet bagged, no yield and no wastage.
     */
    private fun usage(s: ServerState, material: Material, madeChapathis: Map<String, Long>, baggedPackets: Map<String, Long>): Pair<Long?, List<String>> {
        val missing = mutableListOf<String>()
        if (material.isPacking) {
            var sum = 0L
            for (p in s.products) {
                val line = s.recipes[p.id]?.firstOrNull { it.materialId == material.id } ?: continue
                val bagged = baggedPackets[p.id] ?: 0L
                if (bagged == 0L) continue
                val q = line.qtyMb
                if (q == null) missing += missingQuantity(material, p.name) else sum += bagged * q
            }
            return if (missing.isNotEmpty()) null to missing else sum to emptyList()
        }
        val used = s.products.filter { (madeChapathis[it.id] ?: 0L) > 0 && s.recipes[it.id]?.any { r -> r.materialId == material.id } == true }
        var common = 1L
        for (p in used) common = common / gcd(common, p.yieldPerKg.toLong()) * p.yieldPerKg
        var numerator = 0L
        for (p in used) {
            val q = s.recipes.getValue(p.id).first { it.materialId == material.id }.qtyMb
            if (q == null) missing += missingQuantity(material, p.name) else numerator += (madeChapathis[p.id] ?: 0L) * q * (common / p.yieldPerKg)
        }
        if (missing.isNotEmpty()) return null to missing
        // One rounding step: chapathis x quantity / yield x (1 + wastage), half up (wastage applies to ingredients only).
        return divHalfUp(numerator * (BP + s.wastageBp), common * BP) to emptyList()
    }

    private fun chainStart(s: ServerState): YearMonth {
        val months = s.invoices.map { YearMonth.from(it.issuedAt) } + s.purchases.map { YearMonth.from(it.date) } +
            s.damage.map { YearMonth.from(it.date) } + s.returns.map { YearMonth.from(it.date) }
        return months.minOrNull() ?: YearMonth.from(s.today)
    }

    private data class Carry(val qty: Long?, val value: Long?, val price: Price?)

    /** Walks the months from the first one of data to [target], carrying stock and value forward. */
    private fun materialsFor(s: ServerState, target: YearMonth): List<MonthMaterial> {
        val carry = s.materials.associate { m ->
            val o = s.openingStock[m.id]
            m.id to Carry(o?.qtyMb ?: 0L, o?.valueCents ?: 0L, null)
        }.toMutableMap()
        var month = minOf(chainStart(s), target)
        while (true) {
            val packets = chapathisMade(s, month)
            val bagged = packetsBagged(s, month)
            val results = s.materials.map { m -> monthOf(s, m, month, packets, bagged, carry.getValue(m.id)) }
            if (month == target) return results
            results.forEach { r -> carry[r.material.id] = Carry(r.closingQty, r.closingValue, r.price) }
            month = month.plusMonths(1)
        }
    }

    private fun monthOf(s: ServerState, m: Material, month: YearMonth, packets: Map<String, Long>, bagged: Map<String, Long>, c: Carry): MonthMaterial {
        val bought = s.purchases.filter { it.materialId == m.id && YearMonth.from(it.date) == month }
        val bQty = bought.sumOf { it.qtyMb }
        val bCents = bought.sumOf { it.totalCents }
        val (used, usedMissing) = usage(s, m, packets, bagged)
        val purchaseOnly = if (bQty > 0) Price(bCents, bQty) else null

        // A shortage (stock at or below zero) whose value is unknown is valued at this month's purchase price.
        var openingValue = c.value
        if (c.qty != null && openingValue == null && c.qty <= 0) {
            openingValue = (purchaseOnly ?: c.price)?.let { divRound(c.qty * it.valueCents, it.qtyMb) }
        }
        val openKnown = c.qty != null && openingValue != null
        val totalQty = if (openKnown) c.qty!! + bQty else null
        val totalValue = if (openKnown) openingValue!! + bCents else null
        val price = when {
            openKnown && totalQty!! > 0 && totalValue!! > 0 -> Price(totalValue, totalQty)
            purchaseOnly != null -> purchaseOnly
            else -> c.price
        }
        val costMissing = mutableListOf<String>()
        val cost: Long? = when {
            used == null -> { costMissing += usedMissing; null }
            !openKnown -> { costMissing += "${m.name} stock (an earlier month is incomplete)"; null }
            used == 0L -> 0L
            price == null -> { costMissing += "${m.name} price"; null }
            else -> divHalfUp(used * price.valueCents, price.qtyMb)
        }
        val closingQty = if (openKnown && used != null) totalQty!! - used else null
        val closingValue = if (cost != null && totalValue != null) totalValue - cost else null
        return MonthMaterial(m, c.qty, openingValue, bQty, bCents, used, usedMissing, price, cost, costMissing, closingQty, closingValue)
    }

    private fun figure(value: Long?, missing: List<String>): Figure =
        if (value != null) Figure.Known(value) else Figure.Incomplete(missing.distinct())

    /** The month's materials: opening stock, bought, used, closing stock, average price, cost consumed (B6). */
    fun stock(s: ServerState, month: YearMonth): StockReport {
        val rows = materialsFor(s, month).map { r ->
            val m = r.material
            val openMissing = listOf("${m.name} stock (an earlier month is incomplete)")
            StockRow(
                material = m,
                openingQtyMb = figure(r.openingQty, openMissing),
                openingValueCents = figure(r.openingValue, openMissing),
                boughtQtyMb = r.boughtQty,
                boughtCents = r.boughtCents,
                usedMb = figure(r.used, r.usedMissing),
                closingQtyMb = figure(r.closingQty, r.usedMissing.ifEmpty { openMissing }),
                closingValueCents = figure(r.closingValue, r.costMissing),
                avgPriceTt = figure(
                    r.price?.let { divHalfUp(it.valueCents * TT_PER_CENT * m.purchaseMbPerUnit, it.qtyMb) },
                    listOf("${m.name} price"),
                ),
                costConsumedCents = figure(r.cost, r.costMissing),
            )
        }
        fun total(pick: (StockRow) -> Figure): Figure {
            val missing = rows.mapNotNull { (pick(it) as? Figure.Incomplete)?.missing }.flatten()
            return if (missing.isEmpty()) Figure.Known(rows.sumOf { (pick(it) as Figure.Known).value }) else Figure.Incomplete(missing.distinct())
        }
        return StockReport(
            month = month,
            rows = rows,
            boughtTotalCents = rows.sumOf { it.boughtCents },
            openingValueTotal = total { it.openingValueCents },
            costConsumedTotal = total { it.costConsumedCents },
            closingValueTotal = total { it.closingValueCents },
        )
    }

    /** Month direct expense: the cost of all the materials consumed, packing included. */
    fun directExpense(s: ServerState, month: YearMonth): Figure = stock(s, month).costConsumedTotal

    // ------------------------------------------------------------------ costing per chapathi and per packet

    /** a/b summed exactly over several fractions, then rounded half up once (BigInteger: no precision lost). */
    private fun exactRounded(parts: List<Pair<BigInteger, BigInteger>>): Long {
        var n = BigInteger.ZERO
        var d = BigInteger.ONE
        for ((a, b) in parts) {
            n = n.multiply(b).add(a.multiply(d))
            d = d.multiply(b)
            val g = n.gcd(d)
            if (g.signum() > 0 && g != BigInteger.ONE) {
                n = n.divide(g)
                d = d.divide(g)
            }
        }
        return n.multiply(BigInteger.TWO).add(d).divide(d.multiply(BigInteger.TWO)).toLong()
    }

    /** One chapathis-sized piece of the month's weighted average prices, for building costs. */
    private fun costFor(
        s: ServerState,
        product: com.mamre.billing.domain.admin.AdminProduct,
        monthMaterials: Map<String, MonthMaterial>,
        chapathis: Int,
        indirectCents: Long,
        netChapathis: Long,
    ): ProductCost {
        val missing = mutableListOf<String>()
        val lines = mutableListOf<CostLine>()
        val parts = mutableListOf<Pair<BigInteger, BigInteger>>()
        val partsOne = mutableListOf<Pair<BigInteger, BigInteger>>()
        var packing = 0L
        for (entry in s.recipes[product.id].orEmpty()) {
            val mm = monthMaterials.getValue(entry.materialId)
            val q = entry.qtyMb
            val price = mm.price
            if (q == null) missing += missingQuantity(mm.material, null)
            if (price == null) missing += "${mm.material.name} price"
            if (q == null || price == null) continue
            if (mm.material.isPacking) {
                packing += divHalfUp(q * price.valueCents * TT_PER_CENT, price.qtyMb) // one piece per packet
            } else {
                val perUnitDen = BigInteger.valueOf(price.qtyMb).multiply(BigInteger.valueOf(product.yieldPerKg.toLong()))
                val perUnitNum = BigInteger.valueOf(q).multiply(BigInteger.valueOf(price.valueCents)).multiply(BigInteger.valueOf(TT_PER_CENT))
                parts += perUnitNum.multiply(BigInteger.valueOf(chapathis.toLong())) to perUnitDen
                partsOne += perUnitNum to perUnitDen
                lines += CostLine(mm.material.id, mm.material.name, divHalfUp(q * price.valueCents * TT_PER_CENT * chapathis, price.qtyMb * product.yieldPerKg))
            }
        }
        if (netChapathis <= 0) missing += "Chapathis sold this month"
        if (missing.isNotEmpty()) return ProductCost.Incomplete(product.id, product.name, missing.distinct())
        val ingredients = exactRounded(parts)
        val wastage = divHalfUp(ingredients * s.wastageBp, BP) // ingredients only, not packing
        val direct = ingredients + wastage + packing
        val indirect = divHalfUp(indirectCents * TT_PER_CENT * chapathis, netChapathis)
        val one = exactRounded(partsOne)
        return ProductCost.Complete(
            product.id, product.name, chapathis, lines, ingredients, wastage, packing, direct, indirect, direct + indirect,
            perChapathiTt = one + divHalfUp(one * s.wastageBp, BP),
        )
    }

    /** Cost per standard packet for every product (a custom packet is asked for with [packetCost]). */
    fun costing(s: ServerState, month: YearMonth): CostingReport {
        val monthMaterials = materialsFor(s, month).associateBy { it.material.id }
        val net = netChapathis(s, null, month)
        val indirectCents = indirectTotalCents(s, month)
        val products = s.products.map { costFor(s, it, monthMaterials, it.unitsPerPacket, indirectCents, net) }
        return CostingReport(month, s.wastageBp, products, indirectCents, net)
    }

    /** Cost of a packet of [chapathis]: per chapathi x N plus one packing piece (change set C2). */
    fun packetCost(s: ServerState, month: YearMonth, productId: String, chapathis: Int): ProductCost {
        val product = s.products.first { it.id == productId }
        val monthMaterials = materialsFor(s, month).associateBy { it.material.id }
        return costFor(s, product, monthMaterials, chapathis, indirectTotalCents(s, month), netChapathis(s, null, month))
    }

    // ------------------------------------------------------------------ reports

    fun netSalesCents(s: ServerState, month: YearMonth): Long =
        s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }.sumOf { it.totalCents } -
            s.returns.filter { YearMonth.from(it.date) == month }.sumOf { it.creditCents }

    /**
     * One month at a glance. Gross profit = net sales - direct expense; net profit = gross profit -
     * indirect expenses (owner change: damage, wastage and replacements are already inside direct expense).
     * A month whose direct expense is incomplete has no gross or net profit, never a partial number.
     */
    fun dashboard(s: ServerState, month: YearMonth, earliest: YearMonth): DashboardReport {
        val netSales = netSalesCents(s, month)
        val end = month.atEndOfMonth()
        val cash = s.payments.filter { YearMonth.from(it.date) == month }.sumOf { it.amountCents }
        // Outstanding: what customers still owe at month end (credit on account is not a receivable).
        val outstanding = s.customers.sumOf { maxOf(balance(s, it.id, end), 0L) }
        val direct = directExpense(s, month)
        val indirect = indirectTotalCents(s, month)
        val gross: Figure = if (direct is Figure.Known) Figure.Known(netSales - direct.value) else direct
        val net: Figure = if (gross is Figure.Known) Figure.Known(gross.value - indirect) else gross
        val byProduct = s.products.map { p ->
            val revenue = s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }
                .sumOf { inv -> inv.items.filter { it.productId == p.id }.sumOf { it.lineTotalCents } }
            val credits = s.returns.filter { it.productId == p.id && YearMonth.from(it.date) == month }.sumOf { it.creditCents }
            NamedAmount(p.name, revenue - credits)
        }
        val byType = s.types.map { t ->
            val revenue = s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month && it.typeName == t.name }.sumOf { it.totalCents }
            val credits = s.returns.filter { it.typeName == t.name && YearMonth.from(it.date) == month }.sumOf { it.creditCents }
            NamedAmount(t.name, revenue - credits)
        }
        val packetsSold = invoicedPackets(s, month).values.sum().toInt()
        val chapathisSold = invoicedChapathis(s, month).values.sum()
        return DashboardReport(
            month = month,
            inProgress = month == YearMonth.from(s.today),
            netSalesCents = netSales,
            packetsSold = packetsSold,
            chapathisSold = chapathisSold,
            cashCollectedCents = cash,
            outstandingCents = outstanding,
            directCost = direct,
            grossProfit = gross,
            indirectExpensesCents = indirect,
            netProfit = net,
            sixMonthSales = monthWindow(month, 6, earliest).map { MonthSales(it, netSalesCents(s, it)) },
            salesByProduct = byProduct,
            salesByCustomerType = byType,
            missingInputs = (direct as? Figure.Incomplete)?.missing.orEmpty(),
        )
    }

    fun expensesReport(s: ServerState, month: YearMonth): ExpensesReport {
        val inMonth = s.expenses.filter { YearMonth.from(it.date) == month }
        return ExpensesReport(
            month = month,
            categories = s.categories.map { c ->
                val entries = inMonth.filter { it.categoryId == c.id }.sortedByDescending { it.date }
                CategoryTotal(c, entries.sumOf { it.amountCents }, entries)
            },
            indirectTotalCents = indirectTotalCents(s, month),
            directExpense = directExpense(s, month),
        )
    }

    fun returnsReport(s: ServerState, month: YearMonth): ReturnsReport {
        val monthMaterials = materialsFor(s, month).associateBy { it.material.id }
        val net = netChapathis(s, null, month)
        val indirect = indirectTotalCents(s, month)
        val rows = s.returns.filter { YearMonth.from(it.date) == month }.sortedByDescending { it.date }.map { r ->
            // Information only: the replacement is already inside direct expense (owner change).
            val cost: Figure = if (r.resolution == ReturnResolution.REPLACEMENT) {
                val product = s.products.first { it.id == r.productId }
                when (val c = costFor(s, product, monthMaterials, r.chapathisPerPacket, indirect, net)) {
                    is ProductCost.Complete -> Figure.Known(divHalfUp(c.directTt * r.qtyPackets, TT_PER_CENT))
                    is ProductCost.Incomplete -> Figure.Incomplete(c.missing)
                }
            } else {
                Figure.Known(0L)
            }
            AdminReturn(r.id, r.date, r.customerName, r.productName, r.qtyPackets, r.chapathisPerPacket, r.reason, r.resolution, r.creditCents, cost)
        }
        val damage = s.damage.filter { YearMonth.from(it.date) == month }.sortedByDescending { it.date }.map { d ->
            ProductionDamage(d.id, d.date, d.productId, s.products.first { it.id == d.productId }.name, d.chapathis, d.note, d.enteredBy)
        }
        val replacements = rows.filter { it.resolution == ReturnResolution.REPLACEMENT }
        return ReturnsReport(
            month = month,
            returns = rows,
            damage = damage,
            creditsTotalCents = rows.sumOf { it.creditCents },
            replacementPackets = replacements.sumOf { it.qtyPackets },
            replacementChapathis = replacements.sumOf { it.qtyPackets * it.chapathisPerPacket },
            damagedChapathis = damage.sumOf { it.chapathis },
        )
    }

    fun paymentsOf(s: ServerState, customerId: String): List<AdminPayment> =
        s.payments.filter { it.customerId == customerId }.sortedByDescending { it.date }
}
