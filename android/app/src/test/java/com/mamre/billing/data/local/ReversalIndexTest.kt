package com.mamre.billing.data.local

import android.database.sqlite.SQLiteConstraintException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Doc 1 s9.4, Doc 2 s4.2, AT-15: an entry is reversed ONCE. The use case checks it, and the unique index on reverses_id makes
 * the database refuse a second reversal row even when a use case is bypassed. These tests insert straight through the DAO.
 */
@RunWith(RobolectricTestRunner::class)
class ReversalIndexTest {
    private val file = "reversal-index-test.db"
    private val day = LocalDate.of(2026, 10, 1)

    @After fun cleanUp() = TestDatabase.delete(file)

    private fun purchase(id: String, reverses: String?, qty: Long = 1_000) =
        MaterialPurchaseEntity(id, ReferenceIds.MATERIAL_WHEAT, day, qty, 100, "", reverses, if (reverses == null) "" else "typo")

    private fun expense(id: String, reverses: String?, cents: Long = 500) =
        ExpenseEntity(id, ReferenceIds.CATEGORY_FUEL, day, cents, "", reverses, if (reverses == null) "" else "typo")

    @Test fun theDatabaseRefusesASecondReversalOfTheSamePurchase() = runBlocking {
        val db = TestDatabase.inMemory().also { ReferenceSeed(it, RoomUnitOfWork(it)).run() }
        val dao = db.materialPurchaseDao()
        dao.insert(purchase("p1", null))
        dao.insert(purchase("r1", "p1", qty = -1_000)) // the first reversal is fine
        try {
            dao.insert(purchase("r2", "p1", qty = -1_000))
            fail("a second reversal of the same purchase must be refused by the database")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals(listOf("p1", "r1"), dao.getAll().map { it.id })
        db.close()
    }

    @Test fun theDatabaseRefusesASecondReversalOfTheSameExpense() = runBlocking {
        val db = TestDatabase.inMemory().also { ReferenceSeed(it, RoomUnitOfWork(it)).run() }
        val dao = db.expenseDao()
        dao.insert(expense("e1", null))
        dao.insert(expense("x1", "e1", cents = -500))
        try {
            dao.insert(expense("x2", "e1", cents = -500))
            fail("a second reversal of the same expense must be refused by the database")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals(listOf("e1", "x1"), dao.getAll().map { it.id })
        db.close()
    }

    @Test fun manyEntriesWithNoReversalAreFineBecauseNullsAreNotDuplicates() = runBlocking {
        val db = TestDatabase.inMemory().also { ReferenceSeed(it, RoomUnitOfWork(it)).run() }
        repeat(3) { db.materialPurchaseDao().insert(purchase("p$it", null)) }
        repeat(3) { db.expenseDao().insert(expense("e$it", null)) }
        assertEquals(3, db.materialPurchaseDao().getAll().size)
        assertEquals(3, db.expenseDao().getAll().size)
        db.close()
    }

    @Test fun theIndexStillHoldsAfterTheDatabaseIsClosedAndReopened() = runBlocking {
        var db = TestDatabase.file(file).also { ReferenceSeed(it, RoomUnitOfWork(it)).run() }
        db.materialPurchaseDao().insert(purchase("p1", null))
        db.materialPurchaseDao().insert(purchase("r1", "p1", qty = -1_000))
        db.close()
        db = TestDatabase.file(file)
        try {
            db.materialPurchaseDao().insert(purchase("r2", "p1", qty = -1_000))
            fail("the unique index must survive a reopen")
        } catch (_: SQLiteConstraintException) {
        }
        db.close()
    }
}
