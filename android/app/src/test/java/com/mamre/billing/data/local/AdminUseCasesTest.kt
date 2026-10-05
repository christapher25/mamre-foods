package com.mamre.billing.data.local

import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.usecase.RuleException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Admin write use cases and their rules (Doc 1 s4.3, s9.4, s10.2, A-20; Doc 2 s9). */
@RunWith(RobolectricTestRunner::class)
class AdminUseCasesTest {
    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    private suspend fun world() = World(TestDatabase.inMemory()).also { it.seed() }

    // ---------------------------------------------------------------- prices (Doc 1 s4.3)

    @Test fun aNewPriceNeedsALaterEffectiveDateAndAnAmountAboveZero() = runBlocking {
        val w = world()
        val d = LocalDate.of(2026, 3, 1)
        w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_SHOP, 250, d)
        assertTrue(refused { w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_SHOP, 260, d) }.contains("later"))
        assertTrue(refused { w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_SHOP, 260, d.minusDays(1)) }.contains("later"))
        assertTrue(refused { w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_SHOP, 0, d.plusDays(1)) }.contains("above"))
        assertTrue(refused { w.setDefaultPrice("nope", ReferenceIds.TYPE_SHOP, 260, d.plusDays(1)) }.isNotEmpty())
        w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_SHOP, 260, d.plusDays(1))
        // Another product or type has its own history, so the same date is fine there.
        w.setDefaultPrice(ReferenceIds.PRODUCT_CHAPATHI, ReferenceIds.TYPE_SHOP, 280, d)
        assertEquals(3, w.prices.defaults().size)
    }

    @Test fun anOverrideNeedsALaterDateThanTheCurrentOneAndClearingKeepsTheRows() = runBlocking {
        val w = world()
        val c = w.customer("Spice Garden", "Irving")
        val d = LocalDate.of(2026, 3, 1)
        assertTrue(refused { w.clearOverride(c.id, ReferenceIds.PRODUCT_FRESH) }.contains("no override"))
        w.setOverride(c.id, ReferenceIds.PRODUCT_FRESH, 200, d, "note")
        assertTrue(refused { w.setOverride(c.id, ReferenceIds.PRODUCT_FRESH, 190, d, "") }.contains("later"))
        w.setOverride(c.id, ReferenceIds.PRODUCT_FRESH, 190, d.plusDays(10), "")
        w.clearOverride(c.id, ReferenceIds.PRODUCT_FRESH)
        val rows = w.prices.overridesOf(c.id)
        assertEquals(2, rows.size) // history is kept, never deleted
        assertTrue(rows.none { it.isActive })
    }

    @Test fun theSwitchChangesAreLoggedAndASwitchToTheSameValueIsRefused() = runBlocking {
        val w = world()
        assertTrue(refused { w.setCanEditPrice(ReferenceIds.TYPE_RETAIL, true) }.contains("already"))
        assertTrue(refused { w.setCanEditPrice("nope", true) }.isNotEmpty())
        w.setCanEditPrice(ReferenceIds.TYPE_RESTAURANT, true)
        w.setCanEditPrice(ReferenceIds.TYPE_RESTAURANT, false)
        val log = w.changeLog.all().filter { it.what == "Price rule Restaurant" }
        assertEquals(listOf("salesman cannot edit price" to "salesman can edit price", "salesman can edit price" to "salesman cannot edit price"), log.map { it.beforeText to it.afterText })
    }

    // ---------------------------------------------------------------- purchases, expenses, damage, stock

    @Test fun aPurchaseNeedsAQuantityAndATotalAboveZero() = runBlocking {
        val w = world()
        assertTrue(refused { w.addPurchase(ReferenceIds.MATERIAL_WHEAT, w.today, 0, 100, "") }.contains("quantity"))
        assertTrue(refused { w.addPurchase(ReferenceIds.MATERIAL_WHEAT, w.today, 1000, 0, "") }.contains("total"))
        assertTrue(refused { w.addPurchase("nope", w.today, 1000, 100, "") }.isNotEmpty())
        assertEquals(0, w.stock.purchases().size)
    }

    @Test fun anExpenseNeedsAKnownCategoryAndAnAmountAboveZero() = runBlocking {
        val w = world()
        assertTrue(refused { w.addExpense(ReferenceIds.CATEGORY_FUEL, w.today, 0, "") }.isNotEmpty())
        assertTrue(refused { w.addExpense("nope", w.today, 100, "") }.isNotEmpty())
        assertTrue(refused { w.addExpense(ReferenceIds.CATEGORY_FUEL, w.today, -100, "") }.isNotEmpty())
        assertEquals(0, w.expenses.expenses().size)
        w.addExpense(ReferenceIds.CATEGORY_FUEL, w.today, 2_500, "diesel")
        assertEquals(2_500L, w.expenses.expenses().single().amountCents)
    }

    @Test fun productionDamageIsAddOnlyAndCountsInMaterialUsageButNotPacking() = runBlocking {
        val w = world()
        assertTrue(refused { w.addDamage(ReferenceIds.PRODUCT_FRESH, w.today, 0, "") }.contains("more than zero"))
        assertTrue(refused { w.addDamage("nope", w.today, 5, "") }.isNotEmpty())
        w.addDamage(ReferenceIds.PRODUCT_FRESH, w.today, 320, "burnt")
        assertEquals(320, w.stock.damage().single().chapathis)
    }

    @Test fun openingStockIsSetOncePerMaterial() = runBlocking {
        val w = world()
        w.setOpeningStock(ReferenceIds.MATERIAL_WHEAT, YearMonth.of(2026, 4), 100_000_000, 10_000)
        assertTrue(refused { w.setOpeningStock(ReferenceIds.MATERIAL_WHEAT, YearMonth.of(2026, 4), 1, 1) }.contains("already set"))
        assertTrue(refused { w.setOpeningStock(ReferenceIds.MATERIAL_OIL, YearMonth.of(2026, 4), -1, 1) }.contains("negative"))
        assertEquals(1, w.stock.openingStock().size)
    }

    // ---------------------------------------------------------------- recipe, wastage, sizes, settings

    @Test fun theRecipeWastagePacketSizeAndYieldHaveLimits() = runBlocking {
        val w = world()
        assertTrue(refused { w.setRecipe(ReferenceIds.PRODUCT_FRESH, ReferenceIds.MATERIAL_SORBATE, 1000) }.contains("not in the recipe"))
        assertTrue(refused { w.setRecipe(ReferenceIds.PRODUCT_CHAPATHI, ReferenceIds.MATERIAL_SORBATE, 0) }.contains("more than zero"))
        w.setRecipe(ReferenceIds.PRODUCT_CHAPATHI, ReferenceIds.MATERIAL_SORBATE, 500)
        assertEquals(500L, w.stock.recipe().single { it.productId == ReferenceIds.PRODUCT_CHAPATHI && it.materialId == ReferenceIds.MATERIAL_SORBATE }.qtyMilliPerKgWheat)
        // Wastage 0 to 5% (500 basis points).
        assertTrue(refused { w.setWastage(501) }.contains("between"))
        assertTrue(refused { w.setWastage(-1) }.contains("between"))
        w.setWastage(250)
        assertEquals(250, w.settings.wastageBp())
        // Packet size 1 to 200 and different from now.
        assertTrue(refused { w.setPacketSize(ReferenceIds.PRODUCT_FRESH, 0) }.contains("1 to 200"))
        assertTrue(refused { w.setPacketSize(ReferenceIds.PRODUCT_FRESH, 201) }.contains("1 to 200"))
        assertTrue(refused { w.setPacketSize(ReferenceIds.PRODUCT_FRESH, 12) }.contains("already"))
        w.setPacketSize(ReferenceIds.PRODUCT_FRESH, 10)
        assertEquals(10, w.stock.product(ReferenceIds.PRODUCT_FRESH)!!.standardPacketSize)
        assertTrue(refused { w.setYield(ReferenceIds.PRODUCT_FRESH, 0) }.contains("yield"))
        assertTrue(refused { w.setYield(ReferenceIds.PRODUCT_FRESH, 201) }.contains("yield"))
        w.setYield(ReferenceIds.PRODUCT_FRESH, 30)
        assertEquals(30, w.stock.product(ReferenceIds.PRODUCT_FRESH)!!.yieldPerKg)
        // One change-log entry for each of the four successful changes; the refused calls wrote nothing.
        assertEquals(4, w.changeLog.all().size)
    }

    @Test fun theBusinessNeedsANameAndTheChangeIsLogged() = runBlocking {
        val w = world()
        assertTrue(refused { w.saveSettings(BusinessSettings("  ", "a", "b", "c")) }.contains("name"))
        assertEquals("Mamre Foods", w.settings.get(SettingKeys.BUSINESS_NAME))
        w.saveSettings(BusinessSettings(" Mamre Foods ", "line1\nline2", "555", "Thanks"))
        assertEquals("line1\nline2", w.settings.get(SettingKeys.ADDRESS))
        assertEquals(1, w.changeLog.all().count { it.what == "Edit business settings" })
        assertNull(w.settings.get(SettingKeys.PIN_HASH)) // the PIN is not touched here
    }
}
