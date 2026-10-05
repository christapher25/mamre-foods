package com.mamre.billing.ui.worker

import com.mamre.billing.data.demo.DemoCustomerIds
import com.mamre.billing.data.demo.DemoWorld
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.World
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * W1 Home (Doc 2 s10): the name, owner and device code come from Settings and the only number is a COUNT of today's bills,
 * never a total. (The old tests here covered the catalog sync timing; there is no sync in version 1.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    private lateinit var w: World

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { DemoWorld.open() }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun home() = HomeViewModel(w.sales, w.settings, w.clock)

    @Test fun homeShowsTheBusinessOwnerAndDeviceFromSettings() = runBlocking {
        val state = withTimeout(10_000) { home().state.first { it.deviceCode.isNotEmpty() } }
        assertEquals("MAMRE FOODS", state.businessName) // the debug sample settings
        assertEquals("Rajesh", state.ownerName)
        assertEquals("W1", state.deviceCode)
    }

    @Test fun theCountOfTodaysBillsGoesUpWhenABillIsMade() = runBlocking {
        val vm = home()
        val before = withTimeout(10_000) { vm.state.first { it.deviceCode.isNotEmpty() } }.invoicesToday
        w.makeBill(w.draft(DemoCustomerIds.SPICE_GARDEN, listOf(w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = 1, unit = 240))))
        val after = withTimeout(10_000) { vm.state.first { it.invoicesToday == before + 1 } }
        assertEquals(before + 1, after.invoicesToday)
    }

    @Test fun homeHoldsNoMoneyAtAll() {
        val names = HomeState::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.toString(), names.none { it.contains("cents") || it.contains("total") || it.contains("balance") })
    }
}
