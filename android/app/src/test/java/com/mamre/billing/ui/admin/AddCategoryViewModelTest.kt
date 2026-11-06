package com.mamre.billing.ui.admin

import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.ui.ViewModelScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B4: the Add category screen's logic (Doc 1 s10.2), on the real database through the Admin API. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddCategoryViewModelTest {
    private lateinit var w: World
    private val scopes = ViewModelScopes()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory()).also { it.seed() } }
    }

    @After fun tearDown() {
        scopes.endAll() // before resetMain: see ViewModelScopes
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun vm() = scopes.track(AddCategoryViewModel(w.admin))

    private suspend fun AddCategoryViewModel.ready(): AddCategoryUi = withTimeout(10_000) { ui.first { !it.loading } }

    private suspend fun AddCategoryViewModel.until(p: (AddCategoryUi) -> Boolean): AddCategoryUi = withTimeout(10_000) { ui.first(p) }

    @Test fun theScreenKnowsTheExistingNamesSoItCanShowTheProblemsBeforeSaving() = runBlocking {
        val v = vm()
        val ui = v.ready()
        assertEquals(8, ui.existingNames.size)
        assertTrue("Electricity" in ui.existingNames)
        assertEquals(listOf("A category with this name already exists"), v.problems(ui, "electricity"))
        assertEquals(listOf("The name is 2 to 30 characters"), v.problems(ui, "R"))
        assertTrue(v.problems(ui, "Pest control").isEmpty())
    }

    @Test fun saveAddsTheCategoryAndTheScreenReturns() = runBlocking {
        val v = vm()
        v.ready()
        v.save("Pest control")
        v.until { it.saved }
        assertEquals("Pest control", w.admin.expenseCategories().last().name)
    }

    @Test fun aBadNameIsRefusedAndNothingIsSaved() = runBlocking {
        val v = vm()
        v.ready()
        v.save("R")
        val ui = v.until { it.error != null }
        assertEquals("The name is 2 to 30 characters", ui.error)
        assertFalse(ui.saved)
        assertEquals(8, w.admin.expenseCategories().size)
    }

    @Test fun aDuplicateNameShowsTheUseCaseMessageAndSavesNothing() = runBlocking {
        val v = vm()
        v.ready()
        v.save("delivery  CHARGE")
        val ui = v.until { it.error != null }
        assertEquals("A category with this name already exists", ui.error)
        assertEquals(8, w.admin.expenseCategories().size)
    }
}
