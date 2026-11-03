package com.mamre.billing.ui.worker

import com.mamre.billing.data.local.ProductEntity
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.security.AppLock
import java.time.LocalDate
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

/** Doc 1 s2.1 FR-3: the Finish setup card on the Sales Home. It carries a count of missing prices, never an amount. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeSetupCardTest {
    private lateinit var w: World

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory()).also { it.seed() } }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun home() = HomeViewModel(w.sales, w.settings, w.clock, w.prices, w.customers, AppLock(w.clock))

    private suspend fun HomeViewModel.stateWhere(predicate: (HomeState) -> Boolean) = withTimeout(10_000) { state.first(predicate) }

    private suspend fun priceEverything() {
        for (type in w.customers.types()) w.price(type.id, 250, 240)
    }

    private suspend fun fillBusinessDetails() {
        w.settings.put(SettingKeys.OWNER_NAME, "Rajesh")
        w.settings.put(SettingKeys.ADDRESS, "1 Main Road")
        w.settings.put(SettingKeys.PHONE, "9876543210")
    }

    @Test fun aFreshInstallShowsBothItemsWithTheCountOfMissingPrices() = runBlocking {
        val state = home().stateWhere { it.deviceCode.isNotEmpty() }
        val types = w.customers.types().size
        val products = w.prices.products().size
        assertTrue(state.setup.businessDetails)
        assertEquals(types * products, state.setup.missingPrices)
        assertTrue(state.setup.any)
    }

    @Test fun theCardIsGoneWhenNothingIsMissing() = runBlocking {
        fillBusinessDetails()
        priceEverything()
        val state = home().stateWhere { it.deviceCode.isNotEmpty() }
        assertFalse(state.setup.any)
    }

    @Test fun onlyPricesMissingShowsOnlyThePricesItem() = runBlocking {
        fillBusinessDetails()
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 240)
        val state = home().stateWhere { it.deviceCode.isNotEmpty() }
        assertFalse(state.setup.businessDetails)
        assertEquals((w.customers.types().size - 1) * w.prices.products().size, state.setup.missingPrices)
    }

    @Test fun aProductAddedLaterBringsTheCardBackWhenTheHomeComesBackToTheFront() = runBlocking {
        fillBusinessDetails()
        priceEverything()
        val vm = home()
        assertFalse(vm.stateWhere { it.deviceCode.isNotEmpty() }.setup.any)
        w.db.productDao().insertAll(listOf(ProductEntity("p-rotti", "ROTTI", "Rotti", 12, 10, true)))
        vm.refreshSetup()
        val state = vm.stateWhere { it.setup.any }
        assertEquals(w.customers.types().size, state.setup.missingPrices)
        assertFalse(state.setup.businessDetails)
    }

    @Test fun aPriceThatStartsTomorrowIsStillMissingTodayAndCountsFromItsDate() = runBlocking {
        // Doc 1 s2.1 FR-3, s4.3: a default price that starts in the future does not price today's bill ("No price set"), so it is still missing.
        fillBusinessDetails()
        val today = LocalDate.now(w.clock)
        val tomorrow = today.plusDays(1)
        for (type in w.customers.types()) {
            if (type.id == ReferenceIds.TYPE_RESTAURANT) w.price(type.id, 250, 240, from = tomorrow) else w.price(type.id, 250, 240)
        }
        val vm = home()
        val products = w.prices.products().size
        assertEquals(products, vm.stateWhere { it.deviceCode.isNotEmpty() }.setup.missingPrices)
        w.clock.set("${tomorrow}T12:00:00Z")
        vm.refreshSetup()
        assertFalse(vm.stateWhere { it.deviceCode.isNotEmpty() && !it.setup.any }.setup.any)
    }
}
