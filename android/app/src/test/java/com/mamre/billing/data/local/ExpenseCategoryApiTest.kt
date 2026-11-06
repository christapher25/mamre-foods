package com.mamre.billing.data.local

import com.mamre.billing.domain.admin.ExpenseKind
import com.mamre.billing.domain.usecase.RuleException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B4, Doc 1 s10.2 and A-18: the Admin adds an expense category through AddExpenseCategory, read back AFTER THE DATABASE IS CLOSED
 * AND OPENED AGAIN. Every new category is indirect; a refused name changes nothing, not even the change log.
 */
@RunWith(RobolectricTestRunner::class)
class ExpenseCategoryApiTest {
    private val file = "expense-category-api-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun first(): World = World(TestDatabase.file(file)).also { it.seed() }

    private fun reopen(w: World): World {
        w.db.close()
        return World(TestDatabase.file(file), w.clock)
    }

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    @Test fun theEightLaunchCategoriesAreThereAndANewOneIsAddedLastAsIndirectAfterReopening() = runBlocking {
        var w = first()
        val before = w.admin.expenseCategories()
        assertEquals(8, before.size)
        w.admin.addExpenseCategory("  Pest   control ")
        w = reopen(w)
        val after = w.admin.expenseCategories()
        assertEquals(9, after.size)
        assertEquals(before.map { it.id }, after.take(8).map { it.id }) // the launch categories keep their order
        val added = after.last()
        assertEquals("Pest control", added.name)
        assertEquals(ExpenseKind.INDIRECT, added.kind)
        w.db.close()
    }

    @Test fun addingWritesExactlyOneChangeLogEntryNamingTheCategory() = runBlocking {
        val w = first()
        val before = w.changeLog.all().size
        w.admin.addExpenseCategory("Pest control")
        val added = w.changeLog.all().drop(before)
        assertEquals(1, added.size)
        assertEquals("Add expense category Pest control", added.single().what)
        w.db.close()
    }

    @Test fun aTooShortOrDuplicateNameIsRefusedAndChangesNothingNotEvenTheLog() = runBlocking {
        val w = first()
        val categories = w.admin.expenseCategories()
        val log = w.changeLog.all().size
        assertEquals("The name is 2 to 30 characters", refused { w.admin.addExpenseCategory("R") })
        assertEquals("The name is 2 to 30 characters", refused { w.admin.addExpenseCategory("   ") })
        assertEquals("The name is 2 to 30 characters", refused { w.admin.addExpenseCategory("x".repeat(31)) })
        assertEquals("A category with this name already exists", refused { w.admin.addExpenseCategory("electricity") })
        assertEquals("A category with this name already exists", refused { w.admin.addExpenseCategory("delivery  CHARGE") })
        assertEquals(categories, w.admin.expenseCategories())
        assertEquals(log, w.changeLog.all().size)
        w.db.close()
    }

    @Test fun twoNewCategoriesCannotShareANameInAnotherCase() = runBlocking {
        val w = first()
        w.admin.addExpenseCategory("Pest control")
        assertEquals("A category with this name already exists", refused { w.admin.addExpenseCategory("PEST   CONTROL") })
        assertEquals(9, w.admin.expenseCategories().size)
        w.db.close()
    }

    @Test fun anExpenseCanBeAddedToTheNewCategoryAndItShowsZeroUntilThen() = runBlocking {
        val w = first()
        val month = YearMonth.from(w.admin.today)
        w.admin.addExpenseCategory("Pest control")
        val id = w.admin.expenseCategories().last().id
        val empty = w.admin.expenses(month).categories.single { it.category.id == id }
        assertEquals(0L, empty.totalCents)
        assertTrue(empty.entries.isEmpty())
        w.admin.addExpense(id, LocalDate.now(w.clock), 2_500L, "Quarterly visit")
        val used = w.admin.expenses(month).categories.single { it.category.id == id }
        assertEquals(2_500L, used.totalCents)
        assertEquals(2_500L, w.admin.expenses(month).indirectTotalCents)
        w.db.close()
    }

    @Test fun theCategoryDaoCanOnlyInsertAndReadNeverUpdateOrDelete() {
        val methods = ExpenseCategoryDao::class.java.methods.map { it.name.lowercase() }
        assertTrue(methods.none { it.contains("delete") || it.contains("remove") || it.contains("update") || it.contains("rename") })
        assertTrue(methods.contains("insert"))
    }
}
