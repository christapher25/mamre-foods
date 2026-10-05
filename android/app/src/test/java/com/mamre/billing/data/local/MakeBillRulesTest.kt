package com.mamre.billing.data.local

import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.worker.PaymentMethod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bill rules enforced in the MakeBill use case, with the screen bypassed (Doc 1 s4.2, s4.3, s5.1, s5.2, s6.1;
 * Doc 2 I-2, I-3, I-7, I-13; AT-2, AT-13, AT-14). Each refused bill writes nothing and burns no number.
 */
@RunWith(RobolectricTestRunner::class)
class MakeBillRulesTest {
    private val file = "bill-rules-test.db"

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

    /** Restaurant 2.50 and 2.80, Retail 2.60 and 2.90 per standard packet. */
    private suspend fun priced(db: MamreDatabase = TestDatabase.inMemory()): World {
        val w = World(db)
        w.seed()
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 280)
        w.price(ReferenceIds.TYPE_RETAIL, 260, 290)
        return w
    }

    private suspend fun World.nothingWasWritten() {
        assertEquals(0, db.invoiceDao().count())
        assertEquals(0, db.invoiceItemDao().getAll().size)
        assertEquals(0, db.paymentDao().getAll().size)
        assertEquals("1", settings.get(SettingKeys.NEXT_BILL_SEQ))
        assertEquals("1", settings.get(SettingKeys.NEXT_RECEIPT_SEQ))
    }

    // ---------------------------------------------------------------- numbers (Doc 1 s5.2, Doc 2 I-3)

    @Test fun aBillThatFailsAValidationRuleDoesNotBurnANumber() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        // Restaurant may not change a price: refused after the other checks, and the sequence is untouched.
        refused { w.makeBill(w.draft(c.id, listOf(w.line(unit = 200, list = 250)))) }
        refused { w.makeBill(w.draft(c.id, emptyList())) }
        w.nothingWasWritten()
        val first = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        assertEquals("MAM-W1-0001", first.number)
        assertEquals("2", w.settings.get(SettingKeys.NEXT_BILL_SEQ))
    }

    @Test fun twoBillsInARowGetConsecutiveNumbersAfterTheDatabaseIsClosedAndReopened() = runBlocking {
        var w = priced(TestDatabase.file(file))
        val c = w.customer("Spice Garden", "Irving")
        val a = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        w.db.close()
        w = World(TestDatabase.file(file))
        val b = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        w.db.close()
        w = World(TestDatabase.file(file))
        val third = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        assertEquals(listOf("MAM-W1-0001", "MAM-W1-0002", "MAM-W1-0003"), listOf(a.number, b.number, third.number))
        assertEquals(listOf("MAM-W1-0001", "MAM-W1-0002", "MAM-W1-0003"), w.sales.state().invoices.map { it.number })
        w.db.close()
    }

    @Test fun aVoidedBillKeepsItsNumberAndTheNextBillDoesNotReuseIt() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        val a = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        w.voidBill(a.id, "wrong customer")
        val b = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        assertEquals("MAM-W1-0001", a.number)
        assertEquals("MAM-W1-0002", b.number)
        assertEquals(listOf("MAM-W1-0001", "MAM-W1-0002"), w.db.invoiceDao().getAll().map { it.number })
    }

    @Test fun confirmingTheSameDraftTwiceStoresOneBillAndOneNumber() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        val d = w.draft(c.id, listOf(w.line(unit = 250)), paid = 1000, method = PaymentMethod.CASH)
        val a = w.makeBill(d)
        val b = w.makeBill(d)
        assertEquals(a.number, b.number)
        assertEquals(1, w.db.invoiceDao().count())
        assertEquals(1, w.db.paymentDao().getAll().size)
        assertEquals("2", w.settings.get(SettingKeys.NEXT_BILL_SEQ))
    }

    // ---------------------------------------------------------------- prices (Doc 1 s4.2, s4.3)

    @Test fun noPriceBlocksTheSaleAndNeverBecomesZero() = runBlocking {
        val w = World(TestDatabase.inMemory())
        w.seed()
        val c = w.customer("Spice Garden", "Irving") // no price at all
        assertTrue(refused { w.makeBill(w.draft(c.id, listOf(w.line(unit = 250)))) }.contains("No price"))
        w.nothingWasWritten()
        // A product priced for one type is still unpriced for another.
        w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_RETAIL, 260, w.today.minusDays(1))
        assertTrue(refused { w.makeBill(w.draft(c.id, listOf(w.line(unit = 260)))) }.contains("No price"))
        w.nothingWasWritten()
    }

    @Test fun aPriceThatStartsInTheFutureDoesNotPriceTodaysBill() = runBlocking {
        val w = World(TestDatabase.inMemory())
        w.seed()
        val c = w.customer("Spice Garden", "Irving")
        w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_RESTAURANT, 250, w.today.plusDays(5))
        assertTrue(refused { w.makeBill(w.draft(c.id, listOf(w.line(unit = 250)))) }.contains("No price"))
    }

    @Test fun theListPriceOnALineMustBeTheCurrentPriceList() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        assertTrue(refused { w.makeBill(w.draft(c.id, listOf(w.line(unit = 300, list = 300)))) }.contains("changed"))
        w.nothingWasWritten()
    }

    @Test fun anOverrideBeatsTheDefaultAndItsPriceIsTheListPrice() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        w.setOverride(c.id, ReferenceIds.PRODUCT_FRESH, 200, w.today.minusDays(1), "regular customer")
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(unit = 200, list = 200))))
        assertEquals(2000L, bill.totalCents)
        assertFalse(bill.lines.single().priceOverridden)
        // The default no longer matches for this customer and product.
        assertTrue(refused { w.makeBill(w.draft(c.id, listOf(w.line(unit = 250)))) }.contains("changed"))
        // Clearing the override puts the default back.
        w.clearOverride(c.id, ReferenceIds.PRODUCT_FRESH)
        assertEquals(2500L, w.makeBill(w.draft(c.id, listOf(w.line(unit = 250)))).totalCents)
    }

    @Test fun aCustomPacketPriceRoundsHalfUpAndTheListPriceMustMatchIt() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        // 250 per packet of 12: 10 chapathis 208, 9 chapathis 188 (AT-14).
        val ok = w.makeBill(w.draft(c.id, listOf(w.line(qty = 1, unit = 208, size = 10), w.line(qty = 1, unit = 188, size = 9))))
        assertEquals(396L, ok.totalCents)
        assertTrue(refused { w.makeBill(w.draft(c.id, listOf(w.line(qty = 1, unit = 200, size = 10)))) }.contains("changed"))
        // 230 per 12 gives 192 for 10 chapathis.
        w.setDefaultPrice(ReferenceIds.PRODUCT_FRESH, ReferenceIds.TYPE_RESTAURANT, 230, w.today.plusDays(1))
        w.clock.advanceMinutes(60 * 24 * 2)
        assertEquals(192L, w.makeBill(w.draft(c.id, listOf(w.line(qty = 1, unit = 192, size = 10)))).totalCents)
    }

    @Test fun aLockedTypeCannotChangeAPriceEvenIfTheScreenIsBypassed() = runBlocking {
        val w = priced()
        val restaurant = w.customer("Spice Garden", "Irving", ReferenceIds.TYPE_RESTAURANT)
        val shop = w.customer("Patel Mart", "Mesquite", ReferenceIds.TYPE_SHOP)
        w.price(ReferenceIds.TYPE_SHOP, 255, 285)
        assertTrue(refused { w.makeBill(w.draft(restaurant.id, listOf(w.line(unit = 200, list = 250)))) }.contains("cannot be changed"))
        assertTrue(refused { w.makeBill(w.draft(shop.id, listOf(w.line(unit = 300, list = 255)))) }.contains("cannot be changed"))
        w.nothingWasWritten()
    }

    @Test fun anEditableTypeStoresTheListPriceAndTheFlagWithinTheLimits() = runBlocking {
        val w = priced()
        val retail = w.customer("Rao Family", "Coppell", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH)
        val bill = w.makeBill(w.draft(retail.id, listOf(w.line(qty = 2, unit = 247, list = 260)), paid = 494, method = PaymentMethod.CASH))
        val item = w.db.invoiceItemDao().forInvoice(bill.id).single()
        assertEquals(260L, item.listPriceCents)
        assertEquals(247L, item.unitPriceCents)
        assertTrue(item.priceOverridden)
        assertEquals(494L, item.lineTotalCents)
        // Exactly 10 times the list price is allowed; one cent more is refused (Doc 1 s4.3).
        w.makeBill(w.draft(retail.id, listOf(w.line(qty = 1, unit = 2600, list = 260)), paid = 2600, method = PaymentMethod.CASH))
        assertTrue(refused { w.makeBill(w.draft(retail.id, listOf(w.line(qty = 1, unit = 2601, list = 260)), paid = 2601, method = PaymentMethod.CASH)) }.contains("10 times"))
        // A price of one cent is above zero and allowed; the InvoiceLine type itself cannot hold zero or less.
        w.makeBill(w.draft(retail.id, listOf(w.line(qty = 1, unit = 1, list = 260)), paid = 1, method = PaymentMethod.CASH))
        try {
            w.line(unit = 0, list = 260)
            fail("a zero price must never become a line")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun aWalkInFollowsTheRetailSwitchAndItIsReadFromTheDatabase() = runBlocking {
        val w = priced()
        val on = w.makeBill(w.draft(null, listOf(w.line(qty = 1, unit = 200, list = 260)), paid = 200, method = PaymentMethod.CASH))
        assertEquals("Walk-in", w.db.invoiceDao().get(on.id)!!.customerName)
        assertNull(w.db.invoiceDao().get(on.id)!!.customerId)
        w.setCanEditPrice(ReferenceIds.TYPE_RETAIL, false)
        assertTrue(refused { w.makeBill(w.draft(null, listOf(w.line(qty = 1, unit = 200, list = 260)), paid = 200, method = PaymentMethod.CASH)) }.contains("cannot be changed"))
    }

    // ---------------------------------------------------------------- payment at the sale (Doc 1 s4.1, s6.1)

    @Test fun aWalkInMustPayInFull() = runBlocking {
        val w = priced()
        val lines = listOf(w.line(qty = 2, unit = 260))
        assertTrue(refused { w.makeBill(w.draft(null, lines)) }.contains("paid in full"))
        assertTrue(refused { w.makeBill(w.draft(null, lines, paid = 100, method = PaymentMethod.CASH)) }.contains("paid in full"))
        assertTrue(refused { w.makeBill(w.draft(null, lines, paid = 600, method = PaymentMethod.CASH)) }.contains("paid in full"))
        w.nothingWasWritten()
        val bill = w.makeBill(w.draft(null, lines, paid = 520, method = PaymentMethod.ZELLE))
        assertEquals(520L, bill.paidNowCents)
        assertEquals(0L, bill.balanceAfterCents)
    }

    @Test fun aCashCustomerPaysAtLeastTheTotalAndACreditCustomerMayPayPart() = runBlocking {
        val w = priced()
        val cash = w.customer("Corner Shop", "Richardson", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CASH)
        val credit = w.customer("Spice Garden", "Irving", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT)
        val lines = listOf(w.line(qty = 4, unit = 250))
        assertTrue(refused { w.makeBill(w.draft(cash.id, lines, paid = 999, method = PaymentMethod.CASH)) }.contains("at least"))
        assertEquals(0L, w.makeBill(w.draft(cash.id, lines, paid = 1000, method = PaymentMethod.CASH)).balanceAfterCents)
        assertEquals(1000L, w.makeBill(w.draft(credit.id, lines)).balanceAfterCents) // nothing paid
        // The second bill adds to the first: 1000 owed + 1000 billed - 300 paid.
        assertEquals(1700L, w.makeBill(w.draft(credit.id, lines, paid = 300, method = PaymentMethod.CHECK)).balanceAfterCents)
    }

    @Test fun aPaymentNeedsAMethodAndNoPaymentHasNone() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        val lines = listOf(w.line(qty = 1, unit = 250))
        assertTrue(refused { w.makeBill(w.draft(c.id, lines, paid = 100)) }.contains("how"))
        assertTrue(refused { w.makeBill(w.draft(c.id, lines, paid = 0, method = PaymentMethod.CASH)) }.isNotEmpty())
        w.nothingWasWritten()
    }

    @Test fun theBillSavesTheCustomersNameLocationAndCorporateFlagFromTheDatabase() = runBlocking {
        val w = priced()
        val c = w.customer("FreshMart", "Downtown", ReferenceIds.TYPE_RESTAURANT, corporate = true)
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        val row = w.db.invoiceDao().get(bill.id)!!
        assertEquals("FreshMart", row.customerName)
        assertEquals("Downtown", row.customerLocation)
        assertTrue(row.isCorporate)
        // A later edit of the customer never changes the saved bill (Doc 2 I-7).
        w.editCustomer(c.id, com.mamre.billing.domain.admin.CustomerForm("FreshMart", c.typeId, "", "", c.paymentMode, "", true, 0, "Uptown", false))
        val after = w.db.invoiceDao().get(bill.id)!!
        assertEquals("Downtown", after.customerLocation)
        assertTrue(after.isCorporate)
    }

    @Test fun aMissingOrInactiveCustomerIsRefused() = runBlocking {
        val w = priced()
        assertTrue(refused { w.makeBill(w.draft("no-such-customer", listOf(w.line(unit = 250)))) }.isNotEmpty())
        val c = w.customer("Spice Garden", "Irving")
        w.editCustomer(c.id, com.mamre.billing.domain.admin.CustomerForm("Spice Garden", c.typeId, "", "", c.paymentMode, "", false, 0, "Irving", false))
        assertTrue(refused { w.makeBill(w.draft(c.id, listOf(w.line(unit = 250)))) }.contains("not active"))
        w.nothingWasWritten()
        assertNotNull(w.customers.customer(c.id)) // kept, never deleted
    }

    // ---------------------------------------------------------------- the other sales use cases

    @Test fun aPaymentWithNoBillNeedsAnAmountAboveZeroAndANoteForOther() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        assertTrue(refused { w.recordPayment(w.payment(c.id, 0)) }.isNotEmpty())
        assertTrue(refused { w.recordPayment(w.payment(c.id, 500, PaymentMethod.OTHER, note = "  ")) }.contains("note"))
        assertTrue(refused { w.recordPayment(w.payment("nobody", 500)) }.isNotEmpty())
        assertEquals(0, w.db.paymentDao().getAll().size)
        assertEquals("1", w.settings.get(SettingKeys.NEXT_RECEIPT_SEQ))
        val r = w.recordPayment(w.payment(c.id, 500, PaymentMethod.OTHER, note = "money order"))
        assertEquals("RCP-W1-0001", r.receiptNumber)
        assertEquals("money order", r.note)
        val again = w.recordPayment(w.payment(c.id, 700, id = r.id)) // the same id stores once
        assertEquals(500L, again.amountCents)
    }

    @Test fun aCreditReturnLowersTheBalanceAndAReplacementDoesNot() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        w.makeBill(w.draft(c.id, listOf(w.line(qty = 10, unit = 250))))
        assertEquals(2500L, w.sales.state().balanceOf(c.id))
        w.recordReturn(w.returnOf(c.id, qty = 2, credit = true))
        assertEquals(2000L, w.sales.state().balanceOf(c.id))
        w.recordReturn(w.returnOf(c.id, qty = 3, credit = false))
        assertEquals(2000L, w.sales.state().balanceOf(c.id))
        assertTrue(refused { w.recordReturn(w.returnOf(c.id, qty = 0)) }.isNotEmpty())
        assertEquals(2, w.db.returnDao().getAll().size)
    }

    @Test fun aVoidNeedsAReasonAndAVoidedBillCannotBeVoidedAgain() = runBlocking {
        val w = priced()
        val c = w.customer("Spice Garden", "Irving")
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(unit = 250))))
        assertTrue(refused { w.voidBill(bill.id, "   ") }.contains("reason"))
        assertEquals("active", w.db.invoiceDao().get(bill.id)!!.status)
        w.voidBill(bill.id, "  entered twice ")
        assertTrue(refused { w.voidBill(bill.id, "again") }.contains("already void"))
        assertTrue(refused { w.voidBill("missing", "x") }.isNotEmpty())
        val row = w.db.invoiceDao().get(bill.id)!!
        assertEquals("void", row.status)
        assertEquals("entered twice", row.voidReason)
        assertEquals(2500L, row.totalCents) // nothing else about the bill changed
    }
}
