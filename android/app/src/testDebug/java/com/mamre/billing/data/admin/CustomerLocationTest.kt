package com.mamre.billing.data.admin

import com.mamre.billing.data.demo.DemoCustomerIds
import com.mamre.billing.data.demo.DemoWorld
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.ui.worker.WorkerCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Change set D2 on the real database: a customer is a name plus a location, the pair is unique, and the Sales area sees a change at once. */
@RunWith(RobolectricTestRunner::class)
class CustomerLocationTest {
    private lateinit var w: World
    private val admin get() = w.admin

    @Before fun open() {
        w = runBlocking { DemoWorld.open() }
    }

    @After fun close() {
        w.db.close()
    }

    private fun form(name: String, location: String, type: String = ReferenceIds.TYPE_SHOP, mode: PaymentMode = PaymentMode.CASH) =
        CustomerForm(name, type, "", "", mode, "", true, location = location)

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: AdminRuleException) {
            return e.message.orEmpty()
        }
        throw AssertionError("expected AdminRuleException")
    }

    private suspend fun salesCustomers() = WorkerCatalog(w.customers, w.prices).load().customers

    // ------------------------------------------------------------------ the seed

    @Test fun everySeededCustomerHasALocationAndNameWithLocationIsUnique() = runBlocking {
        val rows = admin.customers()
        assertTrue(rows.all { it.location.isNotBlank() })
        assertEquals(rows.size, rows.map { it.name.lowercase() to it.location.lowercase() }.toSet().size)
    }

    @Test fun freshMartIsTwoStoresOfOneChainOnCredit() = runBlocking {
        val stores = admin.customers().filter { it.name == "FreshMart" }
        assertEquals(setOf("Downtown", "Westside"), stores.map { it.location }.toSet())
        assertTrue(stores.all { it.paymentMode == PaymentMode.CREDIT })
        assertEquals(setOf(DemoCustomerIds.FRESHMART_DOWNTOWN, DemoCustomerIds.FRESHMART_WESTSIDE), stores.map { it.id }.toSet())
        // The Admin has both, told apart by their labels, each with its own ledger.
        assertEquals(listOf("FreshMart - Downtown", "FreshMart - Westside"), stores.map { it.label }.sorted())
        for (s in stores) assertTrue(admin.invoices().any { it.customerId == s.id && it.customerName == s.name })
        assertTrue(admin.balances().any { it.customerName == "FreshMart - Downtown" })
        assertTrue(admin.balances().any { it.customerName == "FreshMart - Westside" })
    }

    // ------------------------------------------------------------------ the Admin form rules

    @Test fun aSecondFreshMartNeedsADifferentLocation() = runBlocking {
        val msg = refused { admin.addCustomer(form("FreshMart", "downtown")) }
        assertTrue(msg, msg.contains("name and location already exists"))
        assertTrue(refused { admin.addCustomer(form(" freshmart ", " Downtown  ")) }.isNotBlank())
        val c = admin.addCustomer(form("FreshMart", "Midtown"))
        assertEquals("FreshMart - Midtown", c.label)
        assertTrue(w.newLog().single().after.contains("location 'Midtown'"))
    }

    @Test fun aLocationIsRequiredExceptForANewRetailName() = runBlocking {
        assertTrue(refused { admin.addCustomer(form("Curry Corner", "", ReferenceIds.TYPE_RESTAURANT)) }.contains("location"))
        assertTrue(refused { admin.addCustomer(form("Hall", "  ", ReferenceIds.TYPE_CATERING)) }.contains("location"))
        val walkInLike = admin.addCustomer(form("Nair Family", "", ReferenceIds.TYPE_RETAIL))
        assertEquals("Nair Family", walkInLike.label) // no location, label is just the name
        // The name now exists, so a second one needs a location, even in Retail.
        assertTrue(refused { admin.addCustomer(form("Nair Family", "", ReferenceIds.TYPE_RETAIL)) }.contains("already exists"))
        assertEquals("Nair Family - Coppell", admin.addCustomer(form("Nair Family", "Coppell", ReferenceIds.TYPE_RETAIL)).label)
    }

    @Test fun anEditKeepsItsOwnNameAndLocationButCannotTakeAnotherCustomers() = runBlocking {
        val downtown = admin.customers().first { it.id == DemoCustomerIds.FRESHMART_DOWNTOWN }
        val same = admin.updateCustomer(downtown.id, form("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP, PaymentMode.CREDIT).copy(phone = "555"))
        assertEquals("555", same.phone)
        refused { admin.updateCustomer(downtown.id, form("FreshMart", "Westside", ReferenceIds.TYPE_SHOP, PaymentMode.CREDIT)) }
        assertEquals("Downtown", admin.customer(downtown.id)!!.location)
    }

    @Test fun extraSpacesInTheLocationAreTidiedWhenSaved() = runBlocking {
        val c = admin.addCustomer(form("  Green   Leaf ", "  North   Dallas "))
        assertEquals("Green Leaf", c.name)
        assertEquals("North Dallas", c.location)
    }

    // ------------------------------------------------------------------ the Sales area (one database: a change is seen at once)

    @Test fun aNewLocationIsInTheSalesAreaAtOnce() = runBlocking {
        assertTrue(salesCustomers().none { it.location == "Midtown" })
        admin.addCustomer(form("FreshMart", "Midtown", mode = PaymentMode.CREDIT))
        val labels = salesCustomers().map { "${it.name} - ${it.location}" }
        assertTrue(labels.toString(), "FreshMart - Midtown" in labels)
        assertTrue("FreshMart - Downtown" in labels && "FreshMart - Westside" in labels)
    }

    @Test fun anEditedLocationIsInTheSalesAreaAtOnce() = runBlocking {
        val taj = admin.customers().first { it.name == "Taj Kitchen" }
        admin.updateCustomer(taj.id, form("Taj Kitchen", "Little Elm", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT))
        assertEquals("Little Elm", salesCustomers().first { it.id == taj.id }.location)
        assertFalse(salesCustomers().any { "${it.name} - ${it.location}" == "Taj Kitchen - Frisco" })
    }
}
