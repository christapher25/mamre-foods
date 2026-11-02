package com.mamre.billing.ui.worker

import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.SlowQueries
import com.mamre.billing.data.local.SmallWorld
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.PacketKey
import com.mamre.billing.domain.worker.PaymentCheck
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Changes D1, D2 and D4 through the real invoice flow on the real database (a SmallWorld, so the waits do not depend on the machine): the Owner's name, the customer's
 * "Name - Location" and the corporate flag reach the confirm state and the saved invoice (a snapshot), and a normal
 * customer is unaffected. The view model calls the MakeBill use case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InvoiceFlowViewModelTest {
    private lateinit var s: SmallWorld
    private val w: World get() = s.w

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        s = runBlocking { SmallWorld.open() }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun newFlow() = InvoiceFlowViewModel(WorkerCatalog(w.customers, w.prices), w.sales, w.settings, w.makeBill, w.clock as Clock)

    private suspend fun InvoiceFlowViewModel.ready(): InvoiceFlowViewModel {
        withTimeout(10_000) { ui.first { !it.loading && it.deviceCode != null } }
        return this
    }

    private suspend fun InvoiceFlowViewModel.pick(id: String) {
        selectCustomer(w.customers.customer(id)!!.let { c ->
            com.mamre.billing.domain.model.Customer(
                c.id, c.name, c.typeId, c.phone, c.address,
                if (c.paymentMode == "credit") com.mamre.billing.domain.model.PaymentMode.CREDIT else com.mamre.billing.domain.model.PaymentMode.CASH,
                c.isActive, c.location, c.isCorporate,
            )
        })
        withTimeout(10_000) { ui.first { it.customerChosen && it.products.isNotEmpty() } }
    }

    /**
     * `ui` is derived from Room flows that emit on Room's own threads, so it can lag behind the last action. confirm() reads
     * `ui.value` and returns without a word when the payment does not check out yet, so first wait for the state that
     * can be confirmed, then wait for the outcome itself. The timeout is only a guard against a hang.
     */
    private suspend fun InvoiceFlowViewModel.confirmAndWait(): InvoiceRecord? = withTimeout(10_000) {
        ui.first { it.canConfirm }
        val done = CompletableDeferred<InvoiceRecord?>()
        confirm { done.complete(it) }
        // A refusal never calls back; the error state says so.
        val refused = async { error.first { it != null }; null }
        try {
            select<InvoiceRecord?> {
                done.onAwait { it }
                refused.onAwait { it }
            }
        } finally {
            refused.cancel()
        }
    }

    @Test fun aCorporateCustomerShowsNoBalanceStateAndTheInvoiceKeepsTheFlagTheNameAndTheLocation() = runBlocking {
        val vm = newFlow().ready()
        vm.pick(s.freshMartId)
        vm.setQuantity(PacketKey(ReferenceIds.PRODUCT_CHAPATHI, 12), 70)
        vm.noAmount()
        val ui = vm.ui.first { it.lines.isNotEmpty() }
        assertTrue(ui.isCorporate)
        assertEquals("FreshMart - Downtown", ui.customerLabel)
        assertEquals("FreshMart", ui.customerName)
        assertEquals(0L, ui.previousBalanceCents) // a corporate balance is never carried into the Sales area
        val before = w.admin.customerBalance(s.freshMartId)
        val invoice = vm.confirmAndWait()
        assertNotNull(invoice)
        invoice!!
        assertTrue(invoice.isCorporate)
        assertEquals("Rajesh", invoice.salesmanName) // the Owner's name from Settings, never the device code
        assertEquals("Downtown", invoice.customerLocation)
        assertEquals("FreshMart - Downtown", invoice.customerDisplay)
        assertEquals(70 * 270L, invoice.totalCents) // the ledger still tracks what they owe
        assertEquals(before + 70 * 270L, w.admin.customerBalance(s.freshMartId))
    }

    @Test fun aNormalCustomerIsNotCorporateAndKeepsItsBalanceState() = runBlocking {
        val vm = newFlow().ready()
        vm.pick(s.spiceGardenId)
        vm.setQuantity(PacketKey(ReferenceIds.PRODUCT_CHAPATHI, 12), 10)
        vm.noAmount()
        val ui = vm.ui.first { it.lines.isNotEmpty() }
        assertFalse(ui.isCorporate)
        assertEquals(w.admin.customerBalance(s.spiceGardenId), ui.previousBalanceCents)
        assertEquals("Spice Garden - Irving", ui.customerLabel)
        val invoice = vm.confirmAndWait()!!
        assertFalse(invoice.isCorporate)
        assertEquals("Irving", invoice.customerLocation)
    }

    @Test fun aWalkInHasNoLocationAndIsNotCorporate() = runBlocking { walkInBill(newFlow().ready()) }

    /**
     * The same walk-in on a database whose every query is 300 ms late, which is what a slow CI runner does to the Room flows.
     * `ui` is derived from those flows, so `ui.value` lags the last action; the test has to wait for the state it needs.
     */
    @Test fun aWalkInOnASlowDatabaseStillMakesTheBill() = runBlocking {
        val slow = SlowQueries()
        w.db.close()
        s = SmallWorld.open(TestDatabase.inMemory(slow))
        slow.delayMs = 300
        walkInBill(newFlow().ready())
    }

    private suspend fun walkInBill(vm: InvoiceFlowViewModel) {
        vm.selectWalkIn()
        withTimeout(10_000) { vm.ui.first { it.customerChosen && it.products.isNotEmpty() } }
        vm.setQuantity(PacketKey(ReferenceIds.PRODUCT_FRESH, 12), 2)
        // preparePayment() copies the total from `ui.value`, so the total must be there before it is called.
        vm.ui.first { it.lines.isNotEmpty() }
        vm.preparePayment()
        val ui = vm.ui.first { it.check is PaymentCheck.Ok }
        assertFalse(ui.isCorporate)
        assertEquals("Walk-in", ui.customerLabel)
        assertEquals("", vm.confirmAndWait()!!.customerLocation)
    }

    @Test fun confirmingTwiceStoresOneBillBecauseTheDraftIdIsMadeOnce() = runBlocking {
        val vm = newFlow().ready()
        vm.pick(s.spiceGardenId)
        vm.setQuantity(PacketKey(ReferenceIds.PRODUCT_CHAPATHI, 12), 3)
        vm.noAmount()
        vm.ui.first { it.lines.isNotEmpty() }
        val before = w.db.invoiceDao().count()
        val a = vm.confirmAndWait()!!
        val b = vm.confirmAndWait()!!
        assertEquals(a.number, b.number)
        assertEquals(before + 1, w.db.invoiceDao().count())
    }

    @Test fun theUseCaseRefusalIsShownByTheViewModelAndNothingIsSaved() = runBlocking {
        val vm = newFlow().ready()
        vm.pick(s.spiceGardenId) // a Restaurant: its price cannot be changed
        vm.setQuantity(PacketKey(ReferenceIds.PRODUCT_CHAPATHI, 12), 3)
        vm.noAmount()
        vm.ui.first { it.lines.isNotEmpty() }
        // The shop assistant "bypasses the screen": the price of the current price list changes under the open bill.
        w.admin.setDefaultPrice(ReferenceIds.PRODUCT_CHAPATHI, ReferenceIds.TYPE_RESTAURANT, 999, w.today)
        // An override now beats the default, so the bill's list price is out of date.
        w.admin.setOverride(s.spiceGardenId, ReferenceIds.PRODUCT_CHAPATHI, 111, w.today, "new price")
        val before = w.db.invoiceDao().count()
        assertEquals(null, vm.confirmAndWait()) // refused: the list price changed since the bill was built
        assertNotNull(vm.error.value)
        assertEquals(before, w.db.invoiceDao().count())
    }
}
