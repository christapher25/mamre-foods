package com.mamre.billing.data.local

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** AT-21 (Doc 2 s5.2): a first-run database holds reference data only: no customers, prices or bills. */
@RunWith(RobolectricTestRunner::class)
class FirstRunSeedTest {
    private val file = "first-run-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private fun seeded(db: MamreDatabase) = runBlocking { ReferenceSeed(db, RoomUnitOfWork(db)).run() }

    @Test fun firstRunCreatesTheReferenceDataOnly() = runBlocking {
        val db = TestDatabase.inMemory()
        seeded(db)

        assertEquals(listOf("Catering", "Restaurant", "Retail", "Shop"), db.customerTypeDao().getAll().map { it.name })
        assertEquals(
            mapOf("Restaurant" to false, "Shop" to false, "Retail" to true, "Catering" to true),
            db.customerTypeDao().getAll().associate { it.name to it.salesmanCanEditPrice },
        )
        val products = db.productDao().getAll()
        assertEquals(setOf("Mamre Fresh Chapathi", "Mamre Chapathi"), products.map { it.name }.toSet())
        assertTrue(products.all { it.standardPacketSize == 12 && it.yieldPerKg == 32 && it.isActive })

        val materials = db.materialDao().getAll()
        assertEquals(
            listOf("Whole wheat flour", "Oil", "Sugar", "Salt", "Baking powder", "Potassium sorbate", "Packing"),
            materials.map { it.name },
        )
        assertEquals(listOf("Packing"), materials.filter { it.isPacking }.map { it.name })

        assertEquals(8, db.expenseCategoryDao().getAll().size)
        assertTrue(db.expenseCategoryDao().getAll().all { it.kind == Stored.INDIRECT })

        db.close()
    }

    @Test fun theRecipeIsPerKilogramOfWheatAndSorbateIsUnsetOnMamreChapathiOnly() = runBlocking {
        val db = TestDatabase.inMemory()
        seeded(db)
        val recipe = db.recipeDao().getAll()
        fun qty(product: String, material: String) =
            recipe.single { it.productId == product && it.materialId == material }.qtyMilliPerKgWheat

        for (p in listOf(ReferenceIds.PRODUCT_FRESH, ReferenceIds.PRODUCT_CHAPATHI)) {
            assertEquals(1_000_000L, qty(p, ReferenceIds.MATERIAL_WHEAT)) // 1 kg = 1,000 g in milli-units
            assertEquals(80_000L, qty(p, ReferenceIds.MATERIAL_OIL)) // 80 ml
            assertEquals(20_000L, qty(p, ReferenceIds.MATERIAL_SUGAR)) // 20 g
            assertEquals(15_000L, qty(p, ReferenceIds.MATERIAL_SALT)) // 15 g
            assertEquals(2_000L, qty(p, ReferenceIds.MATERIAL_BAKING_POWDER)) // 2 g
            assertEquals(1_000L, qty(p, ReferenceIds.MATERIAL_PACKING)) // one piece per packet
        }
        assertNull(qty(ReferenceIds.PRODUCT_CHAPATHI, ReferenceIds.MATERIAL_SORBATE))
        assertTrue(recipe.any { it.productId == ReferenceIds.PRODUCT_CHAPATHI && it.materialId == ReferenceIds.MATERIAL_SORBATE })
        assertFalse(recipe.any { it.productId == ReferenceIds.PRODUCT_FRESH && it.materialId == ReferenceIds.MATERIAL_SORBATE })
        db.close()
    }

    @Test fun theDefaultSettingsAreWrittenAndNoBusinessAddressOrPinExists() = runBlocking {
        val db = TestDatabase.inMemory()
        seeded(db)
        val s = db.settingDao().getAll().associate { it.key to it.value }
        assertEquals("Mamre Foods", s[SettingKeys.BUSINESS_NAME])
        assertEquals("W1", s[SettingKeys.DEVICE_CODE])
        assertEquals("200", s[SettingKeys.WASTAGE_BP]) // 2%
        assertEquals("5", s[SettingKeys.LOCK_MINUTES])
        assertEquals("1", s[SettingKeys.NEXT_BILL_SEQ])
        assertEquals("1", s[SettingKeys.NEXT_RECEIPT_SEQ])
        // The Owner types these in Settings; the app never invents them (Doc 1 A-31, P-6, P-12) and the PIN comes in step 2.
        for (key in listOf(SettingKeys.ADDRESS, SettingKeys.PHONE, SettingKeys.FOOTER_TEXT, SettingKeys.OWNER_NAME, SettingKeys.PIN_HASH, SettingKeys.PIN_SALT)) {
            assertNull(key, s[key])
        }
        db.close()
    }

    @Test fun noCustomersPricesBillsPaymentsReturnsPurchasesExpensesDamageStockOrLogExist() = runBlocking {
        val db = TestDatabase.inMemory()
        seeded(db)
        assertEquals(0, db.customerDao().count())
        assertEquals(0, db.priceDefaultDao().getAll().size)
        assertEquals(0, db.priceOverrideDao().getAll().size)
        assertEquals(0, db.invoiceDao().count())
        assertEquals(0, db.invoiceItemDao().getAll().size)
        assertEquals(0, db.paymentDao().getAll().size)
        assertEquals(0, db.returnDao().getAll().size)
        assertEquals(0, db.materialPurchaseDao().getAll().size)
        assertEquals(0, db.expenseDao().getAll().size)
        assertEquals(0, db.productionDamageDao().getAll().size)
        assertEquals(0, db.openingStockDao().getAll().size)
        assertEquals(0, db.changeLogDao().getAll().size)
        db.close()
    }

    @Test fun runningItAgainChangesNothingAndKeepsWhatTheOwnerEdited() = runBlocking {
        val db = TestDatabase.inMemory()
        seeded(db)
        db.settingDao().put(SettingEntity(SettingKeys.BUSINESS_NAME, "My Foods"))
        db.customerTypeDao().setSalesmanCanEditPrice(ReferenceIds.TYPE_SHOP, true)
        seeded(db)
        // Re-inserting the reference rows (as after a half-finished first run) also leaves the edits alone: every insert ignores existing rows.
        db.customerTypeDao().insertAll(ReferenceSeed.customerTypes)
        db.settingDao().insertAll(ReferenceSeed.settings)
        assertEquals("My Foods", db.settingDao().get(SettingKeys.BUSINESS_NAME))
        assertTrue(db.customerTypeDao().get(ReferenceIds.TYPE_SHOP)!!.salesmanCanEditPrice)
        assertEquals(4, db.customerTypeDao().count())
        assertEquals(2, db.productDao().count())
        db.close()
    }

    @Test fun theSeedSurvivesTheDatabaseBeingClosedAndReopened() = runBlocking {
        var db = TestDatabase.file(file)
        seeded(db)
        db.close()
        db = TestDatabase.file(file)
        assertEquals(4, db.customerTypeDao().count())
        assertEquals(7, db.materialDao().getAll().size)
        assertEquals("W1", db.settingDao().get(SettingKeys.DEVICE_CODE))
        assertEquals(0, db.customerDao().count())
        db.close()
    }
}
