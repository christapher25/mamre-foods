package com.mamre.billing.print

import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.worker.PaymentMethod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B8 item g and L3, L12: every bill, payment and return stores its creator, and every print and reprint uses the stored name. */
@RunWith(RobolectricTestRunner::class)
class SalesCreatorTest {
    private val db = TestDatabase.inMemory()
    private val w = World(db)

    @After fun tearDown() = db.close()

    private suspend fun setUp(): String {
        w.seed()
        w.price(com.mamre.billing.data.local.ReferenceIds.TYPE_RESTAURANT, 250, 240)
        return w.customer("Cafe One").id
    }

    @Test fun aBillStoresItsCreatorAndTheDisplayNameAtThatMoment() = runBlocking {
        val id = setUp()
        val bill = w.makeBill(w.draft(id, listOf(w.line(qty = 2, unit = 250)), by = w.asha))
        val row = db.invoiceDao().get(bill.id)!!
        assertEquals(w.asha.id, row.createdByUserId)
        assertEquals("Asha Kumar", row.salesmanName)
        assertEquals("Asha Kumar", bill.salesmanName)
    }

    @Test fun thePaymentTakenWithABillAndAStandalonePaymentStoreTheirCreator() = runBlocking {
        val id = setUp()
        val bill = w.makeBill(w.draft(id, listOf(w.line(qty = 2, unit = 250)), paid = 500, method = PaymentMethod.CASH, by = w.asha))
        val withBill = db.paymentDao().forInvoice(bill.id).single()
        assertEquals(w.asha.id, withBill.createdByUserId)
        assertEquals("Asha Kumar", withBill.salesmanName)
        val standalone = w.recordPayment(w.payment(id, 300, by = w.actor))
        val row = db.paymentDao().getAll().first { it.id == standalone.id }
        assertEquals(w.actor.id, row.createdByUserId)
        assertEquals("Test Admin", row.salesmanName)
        assertEquals("Test Admin", standalone.salesmanName)
    }

    @Test fun aReturnStoresItsCreator() = runBlocking {
        val id = setUp()
        val bill = w.makeBill(w.draft(id, listOf(w.line(qty = 3, unit = 250))))
        w.recordReturn(w.returnOf(id, qty = 1, invoiceId = bill.id, by = w.asha))
        assertEquals(w.asha.id, db.returnDao().getAll().single().createdByUserId)
    }

    @Test fun aReprintPrintsTheOriginalNameNotTheCurrentAccountNorALaterRename() = runBlocking {
        val id = setUp()
        val bill = w.makeBill(w.draft(id, listOf(w.line(qty = 2, unit = 250)), by = w.asha))
        // Test only: version 1 has no rename screen.
        db.openHelper.writableDatabase.execSQL("UPDATE users SET display_name = 'Renamed Person' WHERE id = '${w.asha.id}'")
        val record = w.sales.invoiceRecord(bill.id)!!
        val lines = layoutInvoiceReceipt(invoiceReceiptOf(record, w.sales.state().ledgerOf(id), true, duplicate = true))
        assertTrue(lines.contains("Salesman: Asha Kumar"))
        assertTrue(lines.none { "Renamed Person" in it || "Test Admin" in it })
    }

    @Test fun thePaymentReceiptPrintsTheCreatorOfThePayment() = runBlocking {
        val id = setUp()
        val payment = w.recordPayment(w.payment(id, 300, by = w.asha))
        db.openHelper.writableDatabase.execSQL("UPDATE users SET display_name = 'Renamed Person' WHERE id = '${w.asha.id}'")
        val record = w.sales.paymentRecord(payment.id)!!
        val lines = layoutPaymentReceipt(paymentReceiptOf(record, 0, w.settings.businessHeader()))
        assertTrue(lines.contains("Salesman: Asha Kumar"))
    }
}
