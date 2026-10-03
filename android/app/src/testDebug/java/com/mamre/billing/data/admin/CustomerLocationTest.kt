package com.mamre.billing.data.admin

import com.mamre.billing.data.FakeCustomerDao
import com.mamre.billing.data.FakeCustomerTypeDao
import com.mamre.billing.data.FakePriceDefaultDao
import com.mamre.billing.data.FakePriceOverrideDao
import com.mamre.billing.data.FakeProductDao
import com.mamre.billing.data.FakeSyncStateDao
import com.mamre.billing.data.FakeTransactionRunner
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.label
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set D2: a customer is a name plus a location; the pair is unique; locations travel through the catalog and Sync now. */
class CustomerLocationTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val api = FakeApi(table)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeTransactionRunner(),
        CatalogRemote { cursor -> api.catalog(api.login("user1", "user1").access, cursor) },
    )

    private fun form(name: String, location: String, type: String = DemoIds.SHOP_TYPE, mode: PaymentMode = PaymentMode.CASH) =
        CustomerForm(name, type, "", "", mode, "", true, location = location)

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: AdminRuleException) {
            return e.message.orEmpty()
        }
        throw AssertionError("expected AdminRuleException")
    }

    // ------------------------------------------------------------------ the seed

    @Test fun everySeededCustomerHasALocationAndNameWithLocationIsUnique() {
        val rows = table.customers()
        assertTrue(rows.all { it.location.isNotBlank() })
        assertEquals(rows.size, rows.map { it.name.lowercase() to it.location.lowercase() }.toSet().size)
    }

    @Test fun freshMartIsTwoStoresOfOneChainOnCredit() = runTest {
        val stores = table.customers().filter { it.name == "FreshMart" }
        assertEquals(setOf("Downtown", "Westside"), stores.map { it.location }.toSet())
        assertTrue(stores.all { it.paymentMode == PaymentMode.CREDIT })
        assertEquals(setOf(DemoIds.FRESHMART_DOWNTOWN, DemoIds.FRESHMART_WESTSIDE), stores.map { it.id }.toSet())
        // The Admin has both, told apart by their labels, each with its own ledger.
        val adminStores = admin.customers().filter { it.name == "FreshMart" }
        assertEquals(listOf("FreshMart - Downtown", "FreshMart - Westside"), adminStores.map { it.label }.sorted())
        for (s in adminStores) {
            assertTrue(admin.invoices().any { it.customerId == s.id && it.customerName == s.label })
        }
        assertTrue(admin.balances().any { it.customerName == "FreshMart - Downtown" })
        assertTrue(admin.balances().any { it.customerName == "FreshMart - Westside" })
    }

    // ------------------------------------------------------------------ the Admin form rules

    @Test fun aSecondFreshMartNeedsADifferentLocation() = runTest {
        val msg = refused { admin.addCustomer(form("FreshMart", "downtown"), "Test Admin") }
        assertTrue(msg, msg.contains("name and location already exists"))
        assertTrue(refused { admin.addCustomer(form(" freshmart ", " Downtown  "), "Test Admin") }.isNotBlank())
        val c = admin.addCustomer(form("FreshMart", "Midtown"), "Test Admin")
        assertEquals("FreshMart - Midtown", c.label)
        assertTrue(admin.changeLog.value.single().after.contains("location 'Midtown'"))
    }

    @Test fun aLocationIsRequiredExceptForANewRetailName() = runTest {
        assertTrue(refused { admin.addCustomer(form("Curry Corner", "", DemoIds.RESTAURANT_TYPE), "Test Admin") }.contains("location"))
        assertTrue(refused { admin.addCustomer(form("Hall", "  ", DemoIds.CATERING_TYPE), "Test Admin") }.contains("location"))
        val walkInLike = admin.addCustomer(form("Nair Family", "", DemoIds.RETAIL_TYPE), "Test Admin")
        assertEquals("Nair Family", walkInLike.label) // no location, label is just the name
        // The name now exists, so a second one needs a location, even in Retail.
        assertTrue(refused { admin.addCustomer(form("Nair Family", "", DemoIds.RETAIL_TYPE), "Test Admin") }.contains("already exists"))
        assertEquals("Nair Family - Coppell", admin.addCustomer(form("Nair Family", "Coppell", DemoIds.RETAIL_TYPE), "Test Admin").label)
    }

    @Test fun anEditKeepsItsOwnNameAndLocationButCannotTakeAnotherCustomers() = runTest {
        val downtown = admin.customers().first { it.id == DemoIds.FRESHMART_DOWNTOWN }
        val same = admin.updateCustomer(downtown.id, form("FreshMart", "Downtown", DemoIds.SHOP_TYPE, PaymentMode.CREDIT).copy(phone = "555"), "Test Admin")
        assertEquals("555", same.phone)
        refused { admin.updateCustomer(downtown.id, form("FreshMart", "Westside", DemoIds.SHOP_TYPE, PaymentMode.CREDIT), "Test Admin") }
        assertEquals("Downtown", admin.customer(downtown.id)!!.location)
    }

    @Test fun extraSpacesInTheLocationAreTidiedWhenSaved() = runTest {
        val c = admin.addCustomer(form("  Green   Leaf ", "  North   Dallas "), "Test Admin")
        assertEquals("Green Leaf", c.name)
        assertEquals("North Dallas", c.location)
    }

    // ------------------------------------------------------------------ the catalog and Sync now

    @Test fun theLocationReachesTheSalesmanOnlyAfterSyncNow() = runTest {
        repo.refresh()
        assertTrue(repo.activeCustomers().none { it.location == "Midtown" })
        admin.addCustomer(form("FreshMart", "Midtown", mode = PaymentMode.CREDIT), "Test Admin")
        assertTrue(repo.activeCustomers().none { it.location == "Midtown" }) // not before the sync
        repo.refresh()
        val labels = repo.activeCustomers().map { it.label }
        assertTrue(labels.toString(), "FreshMart - Midtown" in labels)
        assertTrue("FreshMart - Downtown" in labels && "FreshMart - Westside" in labels)
    }

    @Test fun anEditedLocationReachesTheSalesmanAtTheNextSync() = runTest {
        repo.refresh()
        val taj = admin.customers().first { it.name == "Taj Kitchen" }
        admin.updateCustomer(taj.id, form("Taj Kitchen", "Little Elm", DemoIds.RESTAURANT_TYPE, PaymentMode.CREDIT), "Test Admin")
        assertEquals("Frisco", repo.activeCustomers().first { it.id == taj.id }.location)
        repo.refresh()
        assertEquals("Little Elm", repo.activeCustomers().first { it.id == taj.id }.location)
        assertFalse(repo.activeCustomers().any { it.label == "Taj Kitchen - Frisco" })
    }

}
