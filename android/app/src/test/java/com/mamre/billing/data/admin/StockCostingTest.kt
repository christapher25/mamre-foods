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

    /** An invoice of [packets] packets of [size] chapathis each (a standard packet holds 6), at 100 cents a packet. */
    private fun invoice(packets: Int, product: String = SeedIds.FRESH, day: LocalDate = LocalDate.of(2026, 9, 15), n: Int = 1, size: Int = 6) =
        AdminInvoice(
            "t-inv-$n", "MAM-W1-9$n", base.customers.first().id, base.customers.first().name, "Restaurant", "W1",
            LocalDateTime.of(day, java.time.LocalTime.NOON),
            listOf(InvoiceItem(product, base.products.first { it.id == product }.name, packets, 100, packets * 100L, size, 100, size != 6)),
            packets * 100L, InvoiceStatus.ACTIVE,
        )

    private fun purchase(material: String, qtyMb: Long, cents: Long, day: LocalDate = LocalDate.of(2026, 9, 2), n: Int = 1) =
        Purchase("t-pu-$material-$n", day, material, material, qtyMb, cents, "", "Test Admin")

    /** One invoice, one purchase of wheat, nothing else: the owner's golden case. */
    private fun golden() = base.copy(
        customers = base.customers.take(1),
        invoices = listOf(invoice(2_000)),
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
        null, SeedIds.FRESH, "Mamre Fresh Chapathi", packets, 6, ReturnReason.DAMAGED, resolution, 100,
        if (resolution == ReturnResolution.CREDIT) packets * 100L else 0L,
    )

    // --- the owner's golden case ---

    @Test fun goldenCaseTwelveThousandChapathisWheatTwoPercentWastage() {
        val wheat = row(ServerLogic.stock(golden(), september), SeedIds.WHEAT)
        // 2,000 packets of 6 = 12,000 chapathis x 1 kg per 32 chapathis = 375 kg; x (1 + 2%) = 382.5 kg used.
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
        assertEquals(2_000 * 100L - 100 * 100L, ServerLogic.netSalesCents(withCredit, september))
    }

    @Test fun usageAlsoCountsReplacementPacketsAndProductionDamage() {
        val s = golden().copy(
            returns = listOf(returnOf(ReturnResolution.REPLACEMENT, 10)),
            damage = listOf(DamageRow("d1", LocalDate.of(2026, 9, 20), SeedIds.FRESH, 120, "Burnt", "Test Admin")),
        )
        val wheat = row(ServerLogic.stock(s, september), SeedIds.WHEAT)
        // 12,000 invoiced + 10 replacement packets x 6 = 60 + 120 damaged chapathis = 12,180 chapathis;
        // 12,180 / 32 = 380.625 kg; x 1.02 = 388.2375 kg, damage counted in chapathis.
        assertEquals(Figure.Known(388_237_500L), wheat.usedMb)
    }

    @Test fun packingCountsInvoicedAndReplacementPacketsButNotProductionDamage() {
        val s = golden().copy(
            returns = listOf(returnOf(ReturnResolution.REPLACEMENT, 10)),
            damage = listOf(DamageRow("d1", LocalDate.of(2026, 9, 20), SeedIds.FRESH, 120, "Burnt", "Test Admin")),
        )
        val report = ServerLogic.stock(s, september)
        // Damaged chapathis are spoiled before packing: materials yes, packing no.
        assertEquals(Figure.Known(2_010_000L), row(report, SeedIds.PACKING).usedMb) // 2,000 invoiced + 10 replacement packets, one piece each
        assertEquals(Figure.Known(388_237_500L), row(report, SeedIds.WHEAT).usedMb) // the damaged chapathis still used wheat
    }

    @Test fun aVoidInvoiceUsesNothing() {
        val s = golden().copy(invoices = listOf(invoice(2_000).copy(status = InvoiceStatus.VOID, voidReason = "x")))
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
        assertEquals(Figure.Known(30_600_000L), oil.usedMb) // 12,000 chapathis x 80 ml per 32 x 1.02 = 30.6 L is still known
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
        val withChapathi = golden().copy(invoices = listOf(invoice(2_000), invoice(50, SeedIds.CHAPATHI, n = 2)))
        val sorbate = row(ServerLogic.stock(withChapathi, september), SeedIds.SORBATE)
        val missing = listOf("Potassium sorbate quantity per kg of wheat for Mamre Chapathi (Doc 1 P-2)")
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
        // 50 packets of 6 = 300 chapathis x 0.5 g per 32 = 4.6875 g; x 1.02 = 4.78125 g -> 4,781 thousandths, rounded once.
        assertEquals(Figure.Known(4_781L), sorbate.usedMb)
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
        // Packing has no wastage: one piece per packet, 2,000 pieces for 2,000 packets, at 15 cents.
        assertEquals(Figure.Known(2_000_000L), row(report, SeedIds.PACKING).usedMb)
        assertEquals(Figure.Known(30_000L), row(report, SeedIds.PACKING).costConsumedCents)
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

    private fun costedAt(s: ServerState, product: String, chapathis: Int): ProductCost.Complete =
        ServerLogic.packetCost(s, september, product, chapathis) as ProductCost.Complete

    @Test fun costPerStandardPacketIsBrokenDownByMaterialWastagePackingDirectIndirectAndFull() {
        val s = fullyPriced().copy(
            expenses = listOf(Expense("e1", SeedIds.CAT_LABOUR, "Labour", ExpenseKind.INDIRECT, LocalDate.of(2026, 9, 28), 30_000, "", "x")),
        )
        val fresh = ServerLogic.costing(s, september).products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        assertEquals(6, fresh.chapathis) // the standard packet
        // A 6-chapathi packet is half of the Doc 1 s9.5 packet of 12; each line is rounded half up on its own.
        assertEquals(listOf(2_063L, 600L, 38L, 23L, 15L), fresh.lines.map { it.tt })
        assertEquals(listOf("Whole wheat flour", "Oil", "Sugar", "Salt", "Baking powder"), fresh.lines.map { it.materialName })
        // The ingredients are rounded ONCE from the exact sum: 0.27375 -> $0.2738.
        assertEquals(2_738L, fresh.ingredientsTt)
        assertEquals(1_500L, fresh.packingTt) // $0.15, one piece per packet
        // 2% wastage on the ingredients only, not on packing: 2,738 x 2% = 54.76 -> 55.
        assertEquals(55L, fresh.wastageTt)
        assertEquals(2_738L + 55L + 1_500L, fresh.directTt)
        // $300 over 12,000 net chapathis = $0.025 each, so 6 chapathis carry $0.15.
        assertEquals(1_500L, fresh.indirectTt)
        assertEquals(fresh.directTt + fresh.indirectTt, fresh.fullTt)
    }

    @Test fun aTwelveChapathiPacketAtTheDoc1Section95PricesStillGivesTheOldVectors() {
        val s = fullyPriced().copy(wastageBp = 0)
        val twelve = costedAt(s, SeedIds.FRESH, 12)
        assertEquals(listOf(4_125L, 1_200L, 75L, 45L, 30L), twelve.lines.map { it.tt })
        assertEquals(5_475L, twelve.ingredientsTt) // $0.5475
        assertEquals(6_975L, twelve.ingredientsTt + twelve.packingTt) // $0.6975
        assertEquals(6_975L, twelve.directTt)
    }

    @Test fun aSixChapathiPacketGivesAboutTwentySevenCentsOfIngredientsAndFortyTwoCentsDirect() {
        val six = costedAt(fullyPriced().copy(wastageBp = 0), SeedIds.FRESH, 6)
        assertEquals(2_738L, six.ingredientsTt) // $0.2738
        assertEquals(4_238L, six.directTt) // $0.4238 = ingredients + one packing piece
    }

    @Test fun aCustomPacketCostsPerChapathiTimesNPlusOnePackingPiece() {
        val s = fullyPriced().copy(wastageBp = 0)
        val ten = costedAt(s, SeedIds.FRESH, 10)
        assertEquals(4_563L, ten.ingredientsTt) // 10 x 456.25 = 4,562.5 -> half up
        assertEquals(1_500L, ten.packingTt) // packing does not grow with the packet
        assertEquals(4_563L + 1_500L, ten.directTt)
        val one = costedAt(s, SeedIds.FRESH, 1)
        assertEquals(456L, one.ingredientsTt) // 456.25
        assertEquals(456L, one.perChapathiTt)
    }

    @Test fun costPerChapathiIncludesWastage() {
        val one = ServerLogic.costing(fullyPriced(), september).products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        assertEquals(456L + 9L, one.perChapathiTt) // 456 + 2% of 456 = 9.12 -> 9
    }

    @Test fun costingNamesWhatIsMissing() {
        val chapathi = ServerLogic.costing(golden(), september).products.first { it.productId == SeedIds.CHAPATHI } as ProductCost.Incomplete
        assertTrue(chapathi.missing.contains("Potassium sorbate quantity per kg of wheat (Doc 1 P-2)"))
        assertTrue(chapathi.missing.contains("Oil price"))
        assertTrue(chapathi.missing.contains("Potassium sorbate price"))
    }

    @Test fun theWastageShownInCostingIsTheSettingAndNothingElse() {
        assertEquals(200, ServerLogic.costing(golden(), september).wastageBp)
        assertEquals(350, ServerLogic.costing(golden().copy(wastageBp = 350), september).wastageBp)
    }

    @Test fun packingHasNoWastageEvenAtFivePercent() {
        val s = fullyPriced().copy(wastageBp = 500)
        assertEquals(Figure.Known(2_000_000L), row(ServerLogic.stock(s, september), SeedIds.PACKING).usedMb)
        val fresh = ServerLogic.costing(s, september).products.first { it.productId == SeedIds.FRESH } as ProductCost.Complete
        assertEquals(1_500L, fresh.packingTt)
        assertEquals(137L, fresh.wastageTt) // 2,738 x 5% = 136.9
    }

    // --- usage in chapathis (change set C2) ---

    @Test fun mixedPacketSizesAreCountedInChapathis() {
        // 10 packets of 6 and 5 packets of 10 = 110 chapathis.
        val s = golden().copy(invoices = listOf(invoice(10, n = 1), invoice(5, n = 2, size = 10)))
        val wheat = row(ServerLogic.stock(s, september), SeedIds.WHEAT)
        assertEquals(Figure.Known(110L * 1_000_000 * 102 / 100 / 32), wheat.usedMb) // 3.5062... kg, rounded once
        // Packing is one piece per PACKET: 15 packets, whatever their size.
        assertEquals(Figure.Known(15_000L), row(ServerLogic.stock(s, september), SeedIds.PACKING).usedMb)
    }

    @Test fun theYieldPerKgChangesTheUsage() {
        val products = golden().products.map { if (it.id == SeedIds.FRESH) it.copy(yieldPerKg = 40) else it }
        val wheat = row(ServerLogic.stock(golden().copy(products = products), september), SeedIds.WHEAT)
        assertEquals(Figure.Known(306_000_000L), wheat.usedMb) // 12,000 / 40 = 300 kg x 1.02
    }

    @Test fun twoProductsWithDifferentYieldsAreRoundedOnceTogether() {
        val products = golden().products.map { if (it.id == SeedIds.CHAPATHI) it.copy(yieldPerKg = 40) else it }
        // 500 chapathis of each: 500/32 + 500/40 = 15.625 + 12.5 = 28.125 kg; x 1.02 = 28.6875 kg.
        val s = golden().copy(
            products = products,
            invoices = listOf(invoice(5, SeedIds.FRESH, n = 1, size = 100), invoice(5, SeedIds.CHAPATHI, n = 2, size = 100)),
        )
        assertEquals(Figure.Known(28_687_500L), row(ServerLogic.stock(s, september), SeedIds.WHEAT).usedMb)
    }

    @Test fun theIndirectShareIsSpreadOverNetChapathisSoPacketsOfAnySizeCarryTheirShare() {
        val s = fullyPriced().copy(
            expenses = listOf(Expense("e1", SeedIds.CAT_LABOUR, "Labour", ExpenseKind.INDIRECT, LocalDate.of(2026, 9, 28), 12_000, "", "x")),
            returns = listOf(returnOf(ReturnResolution.CREDIT, 100)), // 600 chapathis credited: net 11,400
        )
        val report = ServerLogic.costing(s, september)
        assertEquals(11_400L, report.netChapathis)
        val six = costedAt(s, SeedIds.FRESH, 6)
        val twelve = costedAt(s, SeedIds.FRESH, 12)
        assertEquals(ServerLogic.divHalfUp(12_000L * 100 * 6, 11_400), six.indirectTt)
        assertEquals(ServerLogic.divHalfUp(12_000L * 100 * 12, 11_400), twelve.indirectTt)
    }

    private fun divHalfUp(a: Long, b: Long) = ServerLogic.divHalfUp(a, b)
}
