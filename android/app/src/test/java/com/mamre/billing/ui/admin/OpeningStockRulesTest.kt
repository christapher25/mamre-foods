package com.mamre.billing.ui.admin

import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.MaterialUnit
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B3, Doc 1 s9.4: the opening stock form's input parsing, pure. Zero is allowed; negative and unreadable values are refused. */
class OpeningStockRulesTest {
    private val kg = MaterialUnit("kg", 1_000_000L)
    private val g = MaterialUnit("g", 1_000L)
    private val month = YearMonth.of(2026, 10)

    private fun check(
        materialId: String? = "wheat",
        m: YearMonth? = month,
        qty: String = "400",
        unit: MaterialUnit? = kg,
        value: String = "440.00",
    ) = validateOpeningStock(materialId, m, qty, unit, value)

    private fun ok(c: OpeningStockCheck) = c as OpeningStockCheck.Ok

    private fun problems(c: OpeningStockCheck) = (c as OpeningStockCheck.Invalid).problems

    @Test fun theQuantityIsConvertedWithThePurchaseUnitFactorToMilliUnits() {
        // 400 kg of wheat is 400,000,000 thousandths of a gram; 12.5 kg is 12,500,000; 250 g is 250,000.
        assertEquals(400_000_000L, ok(check(qty = "400", unit = kg)).qtyMb)
        assertEquals(12_500_000L, ok(check(qty = "12.5", unit = kg)).qtyMb)
        assertEquals(250_000L, ok(check(qty = "250", unit = g)).qtyMb)
        assertEquals(1L, ok(check(qty = "0.001", unit = g)).qtyMb)
    }

    @Test fun theValueInDollarsBecomesCents() {
        assertEquals(44_000L, ok(check(value = "440.00")).valueCents)
        assertEquals(44_050L, ok(check(value = "440.5")).valueCents)
        assertEquals(1L, ok(check(value = "0.01")).valueCents)
    }

    @Test fun zeroIsAllowedForTheQuantityAndTheValueABothSidesOfZeroAreChecked() {
        val zero = ok(check(qty = "0", value = "0"))
        assertEquals(0L, zero.qtyMb)
        assertEquals(0L, zero.valueCents)
        assertEquals(setOf(OpeningStockProblem.QUANTITY_NEGATIVE), problems(check(qty = "-0.001")))
        assertEquals(setOf(OpeningStockProblem.VALUE_NEGATIVE), problems(check(value = "-0.01")))
        assertEquals(1L, ok(check(qty = "0.001", unit = g)).qtyMb)
    }

    @Test fun anUnreadableQuantityOrValueIsRefusedWithAPlainProblem() {
        assertEquals(setOf(OpeningStockProblem.QUANTITY_INVALID), problems(check(qty = "")))
        assertEquals(setOf(OpeningStockProblem.QUANTITY_INVALID), problems(check(qty = "abc")))
        assertEquals(setOf(OpeningStockProblem.QUANTITY_INVALID), problems(check(qty = "1.2.3")))
        assertEquals(setOf(OpeningStockProblem.VALUE_INVALID), problems(check(value = "")))
        assertEquals(setOf(OpeningStockProblem.VALUE_INVALID), problems(check(value = "$5")))
    }

    @Test fun theMaterialTheMonthAndTheUnitAreRequired() {
        assertEquals(setOf(OpeningStockProblem.MATERIAL_REQUIRED), problems(check(materialId = null)))
        assertEquals(setOf(OpeningStockProblem.MONTH_REQUIRED), problems(check(m = null)))
        assertEquals(setOf(OpeningStockProblem.UNIT_REQUIRED), problems(check(unit = null)))
    }

    @Test fun everyProblemHasAPlainMessage() {
        for (p in OpeningStockProblem.entries) assertTrue(openingStockProblemMessage(p).isNotBlank())
    }

    @Test fun aSavedRowIsShownAsQuantityValueAndMonthInThePurchaseUnit() {
        val wheat = Material("wheat", "Wheat", "g", "kg", false, listOf(MaterialUnit("g", 1_000L), kg))
        assertEquals("400 kg, \$440.00, October 2026", openingStockLine(wheat, 400_000_000L, 44_000L, month))
        assertEquals("0 kg, \$0.00, October 2026", openingStockLine(wheat, 0L, 0L, month))
    }
}
