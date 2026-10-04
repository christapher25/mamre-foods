package com.mamre.billing.data.admin

import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.InvoiceItem
import com.mamre.billing.domain.admin.ProductCost
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.admin.valueOrNull
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.InvoiceStatus
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Admin's fake server must give figures that agree with each other (Doc 2 s1.1, Doc 1 s11):
 * the screens only show what it returns, and the device recomputes nothing.
 */
class FakeAdminApiTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T14:00:00Z"), ZoneOffset.UTC)
    private val api = FakeAdminApi(clock)
    private val first = YearMonth.of(2026, 4)
    private val last = YearMonth.of(2026, 10)
    private val allMonths = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()
    private val completeMonths = listOf(YearMonth.of(2026, 4), YearMonth.of(2026, 5))
    private val sorbateQuantity = "Potassium sorbate quantity per kg of wheat for Mamre Chapathi (Doc 1 P-2)"

    private suspend fun expectRefused(block: suspend () -> Unit): AdminRuleException {
        try {
            block()
        } catch (e: AdminRuleException) {
            return e
        }
        fail("expected AdminRuleException")
        throw IllegalStateException()
    }

    // --- the data span and determinism ---

    @Test fun theDataRunsFromSixMonthsBackToTheMonthInProgress() = runTest {
        val span = api.span()
        assertEquals(first, span.first)
        assertEquals(last, span.last)
    }

    @Test fun theSeedIsDeterministic() {
        val a = AdminSeed.build(LocalDate.of(2026, 10, 2))
        val b = AdminSeed.build(LocalDate.of(2026, 10, 2))
        assertEquals(a, b)
        assertTrue(a.invoices.size > 100)
    }

    // --- net profit = gross profit - indirect expenses (owner costing spec 4; REWRITTEN, it used to subtract replacement cost) ---

    @Test fun netProfitIsGrossProfitMinusIndirectExpensesOnlyWhenComplete() = runTest {
        for (m in completeMonths) {
            val d = api.dashboard(m)
            val gross = d.grossProfit.valueOrNull!!
            assertEquals("month $m", gross - d.indirectExpensesCents, d.netProfit.valueOrNull)
        }
    }

    @Test fun grossProfitIsNetSalesMinusDirectCost() = runTest {
        for (m in completeMonths) {
            val d = api.dashboard(m)
            assertEquals(d.netSalesCents - d.directCost.valueOrNull!!, d.grossProfit.valueOrNull)
        }
    }

    @Test fun completeMonthsHaveEveryFigure() = runTest {
        for (m in completeMonths) {
            val d = api.dashboard(m)
            assertTrue(d.directCost is Figure.Known && d.grossProfit is Figure.Known && d.netProfit is Figure.Known)
            assertTrue(d.missingInputs.isEmpty())
            assertTrue(d.netSalesCents > 0)
        }
    }

    // --- INCOMPLETE is never zero (Doc 1 s9.1, Doc 3 N5) ---

    @Test fun monthsSellingMamreChapathiAreIncompleteBecauseOfThePotassiumSorbate() = runTest {
        for (m in allMonths - completeMonths.toSet()) {
            val d = api.dashboard(m)
            assertTrue("direct $m", d.directCost is Figure.Incomplete)
            assertTrue("gross $m", d.grossProfit is Figure.Incomplete)
            assertTrue("net $m", d.netProfit is Figure.Incomplete)
            assertTrue(d.missingInputs.contains(sorbateQuantity))
            assertTrue((d.directCost as Figure.Incomplete).missing.contains(sorbateQuantity))
            // What does not depend on cost is still shown.
            assertTrue(d.netSalesCents > 0)
            assertTrue(d.indirectExpensesCents > 0)
        }
    }

    // --- the breakdowns add up ---

    @Test fun salesByProductAndByCustomerTypeBothAddUpToNetSales() = runTest {
        for (m in allMonths) {
            val d = api.dashboard(m)
            assertEquals("product $m", d.netSalesCents, d.salesByProduct.sumOf { it.cents })
            assertEquals("type $m", d.netSalesCents, d.salesByCustomerType.sumOf { it.cents })
        }
    }

    @Test fun theSixMonthSeriesMatchesEachMonthsNetSales() = runTest {
        val d = api.dashboard(YearMonth.of(2026, 9))
        assertEquals((4..9).map { YearMonth.of(2026, it) }, d.sixMonthSales.map { it.month })
        for (point in d.sixMonthSales) assertEquals(api.dashboard(point.month).netSalesCents, point.netSalesCents)
        val early = api.dashboard(YearMonth.of(2026, 5))
        assertEquals(listOf(4, 5).map { YearMonth.of(2026, it) }, early.sixMonthSales.map { it.month })
    }

    @Test fun netSalesIsInvoicedLessReturnCredits() = runTest {
        val m = YearMonth.of(2026, 9)
        val invoiced = api.invoices().filter { !it.isVoid && YearMonth.from(it.issuedAt) == m }.sumOf { it.totalCents }
        val credits = api.returnsReport(m).creditsTotalCents
        assertTrue(credits > 0)
        assertEquals(invoiced - credits, api.dashboard(m).netSalesCents)
    }

    @Test fun cashCollectedIsThePaymentsOfTheMonthAndOutstandingIsWhatIsOwedAtMonthEnd() = runTest {
        val m = YearMonth.of(2026, 9)
        val d = api.dashboard(m)
        val fromCustomers = api.customers().sumOf { c -> maxOf(api.customerSummary(c.id, m).closingCents, 0L) }
        assertEquals(fromCustomers, d.outstandingCents)
        assertTrue(d.cashCollectedCents > 0)
        // Payments of the month, counted again through the customers' own month summaries plus walk-ins.
        val perCustomer = api.customers().sumOf { c -> api.customerSummary(c.id, m).payments.sumOf { it.amountCents } }
        assertTrue(d.cashCollectedCents >= perCustomer)
    }

    // --- customers, ledger and ageing ---

    @Test fun everyCustomerMonthAddsUp() = runTest {
        for (c in api.customers()) for (m in allMonths) {
            val s = api.customerSummary(c.id, m)
            assertEquals(
                "${c.name} $m",
                s.openingCents + s.invoicedCents - s.creditsCents - s.payments.sumOf { it.amountCents },
                s.closingCents,
            )
        }
    }

    @Test fun eachMonthOpensWithThePreviousMonthsClosingBalance() = runTest {
        for (c in api.customers()) for (m in allMonths.drop(1)) {
            assertEquals(api.customerSummary(c.id, m.minusMonths(1)).closingCents, api.customerSummary(c.id, m).openingCents)
        }
    }

    @Test fun balancesAreTheLedgerAndAgeingAddsUpToWhatIsOwed() = runTest {
        val rows = api.balances()
        assertEquals(rows.map { it.balanceCents }.sortedDescending(), rows.map { it.balanceCents })
        for (r in rows) {
            assertEquals(r.customerName, api.customerBalance(r.customerId), r.balanceCents)
            val aged = r.currentCents + r.over30Cents + r.over60Cents
            if (r.balanceCents > 0) assertEquals(r.customerName, r.balanceCents, aged) else assertEquals(r.customerName, 0L, aged)
        }
        assertTrue(rows.any { it.over30Cents > 0 || it.over60Cents > 0 || it.currentCents > 0 })
    }

    @Test fun theOldestInvoicesArePaidFirst() = runTest {
        val id = SeedIds.SPICE_GARDEN
        val invoices = api.invoices().filter { it.customerId == id && !it.isVoid }.sortedBy { it.issuedAt }
        val dues = invoices.map { api.invoiceDetail(it.id)!!.amountDueCents }
        // Once an invoice is (part) unpaid, every later one is untouched, so dues never rise then fall to zero.
        val firstOwing = dues.indexOfFirst { it > 0 }
        assertTrue(firstOwing >= 0)
        for (i in 0 until firstOwing) assertEquals(0L, dues[i])
        for (i in firstOwing + 1 until dues.size) assertEquals(invoices[i].totalCents - creditOn(invoices[i].id), dues[i])
        assertEquals(api.customerBalance(id), dues.sum())
    }

    private suspend fun creditOn(invoiceId: String) = api.invoiceDetail(invoiceId)!!.credits.sumOf { it.creditCents }

    /** Doc 1 s6.5: invoices 120, 90 and 60 and a 150 payment give $120 owing, invoice 2 owing $60. */
    @Test fun section65WorkedExampleThroughTheServerLedger() {
        val base = AdminSeed.build(LocalDate.of(2026, 10, 2))
        val customer = AdminCustomer("c", "Restaurant", SeedIds.RESTAURANT, "Restaurant", "", "", PaymentMode.CREDIT, "", true, 0)
        fun invoice(n: Int, day: Int, cents: Long) = AdminInvoice(
            "i$n", "MAM-W1-000$n", "c", "Restaurant", "Restaurant", "W1", LocalDateTime.of(2026, 10, day, 9, 0),
            listOf(InvoiceItem(SeedIds.FRESH, "Mamre Fresh Chapathi", 1, cents, cents)), cents, InvoiceStatus.ACTIVE,
        )
        val s = base.copy(
            customers = listOf(customer),
            invoices = listOf(invoice(1, 3, 12000), invoice(2, 10, 9000), invoice(3, 20, 6000)),
            payments = listOf(AdminPayment("p", "RCP-W1-0001", "c", "Restaurant", LocalDate.of(2026, 10, 12), 15000, PaymentMethod.CASH, "")),
            paymentInvoiceIds = emptyMap(),
            returns = emptyList(),
        )
        assertEquals(12000L, ServerLogic.balance(s, "c"))
        assertEquals(0L, ServerLogic.invoiceDetail(s, "i1")!!.amountDueCents)
        assertEquals(6000L, ServerLogic.invoiceDetail(s, "i2")!!.amountDueCents)
        assertEquals(6000L, ServerLogic.invoiceDetail(s, "i3")!!.amountDueCents)
        val a = ServerLogic.invoiceDetail(s, "i2")!!.payments.single()
        assertEquals(3000L, a.amountCents) // $30.00 of the payment went to invoice 2
    }

    // --- void (B2; Doc 1 s5.4, AT-7) ---

    @Test fun aVoidNeedsAReasonAndChangesNothingWithout() = runTest {
        val inv = api.invoices().first { !it.isVoid && it.customerId != null }
        val before = api.dashboard(YearMonth.from(inv.issuedAt)).netSalesCents
        expectRefused { api.voidInvoice(inv.id, "   ", "Test Admin") }
        assertFalse(api.invoices().first { it.id == inv.id }.isVoid)
        assertEquals(before, api.dashboard(YearMonth.from(inv.issuedAt)).netSalesCents)
        assertTrue(api.changeLog.value.isEmpty())
    }

    @Test fun aVoidKeepsTheInvoiceAndItsNumberRemovesItFromSalesAndIsLogged() = runTest {
        val inv = api.invoices().first { !it.isVoid && it.customerId == SeedIds.SPICE_GARDEN }
        val month = YearMonth.from(inv.issuedAt)
        val salesBefore = api.dashboard(month).netSalesCents
        val balanceBefore = api.customerBalance(SeedIds.SPICE_GARDEN)
        val detail = api.voidInvoice(inv.id, "  Wrong customer ", "Test Admin")
        assertTrue(detail.invoice.isVoid)
        assertEquals("Wrong customer", detail.invoice.voidReason)
        assertEquals("Test Admin", detail.invoice.voidedBy)
        assertEquals(inv.number, detail.invoice.number)
        assertEquals(inv.items, detail.invoice.items) // never edited, only voided
        assertTrue(api.invoices().any { it.id == inv.id }) // never deleted
        assertEquals(salesBefore - inv.totalCents, api.dashboard(month).netSalesCents)
        assertEquals(balanceBefore - inv.totalCents, api.customerBalance(SeedIds.SPICE_GARDEN))
        val entry = api.changeLog.value.single()
        assertEquals("Test Admin", entry.who)
        assertTrue(entry.what.contains(inv.number))
        assertTrue(entry.after.contains("Wrong customer"))
        assertNotEquals(entry.before, entry.after)
    }

    @Test fun anInvoiceCannotBeVoidedTwice() = runTest {
        val inv = api.invoices().first { !it.isVoid }
        api.voidInvoice(inv.id, "Mistake", "Test Admin")
        expectRefused { api.voidInvoice(inv.id, "Again", "Test Admin") }
    }

    @Test fun theSeedAlreadyContainsOneVoidedInvoice() = runTest {
        val voided = api.invoices().filter { it.isVoid }
        assertEquals(1, voided.size)
        assertFalse(voided.single().voidReason.isNullOrBlank())
        assertEquals(0L, api.invoiceDetail(voided.single().id)!!.amountDueCents)
    }

    // --- prices (B3, B4) ---

    @Test fun aNewDefaultPriceIsAddedToTheHistoryNotWrittenOver() = runTest {
        val before = api.priceMatrix().history(SeedIds.FRESH, SeedIds.SHOP)
        api.setDefaultPrice(SeedIds.FRESH, SeedIds.SHOP, 310, LocalDate.of(2026, 11, 1), "Test Admin")
        val after = api.priceMatrix().history(SeedIds.FRESH, SeedIds.SHOP)
        assertEquals(before.size + 1, after.size)
        assertTrue(after.containsAll(before))
        assertEquals(310L, after.first().unitPriceCents)
        // Today the old price still applies; the new one starts on its date.
        assertEquals(before.first().unitPriceCents, api.priceMatrix().current(SeedIds.FRESH, SeedIds.SHOP, api.today)!!.unitPriceCents)
        assertEquals(310L, api.priceMatrix().current(SeedIds.FRESH, SeedIds.SHOP, LocalDate.of(2026, 11, 1))!!.unitPriceCents)
        val log = api.changeLog.value.single()
        assertTrue(log.before.contains(before.first().unitPriceCents.let { "$" + it / 100 }))
        assertTrue(log.after.contains("$3.10"))
    }

    @Test fun aPriceMustBeAboveZeroAndItsDateLaterThanTheCurrentRow() = runTest {
        val latest = api.priceMatrix().history(SeedIds.FRESH, SeedIds.SHOP).first()
        expectRefused { api.setDefaultPrice(SeedIds.FRESH, SeedIds.SHOP, 0, LocalDate.of(2026, 11, 1), "Test Admin") }
        expectRefused { api.setDefaultPrice(SeedIds.FRESH, SeedIds.SHOP, 310, latest.effectiveFrom, "Test Admin") }
        expectRefused { api.setDefaultPrice(SeedIds.FRESH, SeedIds.SHOP, 310, latest.effectiveFrom.minusDays(1), "Test Admin") }
        assertEquals(latest, api.priceMatrix().history(SeedIds.FRESH, SeedIds.SHOP).first())
        assertTrue(api.changeLog.value.isEmpty())
    }

    @Test fun anOverrideCanBeSetThenClearedAndTheHistoryStays() = runTest {
        val id = SeedIds.CORNER_SHOP
        assertTrue(api.overrides(id).isEmpty())
        api.setOverride(id, SeedIds.FRESH, 290, LocalDate.of(2026, 10, 1), "Loyalty", "Test Admin")
        assertEquals(listOf(true), api.overrides(id).map { it.isActive })
        expectRefused { api.setOverride(id, SeedIds.FRESH, 280, LocalDate.of(2026, 10, 1), "", "Test Admin") } // not later
        api.clearOverride(id, SeedIds.FRESH, "Test Admin")
        assertEquals(listOf(false), api.overrides(id).map { it.isActive }) // kept, switched off
        expectRefused { api.clearOverride(id, SeedIds.FRESH, "Test Admin") }
        assertEquals(2, api.changeLog.value.size)
    }

    // --- costing (B5; Doc 1 s9) ---

    @Test fun theDoc1Section95ExampleComesOutExactly() {
        // REWRITTEN: now through the shared materials. Wheat $1.10/kg, oil $4.00/L, sugar $1.00/kg, salt $0.80/kg,
        // baking powder $4.00/kg, packing 15 cents (Doc 1 s9.5), bought in a clean month with no opening stock.
        val d = LocalDate.of(2026, 9, 2)
        val s = AdminSeed.build(LocalDate.of(2026, 10, 2)).let { seed ->
            seed.copy(
                openingStock = emptyMap(),
                purchases = listOf(
                    Purchase("a", d, SeedIds.WHEAT, "w", 100_000_000, 11_000, "", "t"),
                    Purchase("b", d, SeedIds.OIL, "o", 10_000_000, 4_000, "", "t"),
                    Purchase("c", d, SeedIds.SUGAR, "s", 10_000_000, 1_000, "", "t"),
                    Purchase("d", d, SeedIds.SALT, "s", 10_000_000, 800, "", "t"),
                    Purchase("e", d, SeedIds.BAKING_POWDER, "b", 1_000_000, 400, "", "t"),
                    Purchase("f", d, SeedIds.PACKING, "p", 1_000_000, 15_000, "", "t"),
                ),
                damage = emptyList(),
            )
        }
        // The Doc 1 s9.5 packet is 12 chapathis; the standard packet is now 6, so ask for 12.
        val fresh = ServerLogic.packetCost(s, YearMonth.of(2026, 9), SeedIds.FRESH, 12) as ProductCost.Complete
        assertEquals(5475L, fresh.lines.sumOf { it.tt }) // $0.5475 ingredients
        assertEquals(5475L, fresh.ingredientsTt)
        assertEquals(6975L, fresh.ingredientsTt + fresh.packingTt) // $0.6975 with packing
        val indirect = ServerLogic.divHalfUp(900_00L * 100, 6000) // $900 over 6,000 net packets
        assertEquals(1500L, indirect) // $0.1500
        assertEquals(8475L, 6975L + indirect) // $0.8475
    }

    @Test fun mamreChapathiCostIsIncompleteAndNamesWhatIsMissing() = runTest {
        val report = api.costing(YearMonth.of(2026, 9))
        val chapathi = report.products.first { it.productId == SeedIds.CHAPATHI } as ProductCost.Incomplete
        assertTrue(chapathi.missing.contains("Potassium sorbate quantity per kg of wheat (Doc 1 P-2)"))
        assertTrue(chapathi.missing.contains("Potassium sorbate price"))
        val fresh = report.products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        assertEquals(fresh.directTt + fresh.indirectTt, fresh.fullTt)
        assertEquals(fresh.ingredientsTt + fresh.wastageTt + fresh.packingTt, fresh.directTt)
        assertEquals(1500L, fresh.packingTt)
    }

    @Test fun productionDamageCountsAsMaterialUsage() = runTest {
        val m = YearMonth.of(2026, 9)
        val before = api.stock(m).rows.first { it.material.id == SeedIds.WHEAT }.usedMb.valueOrNull!!
        api.addProductionDamage(SeedIds.FRESH, LocalDate.of(2026, 9, 29), 100, "Dropped trays", "Test Admin")
        val after = api.stock(m).rows.first { it.material.id == SeedIds.WHEAT }.usedMb.valueOrNull!!
        assertEquals(before + 3_187_500L, after) // 100 chapathis x 1 kg per 32 = 3.125 kg, x 1.02
        assertEquals(1, api.changeLog.value.size)
    }

    @Test fun aPurchaseAtAHigherPriceRaisesTheCostPerPacket() = runTest {
        val m = YearMonth.of(2026, 10)
        val before = api.costing(m).products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        api.addPurchase(SeedIds.WHEAT, LocalDate.of(2026, 10, 1), 25_000_000, 6_000, "", "Test Admin") // 25 kg for $60
        val after = api.costing(m).products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        assertTrue(after.directTt > before.directTt)
        assertEquals(after.directTt + after.indirectTt, after.fullTt)
        assertEquals(1, api.changeLog.value.size)
    }

    @Test fun aPurchaseNeedsAKnownMaterialAndAQuantityAndTotalAboveZero() = runTest {
        val d = LocalDate.of(2026, 10, 1)
        expectRefused { api.addPurchase("nope", d, 1_000, 100, "", "Test Admin") }
        expectRefused { api.addPurchase(SeedIds.WHEAT, d, 0, 100, "", "Test Admin") }
        expectRefused { api.addPurchase(SeedIds.WHEAT, d, 1_000, 0, "", "Test Admin") }
        assertTrue(api.changeLog.value.isEmpty())
    }

    @Test fun givingPotassiumSorbateAPriceDoesNotCompleteTheCostWhileTheQuantityIsMissing() = runTest {
        api.addPurchase(SeedIds.SORBATE, LocalDate.of(2026, 9, 2), 1_000_000, 900, "", "Test Admin")
        val chapathi = api.costing(YearMonth.of(2026, 9)).products.first { it.productId == SeedIds.CHAPATHI } as ProductCost.Incomplete
        assertTrue(chapathi.missing.contains("Potassium sorbate quantity per kg of wheat (Doc 1 P-2)"))
        assertFalse(chapathi.missing.contains("Potassium sorbate price"))
    }

    @Test fun wastageAndRecipeEditsAreCheckedAndLogged() = runTest {
        assertEquals(200, api.wastageBp())
        expectRefused { api.setWastageBp(501, "Test Admin") }
        expectRefused { api.setWastageBp(-1, "Test Admin") }
        api.setWastageBp(300, "Test Admin")
        assertEquals(300, api.wastageBp())
        expectRefused { api.setRecipeQuantity(SeedIds.FRESH, SeedIds.WHEAT, 0, "Test Admin") }
        api.setRecipeQuantity(SeedIds.CHAPATHI, SeedIds.SORBATE, 500, "Test Admin")
        assertEquals(500L, api.recipes().first { it.productId == SeedIds.CHAPATHI }.lines.first { it.materialId == SeedIds.SORBATE }.qtyMb)
        assertEquals(2, api.changeLog.value.size)
    }

    // --- expenses (B6, add only) ---

    @Test fun anExpenseIsAddedAndCountsTowardsTheIndirectTotalEverywhere() = runTest {
        val m = YearMonth.of(2026, 9)
        val before = api.expenses(m).indirectTotalCents
        val e = api.addExpense(SeedIds.CAT_ELECTRICITY, LocalDate.of(2026, 9, 30), 12_345, " Extra bill ", "Test Admin")
        assertEquals("Extra bill", e.description)
        assertEquals("Test Admin", e.enteredBy)
        val report = api.expenses(m)
        assertEquals(before + 12_345, report.indirectTotalCents)
        assertEquals(report.indirectTotalCents, report.categories.sumOf { it.totalCents })
        assertEquals(report.indirectTotalCents, api.dashboard(m).indirectExpensesCents)
        assertEquals(1, api.changeLog.value.size)
    }

    @Test fun anExpenseNeedsACategoryAndAnAmountAboveZero() = runTest {
        expectRefused { api.addExpense("nope", LocalDate.of(2026, 9, 30), 100, "", "Test Admin") }
        expectRefused { api.addExpense(SeedIds.CAT_LABOUR, LocalDate.of(2026, 9, 30), 0, "", "Test Admin") }
        assertTrue(api.changeLog.value.isEmpty())
    }

    @Test fun theApiHasNoWayToEditOrDeleteInvoicesPaymentsReturnsOrExpenses() {
        val names = AdminApi::class.java.methods.map { it.name }.toSet()
        assertTrue(names.none { it.startsWith("delete") || it.startsWith("remove") })
        assertEquals(setOf("invoices", "invoiceDetail", "voidInvoice"), names.filter { it.contains("nvoice") }.toSet())
        assertEquals(setOf("expenseCategories", "expenses", "addExpense", "reverseExpense"), names.filter { it.contains("xpense") }.toSet())
        // Purchases and production damage are add only too; a mistake is corrected by a reversing entry (owner spec 5).
        assertEquals(setOf("purchases", "addPurchase", "reversePurchase"), names.filter { it.contains("urchase") }.toSet())
        assertEquals(setOf("addProductionDamage"), names.filter { it.contains("amage") }.toSet())
        assertEquals(setOf("returnsReport"), names.filter { it.contains("eturn") }.toSet())
        assertTrue(names.none { it.contains("ayment") })
    }

    // --- returns and damage (B7) ---

    @Test fun theReturnsReportAddsUp() = runTest {
        for (m in allMonths) {
            val r = api.returnsReport(m)
            assertEquals(r.returns.sumOf { it.creditCents }, r.creditsTotalCents)
            assertEquals(r.damage.sumOf { it.chapathis }, r.damagedChapathis)
        }
        val complete = api.returnsReport(completeMonths.first())
        assertTrue(complete.damagedChapathis > 0) // production damage is listed apart from customer returns
    }

    @Test fun aReplacementHasAnInformationOnlyCostCreditsNothingAndNeverChangesProfit() = runTest {
        val all = allMonths.flatMap { api.returnsReport(it).returns }
        assertTrue(all.any { it.resolution.name == "REPLACEMENT" && it.creditCents == 0L })
        assertTrue(all.any { it.resolution.name == "CREDIT" && it.creditCents > 0L })
        val m = completeMonths.first { api.returnsReport(it).replacementPackets > 0 }
        val rep = api.returnsReport(m).returns.first { it.resolution.name == "REPLACEMENT" }
        assertTrue(rep.replacementCost.valueOrNull!! > 0)
        // Net profit is still gross less indirect: the replacement cost is not subtracted again.
        val d = api.dashboard(m)
        assertEquals(d.grossProfit.valueOrNull!! - d.indirectExpensesCents, d.netProfit.valueOrNull)
    }

    // --- customers and settings (B3, B9) ---

    private fun form(name: String = "New Cafe", type: String = SeedIds.RESTAURANT) =
        CustomerForm(name, type, "555-0100", "1 Main St", PaymentMode.CREDIT, "Net 15", true, location = "Main Street")

    @Test fun aCustomerCanBeAddedAndEditedAndEachChangeIsLogged() = runTest {
        val c = api.addCustomer(form(), "Test Admin")
        assertEquals("Restaurant", c.typeName)
        assertEquals(0L, api.customerBalance(c.id))
        val edited = api.updateCustomer(c.id, form(name = "New Cafe & Bar").copy(isActive = false), "Test Admin")
        assertEquals("New Cafe & Bar", edited.name)
        assertFalse(api.customer(c.id)!!.isActive)
        assertEquals(2, api.changeLog.value.size)
        val edit = api.changeLog.value.first()
        assertTrue(edit.before.contains("New Cafe") && edit.after.contains("New Cafe & Bar"))
    }

    @Test fun aBadCustomerFormIsRefused() = runTest {
        expectRefused { api.addCustomer(form(name = " "), "Test Admin") }
        expectRefused { api.addCustomer(form(type = "t-nope"), "Test Admin") }
        assertTrue(api.changeLog.value.isEmpty())
    }

    @Test fun settingsCanBeEditedAndTheWorkersListIsReadOnly() = runTest {
        api.saveSettings(BusinessSettings("Mamre Foods LLC", "12 Main St", "555-0100", "Thanks!"), "Test Admin")
        assertEquals("Mamre Foods LLC", api.settings().businessName)
        expectRefused { api.saveSettings(BusinessSettings("", "", "", ""), "Test Admin") }
        assertEquals(1, api.changeLog.value.size)
        assertEquals(2, api.workers().size)
        assertTrue(api.workers().any { it.deviceCode == "W1" && it.isActive })
    }


    @Test fun anAdminCanAddASalesmanAndItIsChangeLogged() = runTest {
        val added = api.addSalesman("  Anil   Kumar ", "anil1", "Test Admin")
        assertEquals("Anil Kumar", added.fullName)
        assertEquals("W3", added.deviceCode) // the next free device code
        assertEquals(3, api.workers().size)
        val entry = api.changeLog.value.single()
        assertEquals("Add salesman Anil Kumar", entry.what)
        assertEquals("Test Admin", entry.who)
    }

    @Test fun aDuplicateSalesmanNameIsRefusedIgnoringCaseWithAClearMessage() = runTest {
        val e = expectRefused { api.addSalesman("rajesh", "raj2", "Test Admin") }
        assertTrue(e.message!!, e.message!!.contains("already exists"))
        expectRefused { api.addSalesman("Second  SALESMAN", "sec2", "Test Admin") }
        assertEquals(2, api.workers().size)
        assertTrue(api.changeLog.value.isEmpty())
    }

    @Test fun aSalesmanLoginAlreadyUsedByAnyDemoAccountIsRefused() = runTest {
        expectRefused { api.addSalesman("New Person", "USER1", "Test Admin") }
        expectRefused { api.addSalesman("New Person", "admin", "Test Admin") }
        expectRefused { api.addSalesman("New Person", "ab", "Test Admin") }
        expectRefused { api.addSalesman("N3w", "newp", "Test Admin") }
        assertEquals(2, api.workers().size)
    }
    @Test fun theChangeLogIsNewestFirstAndEveryEntryHasWhoWhatBeforeAfter() = runTest {
        api.addExpense(SeedIds.CAT_LABOUR, LocalDate.of(2026, 9, 30), 100, "", "Test Admin")
        api.setDefaultPrice(SeedIds.FRESH, SeedIds.SHOP, 310, LocalDate.of(2026, 11, 1), "Test Admin")
        val log = api.changeLog.value
        assertEquals(listOf(2L, 1L), log.map { it.id })
        for (e in log) assertTrue(e.who.isNotBlank() && e.what.isNotBlank() && e.before.isNotBlank() && e.after.isNotBlank())
        assertEquals(2L, api.revision.value)
    }
}
