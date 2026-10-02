package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.AdminReturn
import com.mamre.billing.domain.admin.AppliedPayment
import com.mamre.billing.domain.admin.BalanceRow
import com.mamre.billing.domain.admin.CategoryTotal
import com.mamre.billing.domain.admin.CostingReport
import com.mamre.billing.domain.admin.CustomerMonthSummary
import com.mamre.billing.domain.admin.DashboardReport
import com.mamre.billing.domain.admin.ExpenseKind
import com.mamre.billing.domain.admin.ExpensesReport
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.InvoiceCredit
import com.mamre.billing.domain.admin.InvoiceDetail
import com.mamre.billing.domain.admin.MonthSales
import com.mamre.billing.domain.admin.NamedAmount
import com.mamre.billing.domain.admin.ProductCost
import com.mamre.billing.domain.admin.ProductionDamage
import com.mamre.billing.domain.admin.ReturnsReport
import com.mamre.billing.domain.admin.monthWindow
import com.mamre.billing.domain.worker.CreditEntry
import com.mamre.billing.domain.worker.InvoiceEntry
import com.mamre.billing.domain.worker.LedgerEntry
import com.mamre.billing.domain.worker.PaymentEntry
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.domain.worker.broughtForward
import com.mamre.billing.domain.worker.ledgerBalance
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * THE SERVER STAND-IN. Doc 2 s1.1 makes the server the source of truth for costing and reports, and
 * the device never recomputes them. These functions exist only so [FakeAdminApi] can give the Admin
 * screens figures that agree with each other; a real server replaces them with API calls, and the
 * release build, which has no fake, does not contain them. Nothing in domain/admin or ui/admin
 * computes cost, profit or balances.
 *
 * Money is Long cents. Cost per packet is a Long in ten-thousandths of a dollar (Doc 1 s9.4); the
 * integer arithmetic reproduces the Doc 1 s9.5 example exactly ($0.5475, $0.6975, $0.8475).
 */
object ServerLogic {
    private const val TT_PER_CENT = 100L
    private const val DAYS_30 = 30L
    private const val DAYS_60 = 60L
    const val SORBATE_NOTE = "(Doc 1 P-2)"

