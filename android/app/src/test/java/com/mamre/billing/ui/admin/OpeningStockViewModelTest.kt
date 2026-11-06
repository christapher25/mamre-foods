package com.mamre.billing.ui.admin

import androidx.lifecycle.SavedStateHandle
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.ui.ViewModelScopes
import java.time.YearMonth
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B3: the Opening stock section on the Expenses screen and the Enter form's logic (Doc 1 s9.4), on the real database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpeningStockViewModelTest {
    private lateinit var w: World
    private val scopes = ViewModelScopes()
    private val october = YearMonth.of(2026, 10)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory()).also { it.seed() } }
    }

    @After fun tearDown() {
        scopes.endAll() // before resetMain: see ViewModelScopes
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun expenses() = scopes.track(ExpensesViewModel(w.admin))

    private fun form(materialId: String) = scopes.track(OpeningStockViewModel(w.admin, SavedStateHandle(mapOf(AdminRoutes.ARG_ID to materialId))))

    private suspend fun ExpensesViewModel.loaded(): ExpensesUi = withTimeout(10_000) { ui.first { !it.loading && it.stock != null } }

    private suspend fun OpeningStockViewModel.ready(): OpeningStockUi = withTimeout(10_000) { ui.first { !it.loading } }

    private suspend fun OpeningStockViewModel.until(p: (OpeningStockUi) -> Boolean): OpeningStockUi = withTimeout(10_000) { ui.first(p) }

    @Test fun theSectionShowsNotSetForEveryMaterialOnAFreshInstallThenTheSavedRow() = runBlocking {
        val vm = expenses()
        val before = vm.loaded()
        assertTrue(before.materials.isNotEmpty())
        assertTrue("every material is Not set", before.openingStock.isEmpty())
        val material = before.materials.first()
        w.admin.setOpeningStock(material.id, october, 12_500_000L, 44_050L)
        val after = withTimeout(10_000) { vm.ui.first { it.openingStock.isNotEmpty() } }
        val row = after.openingStock.single()
        assertEquals(material.id, row.materialId)
        // The section prints exactly this line for the saved row, and "Not set" for the others.
        assertEquals(
            "${com.mamre.billing.domain.admin.formatQuantity(12_500_000L, material)}, \$440.50, October 2026",
            openingStockLine(material, row.qtyMb, row.valueCents, row.month),
        )
        assertEquals(after.materials.size - 1, after.materials.count { m -> after.openingStock.none { it.materialId == m.id } })
    }

    @Test fun theFormStartsOnTheCurrentMonthWithTheMaterialLoaded() = runBlocking {
        val v = form(ReferenceIds.MATERIAL_SORBATE)
        val ui = v.ready()
        assertEquals(YearMonth.from(w.admin.today), ui.month)
        assertNotNull(ui.material)
        assertEquals(ReferenceIds.MATERIAL_SORBATE, ui.material!!.id)
    }

    @Test fun savingStoresTheEntryAndTheScreenReturns() = runBlocking {
        val v = form(ReferenceIds.MATERIAL_SORBATE)
        v.ready()
        v.save(october, 12_500_000L, 44_050L)
        v.until { it.saved }
        val row = w.admin.openingStock().single()
        assertEquals(12_500_000L, row.qtyMb)
        assertEquals(44_050L, row.valueCents)
        assertEquals(october, row.month)
    }

    @Test fun aSecondEntryShowsTheRefusalAndSavesNothing() = runBlocking {
        w.admin.setOpeningStock(ReferenceIds.MATERIAL_SORBATE, october, 1_000L, 100L)
        val log = w.changeLog.all().size
        val v = form(ReferenceIds.MATERIAL_SORBATE)
        v.ready()
        v.save(october.plusMonths(1), 9_000L, 900L)
        val ui = v.until { it.error != null }
        assertTrue(ui.error!!, ui.error!!.contains("is already set"))
        assertEquals(false, ui.saved)
        assertEquals(1_000L, w.admin.openingStock().single().qtyMb)
        assertEquals(log, w.changeLog.all().size)
    }

    @Test fun aNegativeValueShowsTheUseCaseMessageAndSavesNothing() = runBlocking {
        val v = form(ReferenceIds.MATERIAL_SORBATE)
        v.ready()
        v.save(october, -5L, 100L)
        val ui = v.until { it.error != null }
        assertTrue(ui.error!!.contains("negative"))
        assertNull(w.admin.openingStock().firstOrNull())
    }

    @Test fun zeroIsSavedForAMaterialWithNoneOnHand() = runBlocking {
        val v = form(ReferenceIds.MATERIAL_SORBATE)
        v.ready()
        v.save(october, 0L, 0L)
        v.until { it.saved }
        assertEquals(0L, w.admin.openingStock().single().qtyMb)
    }
}
