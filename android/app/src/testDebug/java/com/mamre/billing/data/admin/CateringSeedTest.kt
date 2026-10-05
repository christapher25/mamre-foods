package com.mamre.billing.data.admin

import android.database.sqlite.SQLiteConstraintException
import com.mamre.billing.data.demo.DemoWorld
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.RuleException
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Change set C1 on the real database: four customer types, Catering seeded, flag defaults, one id per customer. */
@RunWith(RobolectricTestRunner::class)
class CateringSeedTest {
    private lateinit var w: World
    private val api get() = w.admin
    private val today get() = w.today

    @Before fun open() {
        w = runBlocking { DemoWorld.open() }
    }

    @After fun close() {
        w.db.close()
    }

    @Test fun thereAreFourTypesWithCateringAndRetailEditableByDefault() = runBlocking {
        assertEquals(listOf("Catering", "Restaurant", "Retail", "Shop").sorted(), api.customerTypes().map { it.name }.sorted())
        assertEquals(
            mapOf("Restaurant" to false, "Shop" to false, "Retail" to true, "Catering" to true),
            api.customerTypes().associate { it.name to it.workerCanEditPrice },
        )
    }

    @Test fun cateringHasACustomerWithInvoicesAndPricesForEveryProduct() = runBlocking {
        val royal = api.customers().single { it.id == SeedIds.ROYAL_BANQUETS }
        assertEquals("Catering", royal.typeName)
        assertTrue(api.invoices().any { it.customerId == royal.id && it.typeName == "Catering" })
        val matrix = api.priceMatrix()
        for (p in listOf(ReferenceIds.PRODUCT_FRESH, ReferenceIds.PRODUCT_CHAPATHI)) {
            assertTrue(matrix.current(p, ReferenceIds.TYPE_CATERING, today) != null)
        }
        val d = api.dashboard(YearMonth.of(2026, 9))
        assertTrue(d.salesByCustomerType.any { it.label == "Catering" && it.cents > 0 })
    }

    @Test fun aWalkInFollowsTheRetailTypeInTheSeed() = runBlocking {
        val walkIns = api.invoices().filter { it.customerId == null }
        assertTrue(walkIns.isNotEmpty() && walkIns.all { it.typeName == "Retail" })
    }

    /** Replaces "the worker edit flag bumps the version only when it changes": there is no sync version now, the use case refuses a no-op. */
    @Test fun theWorkerEditFlagIsRefusedWhenItDoesNotChangeAndLoggedWhenItDoes() = runBlocking {
        try {
            api.setWorkerCanEditPrice(ReferenceIds.TYPE_RETAIL, true) // already on
            fail("a no-op was accepted")
        } catch (_: RuleException) {
        }
        assertTrue(w.newLog().isEmpty())
        api.setWorkerCanEditPrice(ReferenceIds.TYPE_RETAIL, false)
        assertEquals(false, api.customerTypes().first { it.id == ReferenceIds.TYPE_RETAIL }.workerCanEditPrice)
        assertEquals(1, w.newLog().size)
    }

    @Test fun aNewCustomerNeedsAKnownTypeAndAUniqueId() = runBlocking {
        val before = api.customers().size
        val first = api.customers().first()
        val form = CustomerForm("Brand New", ReferenceIds.TYPE_SHOP, "", "", PaymentMode.CASH, "", true, location = "Plano")
        try {
            w.addCustomer(form, first.id) // an id that exists
            fail("duplicate id accepted")
        } catch (_: SQLiteConstraintException) {
        }
        try {
            w.addCustomer(form.copy(typeId = "nope"))
            fail("unknown type accepted")
        } catch (_: RuleException) {
        }
        assertEquals(before, api.customers().size)
        assertTrue(w.newLog().isEmpty())
    }
}
