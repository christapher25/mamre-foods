package com.mamre.billing.domain.worker

import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.label
import org.junit.Assert.assertEquals
import org.junit.Test

/** Change set D2: the salesman picker shows "Name - Location" and searches either part. */
class CustomerPickerTest {
    private fun c(id: String, name: String, location: String) =
        Customer(id, name, "t", "", "", PaymentMode.CREDIT, true, location)

    private val all = listOf(
        c("3", "Spice Garden", "Irving"),
        c("2", "FreshMart", "Westside"),
        c("1", "FreshMart", "Downtown"),
        c("4", "Rao Family", ""),
    )

    @Test fun theListIsSortedByNameThenLocation() {
        assertEquals(listOf("1", "2", "4", "3"), filterCustomers(all, "").map { it.id })
    }

    @Test fun theLabelIsNameDashLocation() {
        assertEquals(
            listOf("FreshMart - Downtown", "FreshMart - Westside", "Rao Family", "Spice Garden - Irving"),
            filterCustomers(all, "").map { it.label },
        )
    }

    @Test fun searchMatchesTheNameTheLocationOrBoth() {
        assertEquals(listOf("1", "2"), filterCustomers(all, "fresh").map { it.id })
        assertEquals(listOf("1"), filterCustomers(all, "downtown").map { it.id })
        assertEquals(listOf("3"), filterCustomers(all, "IRVING").map { it.id })
        assertEquals(listOf("2"), filterCustomers(all, "fresh west").map { it.id })
        assertEquals(emptyList<String>(), filterCustomers(all, "nowhere"))
    }
}
