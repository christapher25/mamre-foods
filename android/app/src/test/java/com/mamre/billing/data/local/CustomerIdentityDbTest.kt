package com.mamre.billing.data.local

import android.database.sqlite.SQLiteConstraintException
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.RuleException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Doc 1 s4.1, Doc 2 s4.2: a customer is unique on the normalised name PLUS the normalised location (case-folded, trimmed,
 * inner spaces collapsed); a missing location is its own empty key and is required when the name already exists.
 */
@RunWith(RobolectricTestRunner::class)
class CustomerIdentityDbTest {
    private val file = "identity-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    private suspend fun world(): World = World(TestDatabase.inMemory()).also { it.seed() }

    @Test fun freshMartAtDowntownAndAtWestsideAreTwoCustomers() = runBlocking {
        val w = world()
        w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP)
        w.customer("FreshMart", "Westside", ReferenceIds.TYPE_SHOP)
        assertEquals(listOf("Downtown", "Westside"), w.customers.customers().map { it.location })
        assertEquals(2, w.customers.customers().map { it.id }.toSet().size)
    }

    @Test fun theSameNameAndLocationIgnoringCaseAndExtraSpacesIsRefused() = runBlocking {
        val w = world()
        w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP)
        assertTrue(refused { w.customer("freshmart ", "downtown", ReferenceIds.TYPE_SHOP) }.contains("already exists"))
        assertTrue(refused { w.customer("  FRESHMART", " downtown", ReferenceIds.TYPE_SHOP) }.isNotEmpty())
        w.customer("Spice  Garden", "Irving")
        assertTrue(refused { w.customer(" spice garden ", "IRVING") }.isNotEmpty())
        assertEquals(2, w.customers.customers().size)
    }

    @Test fun theKeysAreCaseFoldedTrimmedAndInnerSpacesCollapsed() = runBlocking {
        val w = world()
        w.customer("  Spice   GARDEN ", "  Irving  Texas ")
        val row = w.customers.customers().single()
        assertEquals("Spice GARDEN", row.name) // shown as typed, spaces cleaned
        assertEquals("spice garden", row.nameKey)
        assertEquals("irving texas", row.locationKey)
    }

    @Test fun aMissingLocationIsItsOwnEmptyKeyAndIsRequiredWhenTheNameExists() = runBlocking {
        val w = world()
        // Retail may have no location while the name is new (Doc 1 s4.1).
        w.customer("Rao Family", "", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH)
        assertEquals("", w.customers.customers().single().locationKey)
        // The same name again with no location is refused: a location is required when the name already exists.
        assertTrue(refused { w.customer("rao family ", "", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH) }.contains("location"))
        // With a location it is a different customer.
        w.customer("Rao Family", "Plano", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH)
        assertEquals(2, w.customers.customers().size)
        // Every other type needs a location, even for a new name.
        assertTrue(refused { w.customer("Taj Kitchen", "", ReferenceIds.TYPE_RESTAURANT) }.contains("location"))
        assertTrue(refused { w.customer("Royal Banquets", "", ReferenceIds.TYPE_CATERING) }.contains("location"))
        assertTrue(refused { w.customer("Patel Mart", "", ReferenceIds.TYPE_SHOP) }.contains("location"))
    }

    @Test fun aNameIsRequired() = runBlocking {
        val w = world()
        assertTrue(refused { w.customer("   ", "Irving") }.isNotEmpty())
        assertEquals(0, w.customers.customers().size)
    }

    @Test fun theDatabaseItselfRefusesTheSameKeysEvenIfAUseCaseIsBypassed() = runBlocking {
        val w = world()
        val c = w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP)
        val copy = w.customers.customer(c.id)!!.copy(id = "another-id", name = "FRESHMART", location = "DOWNTOWN")
        try {
            w.db.customerDao().insert(copy)
            fail("the unique index on name_key and location_key must refuse this")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals(1, w.customers.customers().size)
    }

    @Test fun anEditIsNotComparedWithItselfButCannotClashWithAnotherCustomer() = runBlocking {
        val w = world()
        val a = w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP)
        val b = w.customer("FreshMart", "Westside", ReferenceIds.TYPE_SHOP)
        fun form(c: com.mamre.billing.domain.admin.AdminCustomer, location: String = c.location, phone: String = c.phone) =
            com.mamre.billing.domain.admin.CustomerForm(c.name, c.typeId, phone, c.address, c.paymentMode, c.notes, c.isActive, 0, location, c.isCorporate)
        w.editCustomer(a.id, form(a, phone = "555-0100")) // same name and location: the customer itself
        assertEquals("555-0100", w.customers.customer(a.id)!!.phone)
        assertTrue(refused { w.editCustomer(b.id, form(b, location = "downtown ")) }.isNotEmpty())
        assertEquals("Westside", w.customers.customer(b.id)!!.location)
    }

    @Test fun theRulesStillHoldAfterTheDatabaseIsClosedAndReopened() = runBlocking {
        var w = World(TestDatabase.file(file)).also { it.seed() }
        w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_SHOP)
        w.db.close()
        w = World(TestDatabase.file(file))
        assertTrue(refused { w.customer("freshmart", "DOWNTOWN ", ReferenceIds.TYPE_SHOP) }.isNotEmpty())
        w.customer("FreshMart", "Westside", ReferenceIds.TYPE_SHOP)
        assertEquals(setOf("downtown", "westside"), w.customers.customers().map { it.locationKey }.toSet())
        w.db.close()
    }
}
