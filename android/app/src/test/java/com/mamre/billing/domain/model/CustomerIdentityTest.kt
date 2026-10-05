package com.mamre.billing.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set D2: a customer is a name plus a location; the pair is unique; Retail may have no location. */
class CustomerIdentityTest {
    private val others = listOf(
        CustomerIdentity("1", "FreshMart", "Downtown"),
        CustomerIdentity("2", "Spice Garden", "Irving"),
        CustomerIdentity("3", "Rao Family", ""),
    )

    private fun check(name: String, location: String, type: String = "Shop", list: List<CustomerIdentity> = others) =
        checkCustomerIdentity(name, location, type, list)

    @Test fun theLabelIsNameDashLocationOrJustTheName() {
        assertEquals("FreshMart - Downtown", customerLabel("FreshMart", "Downtown"))
        assertEquals("Rao Family", customerLabel("Rao Family", ""))
        assertEquals("Rao Family", customerLabel("  Rao   Family ", "   "))
        assertEquals("FreshMart - West Side", customerLabel(" FreshMart ", " West   Side "))
    }

    @Test fun twoStoresOfOneChainAreTwoCustomers() {
        assertNull(check("FreshMart", "Westside"))
    }

    @Test fun theSamePairIsADuplicateIgnoringCaseAndExtraSpaces() {
        assertEquals(IdentityProblem.DUPLICATE, check("FreshMart", "Downtown"))
        assertEquals(IdentityProblem.DUPLICATE, check("  freshmart ", "DOWNTOWN"))
        assertEquals(IdentityProblem.DUPLICATE, check("FRESHMART", " downtown  "))
    }

    @Test fun editingACustomerDoesNotCollideWithItself() {
        val withoutSelf = others.filter { it.id != "1" }
        assertNull(check("FreshMart", "Downtown", list = withoutSelf))
    }

    @Test fun aNameIsAlwaysRequired() {
        assertEquals(IdentityProblem.NAME_REQUIRED, check("   ", "Downtown"))
        assertEquals(IdentityProblem.NAME_REQUIRED, check("", ""))
    }

    @Test fun retailMayHaveNoLocationWhenTheNameIsNew() {
        assertNull(check("Sharma Family", "", type = "Retail"))
        assertNull(check("Sharma Family", "   ", type = "retail"))
    }

    @Test fun otherTypesNeedALocation() {
        assertEquals(IdentityProblem.LOCATION_REQUIRED, check("Curry House", "", type = "Restaurant"))
        assertEquals(IdentityProblem.LOCATION_REQUIRED, check("Royal Banquets", " ", type = "Catering"))
        assertEquals(IdentityProblem.LOCATION_REQUIRED, check("Patel Mart", "", type = "Shop"))
    }

    @Test fun whenTheNameAlreadyExistsALocationIsRequiredEvenForRetail() {
        assertEquals(IdentityProblem.LOCATION_REQUIRED_NAME_EXISTS, check("Rao Family", "", type = "Retail"))
        assertEquals(IdentityProblem.LOCATION_REQUIRED_NAME_EXISTS, check("rao  family", "", type = "Retail"))
        assertEquals(IdentityProblem.LOCATION_REQUIRED_NAME_EXISTS, check("FreshMart", "", type = "Shop"))
        assertNull(check("Rao Family", "Coppell", type = "Retail")) // with a location the pair is new
    }

    @Test fun theMessagesExplainWhatToDo() {
        for (p in IdentityProblem.entries) assertTrue(identityProblemMessage(p).isNotBlank())
        assertTrue(identityProblemMessage(IdentityProblem.DUPLICATE).contains("already exists"))
        assertTrue(identityProblemMessage(IdentityProblem.LOCATION_REQUIRED_NAME_EXISTS).contains("location"))
    }

    @Test fun searchMatchesTheNameOrTheLocation() {
        assertTrue(customerMatches("FreshMart", "Downtown", "fresh"))
        assertTrue(customerMatches("FreshMart", "Downtown", "DOWN"))
        assertTrue(customerMatches("FreshMart", "Downtown", "mart down")) // both parts of the label
        assertTrue(customerMatches("FreshMart", "Downtown", "  "))
        assertTrue(!customerMatches("FreshMart", "Downtown", "westside"))
    }

    @Test fun theOneKeyIsTrimmedCollapsedAndCaseFoldedAndTheDatabaseColumnsUseIt() {
        assertEquals("spice garden", identityKey("  Spice   GARDEN "))
        assertEquals("", identityKey("   "))
        assertEquals(identityKey("FreshMart"), identityKey(" freshmart "))
    }
}
