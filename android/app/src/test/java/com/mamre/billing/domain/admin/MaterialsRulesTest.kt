package com.mamre.billing.domain.admin

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialsRulesTest {
    private val kg = MaterialUnit("kg", 1_000_000)
    private val g = MaterialUnit("g", 1_000)
    private val wheat = Material("i-wheat", "Whole wheat flour", "g", "kg", false, listOf(g, kg))
    private val packing = Material("i-pack", "Packing", "piece", "piece", true, listOf(MaterialUnit("piece", 1_000)))
    private val day = LocalDate.of(2026, 9, 5)

    // --- add purchase (B6) ---

    private fun purchase(
        material: String? = "i-wheat",
        date: LocalDate? = day,
        qty: String = "400",
        unit: MaterialUnit? = kg,
        total: String = "440.00",
    ) = validatePurchase(material, date, qty, unit, total)

    @Test fun aPurchaseIsConvertedToThousandthsOfTheBaseUnitAndCents() {
        val ok = purchase() as PurchaseCheck.Ok
        assertEquals(400_000_000L, ok.qtyMb) // 400 kg = 400,000 g = 400,000,000 thousandths
        assertEquals(44_000L, ok.totalCents)
    }

    @Test fun theUnitChangesTheQuantityNotTheMeaning() {
        assertEquals(2_500L, (purchase(qty = "2.5", unit = g) as PurchaseCheck.Ok).qtyMb)
        assertEquals(17_500_000L, (purchase(qty = "17.5", unit = kg) as PurchaseCheck.Ok).qtyMb)
        assertEquals(5_000_000L, (purchase(qty = "5000", unit = MaterialUnit("piece", 1_000)) as PurchaseCheck.Ok).qtyMb)
    }

    @Test fun everyPartOfAPurchaseIsRequired() {
        fun problems(c: PurchaseCheck) = (c as PurchaseCheck.Invalid).problems
        assertEquals(setOf(PurchaseProblem.MATERIAL_REQUIRED), problems(purchase(material = null)))
        assertEquals(setOf(PurchaseProblem.DATE_REQUIRED), problems(purchase(date = null)))
        assertEquals(setOf(PurchaseProblem.UNIT_REQUIRED), problems(purchase(unit = null)))
        assertEquals(setOf(PurchaseProblem.QUANTITY_INVALID), problems(purchase(qty = "abc")))
        assertEquals(setOf(PurchaseProblem.QUANTITY_NOT_POSITIVE), problems(purchase(qty = "0")))
        assertEquals(setOf(PurchaseProblem.TOTAL_INVALID), problems(purchase(total = "")))
        assertEquals(setOf(PurchaseProblem.TOTAL_NOT_POSITIVE), problems(purchase(total = "0")))
        assertEquals(
            setOf(PurchaseProblem.MATERIAL_REQUIRED, PurchaseProblem.QUANTITY_NOT_POSITIVE),
            problems(purchase(material = null, qty = "-3")),
        )
    }

    @Test fun bagsTimesKgPerBagGivesTheQuantity() {
        // 16 bags of 25 kg = 400 kg; kg per bag may have decimals (12.5 kg).
        assertEquals("400", bagsTimes("16", "25"))
        assertEquals("200", bagsTimes("16", "12.5"))
        assertEquals("0.375", bagsTimes("3", "0.125"))
        assertNull(bagsTimes("", "25"))
        assertNull(bagsTimes("2.5", "25")) // whole bags only
        assertNull(bagsTimes("0", "25"))
        assertNull(bagsTimes("3", "x"))
        assertNull(bagsTimes("3", "0"))
    }

    // --- recipe quantity (B5) ---

    @Test fun aRecipeQuantityIsInThousandthsOfTheBaseUnitAndAboveZero() {
        assertEquals(375_000L, (validateRecipeQuantity("375") as RecipeCheck.Ok).qtyMb)
        assertEquals(7_500L, (validateRecipeQuantity("7.5") as RecipeCheck.Ok).qtyMb)
        assertEquals(5_625L, (validateRecipeQuantity("5.625") as RecipeCheck.Ok).qtyMb)
        assertEquals(RecipeProblem.NOT_POSITIVE, (validateRecipeQuantity("0") as RecipeCheck.Invalid).problem)
        assertEquals(RecipeProblem.NOT_POSITIVE, (validateRecipeQuantity("-2") as RecipeCheck.Invalid).problem)
        assertEquals(RecipeProblem.INVALID, (validateRecipeQuantity("lots") as RecipeCheck.Invalid).problem)
        assertEquals(RecipeProblem.INVALID, (validateRecipeQuantity("1.2345") as RecipeCheck.Invalid).problem)
    }

    // --- wastage 0 to 5% (B9, B5) ---

    @Test fun wastageIsInBasisPointsFromZeroToFivePercent() {
        assertEquals(200, (validateWastage("2") as WastageCheck.Ok).basisPoints)
        assertEquals(250, (validateWastage("2.5") as WastageCheck.Ok).basisPoints)
        assertEquals(0, (validateWastage("0") as WastageCheck.Ok).basisPoints)
        assertEquals(500, (validateWastage("5") as WastageCheck.Ok).basisPoints)
        assertEquals(125, (validateWastage("1.25") as WastageCheck.Ok).basisPoints)
        assertEquals(WastageProblem.OUT_OF_RANGE, (validateWastage("5.01") as WastageCheck.Invalid).problem)
        assertEquals(WastageProblem.OUT_OF_RANGE, (validateWastage("-1") as WastageCheck.Invalid).problem)
        assertEquals(WastageProblem.OUT_OF_RANGE, (validateWastage("6") as WastageCheck.Invalid).problem)
        assertEquals(WastageProblem.INVALID, (validateWastage("") as WastageCheck.Invalid).problem)
        assertEquals(WastageProblem.INVALID, (validateWastage("two") as WastageCheck.Invalid).problem)
        assertEquals(WastageProblem.INVALID, (validateWastage("1.234") as WastageCheck.Invalid).problem)
    }

    @Test fun wastageIsShownAsAPercent() {
        assertEquals("2", formatWastage(200))
        assertEquals("2.5", formatWastage(250))
        assertEquals("1.25", formatWastage(125))
        assertEquals("0", formatWastage(0))
        assertEquals("5", formatWastage(500))
    }

    // --- production damage (B7) ---

    @Test fun productionDamageNeedsAProductADateAndWholeChapathisAboveZero() {
        fun check(product: String? = "p", date: LocalDate? = day, packets: String = "12") =
            validateProductionDamage(product, date, packets)
        assertEquals(12, (check() as DamageCheck.Ok).chapathis)
        fun problems(c: DamageCheck) = (c as DamageCheck.Invalid).problems
        assertEquals(setOf(DamageProblem.PRODUCT_REQUIRED), problems(check(product = null)))
        assertEquals(setOf(DamageProblem.DATE_REQUIRED), problems(check(date = null)))
        assertEquals(setOf(DamageProblem.CHAPATHIS_INVALID), problems(check(packets = "1.5")))
        assertEquals(setOf(DamageProblem.CHAPATHIS_INVALID), problems(check(packets = "")))
        assertEquals(setOf(DamageProblem.CHAPATHIS_NOT_POSITIVE), problems(check(packets = "0")))
        assertEquals(setOf(DamageProblem.CHAPATHIS_NOT_POSITIVE), problems(check(packets = "-4")))
    }

    // --- showing quantities ---

    @Test fun quantitiesAreShownInThePurchaseUnit() {
        assertEquals("17.5 kg", formatQuantity(17_500_000, wheat))
        assertEquals("382.5 kg", formatQuantity(382_500_000, wheat))
        assertEquals("-20 kg", formatQuantity(-20_000_000, wheat))
        assertEquals("0.375 kg", formatQuantity(375_000, wheat))
        assertEquals("0 kg", formatQuantity(0, wheat))
        assertEquals("5000 piece", formatQuantity(5_000_000, packing))
        assertEquals("0.001 kg", formatQuantity(1_000, wheat))
    }

    @Test fun recipeQuantitiesAreShownInTheBaseUnit() {
        assertEquals("375 g", formatRecipeQuantity(375_000, "g"))
        assertEquals("5.625 g", formatRecipeQuantity(5_625, "g"))
        assertEquals("1 piece", formatRecipeQuantity(1_000, "piece"))
    }

    // --- date picker ---

    @Test fun thePickerDateRoundTripsInUtc() {
        val d = LocalDate.of(2026, 10, 3)
        assertEquals(d, pickerMillisToDate(dateToPickerMillis(d)))
        assertEquals(LocalDate.of(1970, 1, 1), pickerMillisToDate(0))
        assertTrue(dateToPickerMillis(d) > dateToPickerMillis(d.minusDays(1)))
    }
}
