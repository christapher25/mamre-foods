package com.mamre.billing.ui.admin

import androidx.lifecycle.SavedStateHandle
import com.mamre.billing.data.local.ReferenceIds
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

/** B5: the Add product and Edit product screens' logic (Doc 1 s3), on the real database through the Admin API. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProductFormViewModelTest {
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

    private fun add() = scopes.track(ProductFormViewModel(w.admin, SavedStateHandle()))

    private fun edit(id: String) = scopes.track(ProductFormViewModel(w.admin, SavedStateHandle(mapOf(AdminRoutes.ARG_ID to id))))

    private suspend fun ProductFormViewModel.ready(): ProductFormUi = withTimeout(10_000) { ui.first { !it.loading } }

    private suspend fun ProductFormViewModel.until(p: (ProductFormUi) -> Boolean): ProductFormUi = withTimeout(10_000) { ui.first(p) }

    private fun ProductFormViewModel.fill(name: String, code: String, packet: String = "12", yield: String = "32") {
        onName(name); onCode(code); onPacket(packet); onYield(yield)
    }

    @Test fun theAddFormStartsWithTwelveAndThirtyTwoAndActiveOn() = runBlocking {
        val v = add()
        val ui = v.ready()
        assertEquals("Add product", ui.title)
        assertEquals("12", ui.packet)
        assertEquals("32", ui.yieldPerKg)
        assertTrue(ui.active)
    }

    @Test fun saveAddsTheProductAndTheScreenReturns() = runBlocking {
        val v = add()
        v.ready()
        v.fill("Mamre Roti", "roti", "10", "30")
        v.save()
        v.until { it.saved }
        val p = w.admin.products().single { it.code == "ROTI" }
        assertEquals("Mamre Roti", p.name)
        assertEquals(10, p.unitsPerPacket)
        assertEquals(30, p.yieldPerKg)
        assertTrue(p.isActive)
    }

    @Test fun aBadFormShowsEveryProblemAndSavesNothing() = runBlocking {
        val v = add()
        v.ready()
        v.fill("R", "", "0", "0")
        v.save()
        val ui = v.until { it.tried }
        assertEquals(4, v.problems(ui).size)
        assertFalse(ui.saved)
        assertEquals(2, w.admin.products().size) // only the two reference products
    }

    @Test fun aDuplicateCodeIsShownAsAProblemBeforeAnythingIsSent() = runBlocking {
        val v = add()
        v.ready()
        v.fill("Mamre Roti", "fresh")
        v.save()
        val ui = v.until { it.tried }
        assertEquals(listOf("Another product already has that code"), v.problems(ui))
        assertEquals(2, w.admin.products().size)
    }

    @Test fun theEditFormIsFilledFromTheProductAndKeepsItsOwnNameAndCode() = runBlocking {
        val v = edit(ReferenceIds.PRODUCT_FRESH)
        val ui = v.ready()
        assertEquals("Edit product", ui.title)
        assertEquals("FRESH", ui.code)
        assertTrue(v.problems(ui).isEmpty())
    }

    @Test fun editSavesNameCodeActiveAndAlsoThePacketSizeAndYieldThroughTheirOwnUseCases() = runBlocking {
        val v = edit(ReferenceIds.PRODUCT_FRESH)
        v.ready()
        val logBefore = w.changeLog.all().size
        v.onName("Mamre Fresh Roti"); v.onCode("fr1"); v.onActive(false); v.onPacket("10"); v.onYield("30")
        v.save()
        v.until { it.saved }
        val p = w.admin.products().single { it.id == ReferenceIds.PRODUCT_FRESH }
        assertEquals("Mamre Fresh Roti", p.name)
        assertEquals("FR1", p.code)
        assertFalse(p.isActive)
        assertEquals(10, p.unitsPerPacket)
        assertEquals(30, p.yieldPerKg)
        assertEquals(listOf("Edit product Mamre Fresh Chapathi", "Standard packet Mamre Fresh Roti", "Yield Mamre Fresh Roti"), w.changeLog.all().drop(logBefore).map { it.what })
    }

    @Test fun editingOnlyThePacketSizeWritesOnlyThatChangeLogEntry() = runBlocking {
        val v = edit(ReferenceIds.PRODUCT_CHAPATHI)
        v.ready()
        val logBefore = w.changeLog.all().size
        v.onPacket("6")
        v.save()
        v.until { it.saved }
        assertEquals(1, w.changeLog.all().size - logBefore)
        assertEquals(6, w.admin.products().single { it.id == ReferenceIds.PRODUCT_CHAPATHI }.unitsPerPacket)
    }
}
