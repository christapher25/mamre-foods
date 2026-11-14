package com.mamre.billing.data.local

import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.worker.PaymentMethod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B6, Doc 1 s6.2 and s5.4: a void bill's payment pays the next bill, and it is still so after the database is closed and reopened. */
@RunWith(RobolectricTestRunner::class)
class AllocationPersistenceTest {
    private val file = "allocation-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    @Test fun theFreedPaymentStillPaysTheNextBillAfterTheDatabaseIsReopened() = runBlocking {
        var w = World(TestDatabase.file(file)).also { it.seed() }
        w.price(ReferenceIds.TYPE_RESTAURANT, 1_000, 600)
        val customer = w.customer("Spice Garden", "Irving", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT)
        val bill1 = w.makeBill(w.draft(customer.id, listOf(w.line(ReferenceIds.PRODUCT_FRESH, "Mamre Fresh Chapathi", qty = 1, unit = 1_000)), paid = 400, method = PaymentMethod.CASH))
        val bill2 = w.makeBill(w.draft(customer.id, listOf(w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = 1, unit = 600))))
        w.voidBill(bill1.id, "typo")
        assertEquals(200L, w.admin.customerBalance(customer.id)) // 6.00 owed, 4.00 already paid
        w.db.close()

        w = World(TestDatabase.file(file), w.clock)
        assertEquals(200L, w.admin.customerBalance(customer.id))
        assertEquals(200L, w.admin.invoiceDetail(bill2.id)!!.amountDueCents) // the 4.00 now pays bill 2
        w.db.close()
    }
}