    /** a / b rounded half up, for non-negative numbers. */
    fun divHalfUp(a: Long, b: Long): Long = (a * 2 + b) / (b * 2)

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
        BalanceRow(c.id, c.name, c.typeName, bal, current, over30, over60)
    }.sortedByDescending { it.balanceCents }

    // ------------------------------------------------------------------ costing

    private fun recipeFor(s: ServerState, productId: String) = s.recipes[productId].orEmpty()

    /** Cost of the ingredients in 1 kg of wheat on [date], in ten-thousandths of a dollar, or what is missing. */
    private fun perKgTt(s: ServerState, productId: String, date: LocalDate): Pair<Long?, List<String>> {
        var total = 0L
        val missing = mutableListOf<String>()
        for (line in recipeFor(s, productId)) {
            val ing = s.ingredients.first { it.id == line.ingredientId }
            val qty = line.milliPerKgWheat
            val price = ing.priceOn(date)
            if (qty == null) {
                missing += "${ing.name} quantity per kg of wheat" + if (ing.id == SeedIds.SORBATE) " $SORBATE_NOTE" else ""
            }
            if (price == null) missing += "${ing.name} price"
            if (qty != null && price != null) {
                total += divHalfUp(qty * price.priceCents * TT_PER_CENT, ing.basePerPurchaseUnit.toLong() * 1000)
            }
        }
        return if (missing.isEmpty()) total to emptyList() else null to missing
    }

    /** Cost of the ingredients per packet before any damage: the Doc 1 s9.5 table. */
    fun ingredientsPerPacketTt(s: ServerState, productId: String, date: LocalDate): Long? {
        val product = s.products.first { it.id == productId }
        val perKg = perKgTt(s, productId, date).first ?: return null
        // A packet of 12 uses 12 / 32 kg of wheat (Doc 1 s9.3).
        return divHalfUp(perKg * product.unitsPerPacket, 32)
    }

    fun netPackets(s: ServerState, productId: String?, month: YearMonth): Int {
        val sold = s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }
            .sumOf { inv -> inv.items.filter { productId == null || it.productId == productId }.sumOf { it.qtyPackets } }
        val credited = s.returns.filter { it.resolution == ReturnResolution.CREDIT && YearMonth.from(it.date) == month && (productId == null || it.productId == productId) }
            .sumOf { it.qtyPackets }
        return sold - credited
    }

    fun indirectTotalCents(s: ServerState, month: YearMonth): Long =
        s.expenses.filter { it.kind == ExpenseKind.INDIRECT && YearMonth.from(it.date) == month }.sumOf { it.amountCents }

    /** Direct cost per good packet for a month: batch ingredient cost over good packets, plus packing (Doc 1 s9.4). */
    fun directTt(s: ServerState, productId: String, month: YearMonth): Pair<Long?, List<String>> {
        val product = s.products.first { it.id == productId }
        val inMonth = s.batches.filter { it.productId == productId && YearMonth.from(it.date) == month }
        val batches = inMonth.ifEmpty {
            // No batch this month: use the most recent one (average cost, Doc 1 A-10).
            listOfNotNull(s.batches.filter { it.productId == productId && YearMonth.from(it.date).isBefore(month) }.maxByOrNull { it.date })
        }
        if (batches.isEmpty()) return null to listOf("A production batch for ${product.name}")
        var batchTotalTt = 0L
        val missing = linkedSetOf<String>()
        for (b in batches) {
            val (perKg, m) = perKgTt(s, productId, b.date)
            if (perKg == null) missing += m else batchTotalTt += perKg * b.wheatKg
        }
        if (missing.isNotEmpty()) return null to missing.toList()
        val good = batches.sumOf { it.goodPackets }.toLong()
        if (good == 0L) return null to listOf("Good packets for ${product.name}")
        return divHalfUp(batchTotalTt, good) + product.packingCostCents * TT_PER_CENT to emptyList()
    }

    fun productCost(s: ServerState, productId: String, month: YearMonth): ProductCost {
        val product = s.products.first { it.id == productId }
        val (direct, missing) = directTt(s, productId, month)
        val net = netPackets(s, null, month)
        if (direct == null) return ProductCost.Incomplete(productId, product.name, missing)
        if (net <= 0) return ProductCost.Incomplete(productId, product.name, listOf("Packets sold this month"))
        val indirect = divHalfUp(indirectTotalCents(s, month) * TT_PER_CENT, net.toLong())
        val packing = product.packingCostCents * TT_PER_CENT
        return ProductCost.Complete(productId, product.name, direct - packing, packing, direct, indirect, direct + indirect)
    }

    fun costing(s: ServerState, month: YearMonth) = CostingReport(
        month = month,
        products = s.products.map { productCost(s, it.id, month) },
        indirectTotalCents = indirectTotalCents(s, month),
        netPackets = netPackets(s, null, month),
    )

    /** Direct cost of the packets sold in the month, or INCOMPLETE naming the missing inputs. */
    fun directCostCents(s: ServerState, month: YearMonth): Figure {
        var totalTt = 0L
        val missing = linkedSetOf<String>()
        for (p in s.products) {
            val net = netPackets(s, p.id, month)
            if (net <= 0) continue
            val (tt, m) = directTt(s, p.id, month)
            if (tt == null) missing += m else totalTt += tt * net
        }
        return if (missing.isEmpty()) Figure.Known(divHalfUp(totalTt, TT_PER_CENT)) else Figure.Incomplete(missing.toList())
    }

    fun replacementCost(s: ServerState, month: YearMonth): Figure {
        var totalTt = 0L
        val missing = linkedSetOf<String>()
        for (r in s.returns.filter { it.resolution == ReturnResolution.REPLACEMENT && YearMonth.from(it.date) == month }) {
            val (tt, m) = directTt(s, r.productId, month)
            if (tt == null) missing += m else totalTt += tt * r.qtyPackets
        }
        return if (missing.isEmpty()) Figure.Known(divHalfUp(totalTt, TT_PER_CENT)) else Figure.Incomplete(missing.toList())
    }

    // ------------------------------------------------------------------ reports

    fun netSalesCents(s: ServerState, month: YearMonth): Long =
        s.invoices.filter { !it.isVoid && YearMonth.from(it.issuedAt) == month }.sumOf { it.totalCents } -
            s.returns.filter { YearMonth.from(it.date) == month }.sumOf { it.creditCents }

    fun dashboard(s: ServerState, month: YearMonth, earliest: YearMonth): DashboardReport {
        val netSales = netSalesCents(s, month)
        val end = month.atEndOfMonth()
        val cash = s.payments.filter { YearMonth.from(it.date) == month }.sumOf { it.amountCents }
        // Outstanding: what customers still owe at month end (credit on account is not a receivable).
        val outstanding = s.customers.sumOf { maxOf(balance(s, it.id, end), 0L) }
        val direct = directCostCents(s, month)
        val indirect = indirectTotalCents(s, month)
        val replacement = replacementCost(s, month)
        val gross: Figure = if (direct is Figure.Known) Figure.Known(netSales - direct.value) else direct
        val net: Figure = when {
            gross is Figure.Known && replacement is Figure.Known -> Figure.Known(gross.value - indirect - replacement.value)
            else -> Figure.Incomplete(((gross as? Figure.Incomplete)?.missing.orEmpty() + (replacement as? Figure.Incomplete)?.missing.orEmpty()).distinct())
        }
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
        val missing = (((direct as? Figure.Incomplete)?.missing.orEmpty()) + ((replacement as? Figure.Incomplete)?.missing.orEmpty())).distinct()
        return DashboardReport(
            month = month,
            inProgress = month == YearMonth.from(s.today),
            netSalesCents = netSales,
            cashCollectedCents = cash,
            outstandingCents = outstanding,
            directCost = direct,
            grossProfit = gross,
            indirectExpensesCents = indirect,
            replacementCost = replacement,
            netProfit = net,
            sixMonthSales = monthWindow(month, 6, earliest).map { MonthSales(it, netSalesCents(s, it)) },
            salesByProduct = byProduct,
            salesByCustomerType = byType,
            missingInputs = missing,
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
            directCost = directCostCents(s, month),
        )
    }

    fun returnsReport(s: ServerState, month: YearMonth): ReturnsReport {
        val rows = s.returns.filter { YearMonth.from(it.date) == month }.sortedByDescending { it.date }.map { r ->
            val cost: Figure = if (r.resolution == ReturnResolution.REPLACEMENT) {
                val (tt, m) = directTt(s, r.productId, month)
                if (tt == null) Figure.Incomplete(m) else Figure.Known(divHalfUp(tt * r.qtyPackets, TT_PER_CENT))
            } else {
                Figure.Known(0L)
            }
            AdminReturn(r.id, r.date, r.customerName, r.productName, r.qtyPackets, r.reason, r.resolution, r.creditCents, cost)
        }
        val damage = s.batches.filter { YearMonth.from(it.date) == month && it.packetsDamaged > 0 }.sortedByDescending { it.date }.map { b ->
            ProductionDamage(b.id, b.date, s.products.first { it.id == b.productId }.name, b.packetsPacked, b.packetsDamaged)
        }
        return ReturnsReport(
            month = month,
            returns = rows,
            damage = damage,
            creditsTotalCents = rows.sumOf { it.creditCents },
            replacementPackets = rows.filter { it.resolution == ReturnResolution.REPLACEMENT }.sumOf { it.qtyPackets },
            replacementCost = replacementCost(s, month),
            damagedPackets = damage.sumOf { it.packetsDamaged },
        )
    }

    fun paymentsOf(s: ServerState, customerId: String): List<AdminPayment> =
        s.payments.filter { it.customerId == customerId }.sortedByDescending { it.date }
}
