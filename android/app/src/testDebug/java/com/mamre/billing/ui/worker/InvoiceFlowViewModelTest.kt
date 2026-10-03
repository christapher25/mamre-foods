package com.mamre.billing.ui.worker

import com.mamre.billing.data.FakeCustomerDao
import com.mamre.billing.data.FakeCustomerTypeDao
import com.mamre.billing.data.FakePriceDefaultDao
import com.mamre.billing.data.FakePriceOverrideDao
import com.mamre.billing.data.FakeProductDao
import com.mamre.billing.data.FakeSyncStateDao
import com.mamre.billing.data.FakeTransactionRunner
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.auth.MemoryTokenStore
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.domain.worker.PacketKey
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Changes D1, D2 and D4 through the real invoice flow: the signed-in salesman's name, the customer's "Name - Location"
 * and the corporate flag reach the confirm state and the saved invoice (a snapshot), and a normal customer is unaffected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceFlowViewModelTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val api = FakeApi(table)
    private val tokens = MemoryTokenStore()
    private val session = SessionManager(api, tokens, adminSignInAvailable = false)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeTransactionRunner(),
        CatalogRemote { cursor -> session.authorized { api.catalog(it, cursor) } },
    )
    private val store = DemoStore(clock, DemoSeed.state())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun newFlow(): InvoiceFlowViewModel {
        session.login("user1", "user1")
        repo.refresh()
        return InvoiceFlowViewModel(WorkerCatalog(repo), store, session)
    }

    private suspend fun InvoiceFlowViewModel.pick(id: String) = selectCustomer(repo.customer(id)!!)

    @Test fun aCorporateCustomerShowsNoBalanceStateAndTheInvoiceKeepsTheFlagTheNameAndTheLocation() = runTest {
        val vm = newFlow()
        vm.pick(DemoIds.FRESHMART_DOWNTOWN)
        vm.setQuantity(PacketKey(DemoIds.CHAPATHI, 6), 70)
        vm.noAmount()
        val ui = vm.ui.value
        assertTrue(ui.isCorporate)
        assertEquals("FreshMart - Downtown", ui.customerLabel)
        assertEquals("FreshMart", ui.customerName)
        val invoice = vm.confirm()
        assertNotNull(invoice)
        invoice!!
        assertTrue(invoice.isCorporate)
        assertEquals("Rajesh", invoice.salesmanName) // the signed-in salesman, not the device code
        assertEquals("Downtown", invoice.customerLocation)
        assertEquals("FreshMart - Downtown", invoice.customerDisplay)
        assertEquals(70 * 270L, invoice.totalCents) // the ledger still tracks what they owe
        assertEquals(70 * 270L, store.state.value.balanceOf(DemoIds.FRESHMART_DOWNTOWN))
    }

    @Test fun aNormalCustomerIsNotCorporateAndKeepsItsBalanceState() = runTest {
        val vm = newFlow()
        vm.pick(DemoIds.RESTAURANT)
        vm.setQuantity(PacketKey(DemoIds.CHAPATHI, 6), 10)
        vm.noAmount()
        val ui = vm.ui.value
        assertFalse(ui.isCorporate)
        assertEquals(12_000L, ui.previousBalanceCents)
        assertEquals("Spice Garden - Irving", ui.customerLabel)
        val invoice = vm.confirm()!!
        assertFalse(invoice.isCorporate)
        assertEquals("Irving", invoice.customerLocation)
    }

    @Test fun aWalkInHasNoLocationAndIsNotCorporate() = runTest {
        val vm = newFlow()
        vm.selectWalkIn()
        vm.setQuantity(PacketKey(DemoIds.FRESH, 6), 2)
        vm.preparePayment()
        val ui = vm.ui.value
        assertFalse(ui.isCorporate)
        assertEquals("Walk-in", ui.customerLabel)
        assertEquals("", vm.confirm()!!.customerLocation)
    }

    @Test fun theCorporateFlagComesFromTheSalesmansCatalogNotFromTheAdminSide() = runTest {
        val vm = newFlow()
        // The Admin turns the flag off now, but the salesman has not synced yet: the app still treats them as corporate.
        table.updateCustomer(DemoIds.FRESHMART_DOWNTOWN) { it.copy(isCorporate = false) }
        vm.pick(DemoIds.FRESHMART_DOWNTOWN)
        assertTrue(vm.ui.value.isCorporate)
    }
}
