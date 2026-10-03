package com.mamre.billing.ui.worker

import com.mamre.billing.data.FakeCustomerDao
import com.mamre.billing.data.FakeCustomerTypeDao
import com.mamre.billing.data.FakePriceDefaultDao
import com.mamre.billing.data.FakePriceOverrideDao
import com.mamre.billing.data.FakeProductDao
import com.mamre.billing.data.FakeSettingDao
import com.mamre.billing.data.FakeSyncStateDao
import com.mamre.billing.data.FakeTransactionRunner
import com.mamre.billing.data.admin.FakeAdminApi
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.auth.MemoryTokenStore
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.data.repository.SyncOutcome
import com.mamre.billing.data.repository.WorkerSync
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.PaymentMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sync timing (owner decision): customers, the corporate flag, locations, prices, price-edit switches and the business
 * settings reach the salesman ONLY at Sync now. Opening Home must not pull the catalog, except once when the local
 * catalog is empty (first use after login), so the app is usable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val api = FakeApi(table)
    private val session = SessionManager(api, MemoryTokenStore(), adminSignInAvailable = false)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeSettingDao(), FakeTransactionRunner(),
        CatalogRemote { cursor -> session.authorized { api.catalog(it, cursor) } },
    )
    private val store = DemoStore(clock, DemoSeed.state())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun openHome() = HomeViewModel(session, repo, store)

    /** Everything the salesman's local catalog holds that the Admin can change. */
    private data class Local(
        val cursor: Long,
        val customerIds: Set<String>,
        val spiceGardenCorporate: Boolean,
        val spiceGardenLocation: String,
        val priceRows: Int,
        val restaurantCanEditPrice: Boolean,
        val addressLines: List<String>,
    )

    private suspend fun local() = Local(
        cursor = repo.catalogCursor(),
        customerIds = repo.activeCustomers().map { it.id }.toSet(),
        spiceGardenCorporate = repo.customer(DemoIds.RESTAURANT)!!.isCorporate,
        spiceGardenLocation = repo.customer(DemoIds.RESTAURANT)!!.location,
        priceRows = repo.priceBook().defaults.size,
        restaurantCanEditPrice = repo.activeCustomerTypes().first { it.id == DemoIds.RESTAURANT_TYPE }.workerCanEditPrice,
        addressLines = repo.businessHeader().addressLines,
    )

    /** The Admin changes a customer, the corporate flag, a location, a price, a price-edit switch and the address. */
    private suspend fun adminChangesEverything() {
        val by = "Test Admin"
        admin.addCustomer(CustomerForm("Green Leaf", DemoIds.SHOP_TYPE, "", "", PaymentMode.CASH, "", true, location = "Plano"), by)
        val spice = admin.customer(DemoIds.RESTAURANT)!!
        admin.updateCustomer(
            spice.id,
            CustomerForm(spice.name, spice.typeId, spice.phone, spice.address, spice.paymentMode, spice.notes, spice.isActive, location = "Little Elm", isCorporate = true),
            by,
        )
        admin.setDefaultPrice(DemoIds.FRESH, DemoIds.SHOP_TYPE, 333, LocalDate.of(2026, 11, 1), by)
        admin.setWorkerCanEditPrice(DemoIds.RESTAURANT_TYPE, true, by)
        admin.saveSettings(admin.settings().copy(address = "9 Sample Road\nOtherville, TX 11111"), by)
    }

    @Test fun firstUseWithAnEmptyCatalogLoadsItOnceSoTheAppIsUsable() = runTest {
        session.login("user1", "user1")
        assertTrue(repo.activeProducts().isEmpty())
        openHome()
        assertTrue(repo.activeProducts().isNotEmpty())
        assertTrue(repo.activeCustomers().isNotEmpty())
        assertEquals(table.version, repo.catalogCursor())
        assertEquals(listOf("123 Example Street", "Anytown, TX 00000"), repo.businessHeader().addressLines)
    }

    @Test fun openingHomeDoesNotChangeTheLocalCatalogButSyncNowDoes() = runTest {
        session.login("user1", "user1")
        repo.refresh() // the catalog is already on the device from an earlier sync
        val before = local()
        assertFalse(before.spiceGardenCorporate)
        assertEquals("Irving", before.spiceGardenLocation)

        adminChangesEverything()

        // Opening Home, even twice, changes nothing on the device.
        openHome()
        openHome()
        assertEquals(before, local())

        // Sync now does.
        assertEquals(SyncOutcome.Done, WorkerSync(repo, store).syncNow())
        val after = local()
        assertTrue(after.cursor > before.cursor)
        assertEquals(before.customerIds.size + 1, after.customerIds.size)
        assertTrue(after.spiceGardenCorporate)
        assertEquals("Little Elm", after.spiceGardenLocation)
        assertEquals(before.priceRows + 1, after.priceRows)
        assertTrue(after.restaurantCanEditPrice)
        assertEquals(listOf("9 Sample Road", "Otherville, TX 11111"), after.addressLines)
    }

    @Test fun homeStillShowsTheStoredProfileAndTodaysCountWithoutPullingAnything() = runTest {
        session.login("user1", "user1")
        repo.refresh()
        val cursor = repo.catalogCursor()
        val home = openHome()
        assertEquals("Rajesh", home.state.value.workerName)
        assertEquals("W1", home.state.value.deviceCode)
        assertEquals(cursor, repo.catalogCursor())
    }
}
