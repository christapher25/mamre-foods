package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.Expense
import com.mamre.billing.domain.admin.ExpenseKind
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.InvoiceItem
import com.mamre.billing.domain.admin.ProductCost
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.admin.StockReport
import com.mamre.billing.domain.admin.StockRow
import com.mamre.billing.domain.admin.valueOrNull
import com.mamre.billing.domain.worker.InvoiceStatus
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnResolution
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner brief, items 3, 4 and 8: materials are bought, used (calculated) and costed on a weighted
 * average. These test the server stand-in directly with small, fully controlled data.
 */
class StockCostingTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val september = YearMonth.of(2026, 9)
    private val october = YearMonth.of(2026, 10)
    private val base = AdminSeed.build(today, SharedPriceTable.seeded(today, baseVersion = 10))

    private fun invoice(packets: Int, product: String = SeedIds.FRESH, day: LocalDate = LocalDate.of(2026, 9, 15), n: Int = 1) =
        AdminInvoice(
            "t-inv-$n", "MAM-W1-9$n", base.customers.first().id, base.customers.first().name, "Restaurant", "W1",
            LocalDateTime.of(day, java.time.LocalTime.NOON),
            listOf(InvoiceItem(product, base.products.first { it.id == product }.name, packets, 100, packets * 100L)),
            packets * 100L, InvoiceStatus.ACTIVE,
        )

    private fun purchase(material: String, qtyMb: Long, cents: Long, day: LocalDate = LocalDate.of(2026, 9, 2), n: Int = 1) =
        Purchase("t-pu-$material-$n", day, material, material, qtyMb, cents, "", "Test Admin")

    /** One invoice, one purchase of wheat, nothing else: the owner's golden case. */
    private fun golden() = base.copy(
        customers = base.customers.take(1),
        invoices = listOf(invoice(1_000)),
        payments = emptyList(),
        paymentInvoiceIds = emptyMap(),
        returns = emptyList(),
        damage = emptyList(),
        purchases = listOf(purchase(SeedIds.WHEAT, 400_000_000, 44_000)),
        openingStock = emptyMap(),
        expenses = emptyList(),
        wastageBp = 200,
    )

    private fun row(report: StockReport, id: String): StockRow = report.rows.first { it.material.id == id }

    private fun returnOf(resolution: ReturnResolution, packets: Int, n: Int = 1) = ReturnRow(
        "t-ret-$n", LocalDate.of(2026, 9, 16), base.customers.first().id, base.customers.first().name, "Restaurant",
        null, SeedIds.FRESH, "Mamre Fresh Chapathi", packets, ReturnReason.DAMAGED, resolution, 100,
        if (resolution == ReturnResolution.CREDIT) packets * 100L else 0L,
    )

    // --- the owner's golden case ---

    @Test fun goldenCaseThousandPacketsWheatTwoPercentWastage() {
        val wheat = row(ServerLogic.stock(golden(), september), SeedIds.WHEAT)
        // 1,000 packets x 0.375 kg x (1 + 2%) = 382.5 kg used.
        assertEquals(Figure.Known(382_500_000L), wheat.usedMb)
        // Bought 400 kg for 44000 cents: $1.10 per kg.
        assertEquals(400_000_000L, wheat.boughtQtyMb)
        assertEquals(44_000L, wheat.boughtCents)
        assertEquals(Figure.Known(11_000L), wheat.avgPriceTt) // $1.1000 per kg
        // Cost consumed 382.5 kg x $1.10 = 42075 cents; closing stock 17.5 kg.
        assertEquals(Figure.Known(42_075L), wheat.costConsumedCents)
        assertEquals(Figure.Known(17_500_000L), wheat.closingQtyMb)
        assertEquals(Figure.Known(1_925L), wheat.closingValueCents) // 17.5 kg x $1.10
        assertFalse(wheat.negativeStock)
    }

    @Test fun usageUsesGrossInvoicedPacketsNotNetOfCredits() {
        val withCredit = golden().copy(returns = listOf(returnOf(ReturnResolution.CREDIT, 100)))
        val wheat = row(ServerLogic.stock(withCredit, september), SeedIds.WHEAT)
        assertEquals(Figure.Known(382_500_000L), wheat.usedMb) // unchanged: the credited packets were still made
        // The credit does lower net sales, which is a different figure.
        assertEquals(1_000 * 100L - 100 * 100L, ServerLogic.netSalesCents(withCredit, september))
    }

    @Test fun usageAlsoCountsReplacementPacketsAndProductionDamage() {
        val s = golden().copy(
            returns = listOf(returnOf(ReturnResolution.REPLACEMENT, 10)),
            damage = listOf(DamageRow("d1", LocalDate.of(2026, 9, 20), SeedIds.FRESH, 20, "Burnt", "Test Admin")),
        )
        val wheat = row(ServerLogic.stock(s, september), SeedIds.WHEAT)
        // (1000 invoiced + 10 replacement + 20 damaged) x 0.375 kg x 1.02
        assertEquals(Figure.Known(393_975_000L), wheat.usedMb)
    }

    @Test fun packingCountsInvoicedAndReplacementPacketsButNotProductionDamage() {
        val s = golden().copy(
            returns = listOf(returnOf(ReturnResolution.REPLACEMENT, 10)),
            damage = listOf(DamageRow("d1", LocalDate.of(2026, 9, 20), SeedIds.FRESH, 20, "Burnt", "Test Admin")),
        )
        val report = ServerLogic.stock(s, september)
        // Damaged packets are spoiled before packing: materials yes, packing no.
        assertEquals(Figure.Known(1_010_000L), row(report, SeedIds.PACKING).usedMb) // 1,000 invoiced + 10 replacement pieces
        assertEquals(Figure.Known(393_975_000L), row(report, SeedIds.WHEAT).usedMb) // still 1,030 packets of wheat
    }

    @Test fun aVoidInvoiceUsesNothing() {
        val s = golden().copy(invoices = listOf(invoice(1_000).copy(status = InvoiceStatus.VOID, voidReason = "x")))
        assertEquals(Figure.Known(0L), row(ServerLogic.stock(s, september), SeedIds.WHEAT).usedMb)
    }

    @Test fun wastageScalesUsageFromZeroToFivePercent() {
        assertEquals(Figure.Known(375_000_000L), row(ServerLogic.stock(golden().copy(wastageBp = 0), september), SeedIds.WHEAT).usedMb)
        assertEquals(Figure.Known(393_750_000L), row(ServerLogic.stock(golden().copy(wastageBp = 500), september), SeedIds.WHEAT).usedMb)
        assertEquals(Figure.Known(384_375_000L), row(ServerLogic.stock(golden().copy(wastageBp = 250), september), SeedIds.WHEAT).usedMb)
    }

    // --- price: weighted average of opening stock and purchases ---

    @Test fun priceIsTheWeightedAverageOfOpeningStockAndPurchases() {
        val s = golden().copy(
            openingStock = mapOf(SeedIds.WHEAT to OpeningStock(100_000_000, 10_000)), // 100 kg at $1.00
            purchases = listOf(purchase(SeedIds.WHEAT, 100_000_000, 12_000)), // 100 kg at $1.20
        )
        val wheat = row(ServerLogic.stock(s, september), SeedIds.WHEAT)
        assertEquals(Figure.Known(11_000L), wheat.avgPriceTt) // (100 + 120) / 200 kg = $1.10
        assertEquals(Figure.Known(100_000_000L), wheat.openingQtyMb)
        assertEquals(Figure.Known(10_000L), wheat.openingValueCents)
    }

    @Test fun twoPurchasesInOneMonthAverageToo() {
        val s = golden().copy(
            purchases = listOf(
                purchase(SeedIds.WHEAT, 200_000_000, 20_000, n = 1), // 200 kg at $1.00
                purchase(SeedIds.WHEAT, 200_000_000, 24_000, LocalDate.of(2026, 9, 20), n = 2), // 200 kg at $1.20
            ),
        )
        assertEquals(Figure.Known(11_000L), row(ServerLogic.stock(s, september), SeedIds.WHEAT).avgPriceTt)
    }

    @Test fun closingStockBecomesNextMonthsOpeningStockAtItsValue() {
        val s = golden().copy(
            purchases = listOf(
                purchase(SeedIds.WHEAT, 400_000_000, 44_000),
                purchase(SeedIds.WHEAT, 100_000_000, 15_000, LocalDate.of(2026, 10, 1), n = 2), // 100 kg at $1.50
            ),
        )
        val oct = row(ServerLogic.stock(s, october), SeedIds.WHEAT)
        assertEquals(Figure.Known(17_500_000L), oct.openingQtyMb)
        assertEquals(Figure.Known(1_925L), oct.openingValueCents)
        // (1925 + 15000) cents over 117.5 kg
        assertEquals(Figure.Known(divHalfUp(16_925L * 100 * 1_000_000, 117_500_000)), oct.avgPriceTt)
    }

    // --- warnings and INCOMPLETE ---

    @Test fun usingMoreThanWasHeldGivesNegativeClosingStockAndAWarning() {
        val s = golden().copy(purchases = listOf(purchase(SeedIds.WHEAT, 100_000_000, 11_000)))
        val wheat = row(ServerLogic.stock(s, september), SeedIds.WHEAT)
        assertEquals(Figure.Known(-282_500_000L), wheat.closingQtyMb) // 100 kg held, 382.5 kg used
        assertTrue(wheat.negativeStock)
        // The shortage is carried into the next month as negative opening stock.
        assertEquals(Figure.Known(-282_500_000L), row(ServerLogic.stock(s, october), SeedIds.WHEAT).openingQtyMb)
    }

    @Test fun aMaterialWithNoPriceIsIncompleteNeverZero() {
        val s = golden()
        val oil = row(ServerLogic.stock(s, september), SeedIds.OIL)
        assertEquals(Figure.Known(30_600_000L), oil.usedMb) // 1,000 x 30 ml x 1.02 is still known
        assertEquals(Figure.Incomplete(listOf("Oil price")), oil.costConsumedCents)
        assertEquals(Figure.Incomplete(listOf("Oil price")), oil.avgPriceTt)
        val total = ServerLogic.stock(s, september).costConsumedTotal
        assertTrue(total is Figure.Incomplete)
        assertTrue((total as Figure.Incomplete).missing.contains("Oil price"))
        assertEquals(Figure.Known(42_075L), row(ServerLogic.stock(s, september), SeedIds.WHEAT).costConsumedCents)
        // Nothing used means nothing to price: a material that was not used is not incomplete.
        assertEquals(Figure.Known(0L), row(ServerLogic.stock(s, september), SeedIds.SORBATE).costConsumedCents)
    }

    @Test fun anUnsetRecipeQuantityMakesUsageIncompleteOnlyWhenThatProductWasMade() {
        val withChapathi = golden().copy(invoices = listOf(invoice(1_000), invoice(50, SeedIds.CHAPATHI, n = 2)))
        val sorbate = row(ServerLogic.stock(withChapathi, september), SeedIds.SORBATE)
        val missing = listOf("Potassium sorbate quantity per packet of Mamre Chapathi (Doc 1 P-2)")
        assertEquals(Figure.Incomplete(missing), sorbate.usedMb)
        assertEquals(Figure.Incomplete(missing), sorbate.costConsumedCents)
        assertEquals(Figure.Incomplete(missing), sorbate.closingQtyMb)
        // Fresh only: the Chapathi quantity does not matter.
        assertEquals(Figure.Known(0L), row(ServerLogic.stock(golden(), september), SeedIds.SORBATE).usedMb)
    }

    @Test fun onceTheQuantityIsSetThePriceIsWhatIsStillMissing() {
        val chapathi = golden().copy(invoices = listOf(invoice(50, SeedIds.CHAPATHI)))
        val recipes = chapathi.recipes.mapValues { (product, lines) ->
            if (product == SeedIds.CHAPATHI) lines.map { if (it.materialId == SeedIds.SORBATE) it.copy(qtyMb = 500) else it } else lines
        }
        val s = chapathi.copy(recipes = recipes)
        val sorbate = row(ServerLogic.stock(s, september), SeedIds.SORBATE)
        assertEquals(Figure.Known(25_500L), sorbate.usedMb) // 50 packets x 0.5 g x 1.02 = 25.5 g
        assertEquals(Figure.Incomplete(listOf("Potassium sorbate price")), sorbate.costConsumedCents)
    }

    // --- direct expense = cost consumed of every material including packing ---

    private fun fullyPriced() = golden().copy(
        purchases = listOf(
            purchase(SeedIds.WHEAT, 400_000_000, 44_000),
            purchase(SeedIds.OIL, 100_000_000, 40_000), // 100 L at $4.00
            purchase(SeedIds.SUGAR, 100_000_000, 10_000),
            purchase(SeedIds.SALT, 100_000_000, 8_000),
            purchase(SeedIds.BAKING_POWDER, 10_000_000, 4_000),
            purchase(SeedIds.PACKING, 5_000_000, 75_000), // 5,000 pieces at 15 cents
        ),
    )

    @Test fun monthDirectExpenseIsTheSumOfCostConsumedAcrossAllMaterialsIncludingPacking() {
        val report = ServerLogic.stock(fullyPriced(), september)
        val sum = report.rows.sumOf { it.costConsumedCents.valueOrNull!! }
        assertEquals(Figure.Known(sum), report.costConsumedTotal)
        assertTrue(row(report, SeedIds.PACKING).costConsumedCents.valueOrNull!! > 0) // packing is part of it
        assertEquals(report.costConsumedTotal, ServerLogic.directExpense(fullyPriced(), september))
        // Packing has no wastage: 1,000 pieces for 1,000 packets, at 15 cents.
        assertEquals(Figure.Known(1_000_000L), row(report, SeedIds.PACKING).usedMb)
        assertEquals(Figure.Known(15_000L), row(report, SeedIds.PACKING).costConsumedCents)
    }

    // --- profit formulas (owner change 5) ---

    @Test fun grossProfitIsNetSalesLessDirectExpenseAndNetProfitIsGrossLessIndirectOnly() {
        val s = fullyPriced().copy(
            expenses = listOf(
                Expense("e1", SeedIds.CAT_LABOUR, "Labour", ExpenseKind.INDIRECT, LocalDate.of(2026, 9, 28), 30_000, "", "Test Admin"),
            ),
            returns = listOf(returnOf(ReturnResolution.REPLACEMENT, 10)), // a replacement no longer has its own deduction
        )
        val d = ServerLogic.dashboard(s, september, september)
        val direct = d.directCost.valueOrNull!!
        assertEquals(d.netSalesCents - direct, d.grossProfit.valueOrNull)
        assertEquals(d.grossProfit.valueOrNull!! - 30_000, d.netProfit.valueOrNull)
        assertEquals(30_000L, d.indirectExpensesCents)
    }

    @Test fun anIncompleteDirectExpenseMakesGrossAndNetProfitIncompleteToo() {
        val d = ServerLogic.dashboard(golden(), september, september)
        assertTrue(d.directCost is Figure.Incomplete && d.grossProfit is Figure.Incomplete && d.netProfit is Figure.Incomplete)
        assertTrue(d.missingInputs.contains("Oil price"))
        assertTrue(d.netSalesCents > 0)
    }

    // --- costing breakdown (owner change 4) ---

    @Test fun costPerPacketIsBrokenDownByMaterialWastagePackingDirectIndirectAndFull() {
        val s = fullyPriced().copy(
            expenses = listOf(Expense("e1", SeedIds.CAT_LABOUR, "Labour", ExpenseKind.INDIRECT, LocalDate.of(2026, 9, 28), 30_000, "", "x")),
        )
        val fresh = ServerLogic.costing(s, september).products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        // The Doc 1 s9.5 table: $0.4125 + $0.1200 + $0.0075 + $0.0045 + $0.0030 = $0.5475 of ingredients.
        assertEquals(
            listOf(4_125L, 1_200L, 75L, 45L, 30L),
            fresh.lines.map { it.tt },
        )
        assertEquals(listOf("Whole wheat flour", "Oil", "Sugar", "Salt", "Baking powder"), fresh.lines.map { it.materialName })
        assertEquals(1_500L, fresh.packingTt) // $0.15
        // 2% wastage on the ingredients only, not on packing: 5,475 x 2% = 109.5 -> 110.
        assertEquals(110L, fresh.wastageTt)
        assertEquals(5_475L + 110L + 1_500L, fresh.directTt)
        // Before wastage the Doc 1 s9.5 vectors still hold: $0.5475 ingredients, $0.6975 with packing.
        assertEquals(5_475L, fresh.lines.sumOf { it.tt })
        assertEquals(6_975L, fresh.lines.sumOf { it.tt } + fresh.packingTt)
        // $300 over 1,000 net packets = $0.30.
        assertEquals(3_000L, fresh.indirectTt)
        assertEquals(fresh.directTt + fresh.indirectTt, fresh.fullTt)
        assertEquals(fresh.lines.sumOf { it.tt } + fresh.packingTt + fresh.wastageTt, fresh.directTt)
    }

    @Test fun costingNamesWhatIsMissing() {
        val chapathi = ServerLogic.costing(golden(), september).products.first { it.productId == SeedIds.CHAPATHI } as ProductCost.Incomplete
        assertTrue(chapathi.missing.contains("Potassium sorbate quantity per packet (Doc 1 P-2)"))
        assertTrue(chapathi.missing.contains("Oil price"))
        assertTrue(chapathi.missing.contains("Potassium sorbate price"))
    }

    @Test fun theWastageShownInCostingIsTheSettingAndNothingElse() {
        assertEquals(200, ServerLogic.costing(golden(), september).wastageBp)
        assertEquals(350, ServerLogic.costing(golden().copy(wastageBp = 350), september).wastageBp)
    }

    @Test fun packingHasNoWastageEvenAtFivePercent() {
        val s = fullyPriced().copy(wastageBp = 500)
        assertEquals(Figure.Known(1_000_000L), row(ServerLogic.stock(s, september), SeedIds.PACKING).usedMb)
        val fresh = ServerLogic.costing(s, september).products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        assertEquals(1_500L, fresh.packingTt)
        assertEquals(274L, fresh.wastageTt) // 5,475 x 5% = 273.75
    }

    private fun divHalfUp(a: Long, b: Long) = ServerLogic.divHalfUp(a, b)
}
