package com.mamre.billing.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Doc 2 s5.2 (review finding 11): the first-run reference data is written INSIDE the transaction that creates the schema
 * (RoomDatabase.Callback.onCreate), so a database either has its schema AND its reference data, or neither. Nothing calls a
 * seed: the first time the database is opened it is there. These tests use the production builder, `MamreDatabase.builder`.
 */
@RunWith(RobolectricTestRunner::class)
class ReferenceSeedOnCreateTest {
    private val name = "seed-on-create-test.db"
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After fun cleanUp() = TestDatabase.delete(name)

    private fun open(extra: ((androidx.sqlite.db.SupportSQLiteDatabase) -> Unit)? = null): MamreDatabase =
        MamreDatabase.builder(context, name, extra).allowMainThreadQueries().build()

    @Test fun theReferenceDataExistsAfterTheFirstOpenWithNoSeedCallAtAll() = runBlocking {
        val db = open()
        assertEquals(4, db.customerTypeDao().count())
        assertEquals(2, db.productDao().count())
        assertEquals(7, db.materialDao().count())
        assertEquals(8, db.expenseCategoryDao().count())
        assertEquals("W1", db.settingDao().get(SettingKeys.DEVICE_CODE))
        db.close()
    }

    @Test fun whatTheCallbackWritesIsExactlyTheReferenceLists() = runBlocking {
        val db = open()
        assertEquals(ReferenceSeed.customerTypes.toSet(), db.customerTypeDao().getAll().toSet())
        assertEquals(ReferenceSeed.products.toSet(), db.productDao().getAll().toSet())
        assertEquals(ReferenceSeed.materials.toSet(), db.materialDao().getAll().toSet())
        assertEquals(ReferenceSeed.recipe.toSet(), db.recipeDao().getAll().toSet()) // the unset sorbate quantity stays NULL
        assertEquals(ReferenceSeed.expenseCategories.toSet(), db.expenseCategoryDao().getAll().toSet())
        assertEquals(ReferenceSeed.settings.toSet(), db.settingDao().getAll().toSet())
        // Reference data only: nothing else is created (AT-21).
        assertEquals(0, db.customerDao().count())
        assertEquals(0, db.invoiceDao().count())
        assertEquals(0, db.priceDefaultDao().count())
        db.close()
    }

    @Test fun aFailedSeedLeavesNothingBehindNotEvenTheSchema() = runBlocking {
        val db = open { error("the seed failed halfway") } // runs after the reference rows, in the same creation transaction
        try {
            db.customerTypeDao().count()
            fail("opening a database whose creation failed must fail")
        } catch (_: Exception) {
        }
        db.close()
        // The file is either not there or holds no table: the creation transaction was rolled back as a whole.
        val file: File = context.getDatabasePath(name)
        if (file.exists()) {
            val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            raw.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%'", null).use {
                assertEquals("tables left by a failed creation", 0, it.count)
            }
            raw.close()
        }
        // The next start creates everything cleanly, once.
        val again = open()
        assertEquals(4, again.customerTypeDao().count())
        assertEquals(6, again.settingDao().getAll().size)
        assertEquals(7, again.materialDao().count())
        again.close()
    }

    @Test fun aSecondOpenNeitherSeedsAgainNorOverwritesWhatTheOwnerEdited() = runBlocking {
        var db = open()
        db.settingDao().put(SettingEntity(SettingKeys.BUSINESS_NAME, "My Foods"))
        db.customerTypeDao().setSalesmanCanEditPrice(ReferenceIds.TYPE_SHOP, true)
        db.close()
        db = open { fail("the creation callback must not run on an existing database") }
        assertEquals("My Foods", db.settingDao().get(SettingKeys.BUSINESS_NAME))
        assertTrue(db.customerTypeDao().get(ReferenceIds.TYPE_SHOP)!!.salesmanCanEditPrice)
        assertEquals(4, db.customerTypeDao().count())
        db.close()
    }

    @Test fun theIdempotentTopUpStillChangesNothingOnADatabaseThatAlreadyHasTheData() = runBlocking {
        val db = open()
        val before = db.settingDao().getAll().toSet()
        ReferenceSeed(db, RoomUnitOfWork(db)).run()
        assertEquals(before, db.settingDao().getAll().toSet())
        assertFalse(db.customerTypeDao().getAll().isEmpty())
        db.close()
    }
}
