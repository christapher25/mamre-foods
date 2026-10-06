package com.mamre.billing.data.local

import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.SetDeviceCode
import com.mamre.billing.domain.usecase.SetLockMinutes
import com.mamre.billing.domain.usecase.SetOwnerName
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.print.invoiceReceiptOf
import com.mamre.billing.print.layoutInvoiceReceipt
import com.mamre.billing.print.layoutPaymentReceipt
import com.mamre.billing.print.paymentReceiptOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Admin > Settings (Doc 1 s13 P-6, P-12, A-29, A-31; Doc 2 s4.2): every setting is validated, saved through a use case with a
 * change-log entry, and READ BACK AFTER THE DATABASE IS CLOSED AND OPENED AGAIN. The bill header and the Salesman line come from
 * these settings and from nothing in the code.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val file = "settings-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun first(): World = World(TestDatabase.file(file)).also { it.seed() }

    private fun reopen(w: World): World {
        w.db.close()
        return World(TestDatabase.file(file), w.clock)
    }

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    private fun World.setOwner() = SetOwnerName(unitOfWork, settings, changeLog, clock)
    private fun World.setCode() = SetDeviceCode(unitOfWork, settings, changeLog, clock)
    private fun World.setLock() = SetLockMinutes(unitOfWork, settings, changeLog, clock)

    private fun World.cashBill() = draft(null, listOf(line(qty = 1, unit = 260)), paid = 260, method = PaymentMethod.CASH)

    // ---------------------------------------------------------------- owner name

    @Test fun theOwnerNameIsTwoToFortyCharactersAndSurvivesReopening() = runBlocking {
        var w = first()
        assertTrue(refused { w.setOwner()("A") }.contains("2 to 40"))
        assertTrue(refused { w.setOwner()("x".repeat(41)) }.contains("2 to 40"))
        assertEquals("", w.settings.ownerName())
        w.setOwner()("Jo")
        w.setOwner()("x".repeat(40))
        w.setOwner()(" Rajesh  Thomas ")
        w = reopen(w)
        assertEquals("Rajesh Thomas", w.settings.ownerName())
    }

    // ---------------------------------------------------------------- business details

    @Test fun theBusinessDetailsAreValidatedAndSurviveReopening() = runBlocking {
        var w = first()
        val ok = BusinessSettings("Mamre Foods", "9 Sample Road\nOtherville, TX 11111", "+1 (111) 111-1111", "Come again!")
        assertTrue(refused { w.saveSettings(ok.copy(businessName = "  ")) }.contains("name"))
        assertTrue(refused { w.saveSettings(ok.copy(businessName = "x".repeat(41))) }.contains("40"))
        assertTrue(refused { w.saveSettings(ok.copy(address = (1..5).joinToString("\n") { "Line $it" })) }.contains("4 lines"))
        assertTrue(refused { w.saveSettings(ok.copy(address = "x".repeat(41))) }.contains("40"))
        assertTrue(refused { w.saveSettings(ok.copy(phone = "1".repeat(21))) }.contains("20"))
        assertTrue(refused { w.saveSettings(ok.copy(footerText = "x".repeat(81))) }.contains("80"))
        assertEquals("", w.settings.businessHeader().phone) // nothing was changed by the refusals
        w.saveSettings(ok)
        w = reopen(w)
        val header = w.settings.businessHeader()
        assertEquals("Mamre Foods", header.name)
        assertEquals(listOf("9 Sample Road", "Otherville, TX 11111"), header.addressLines)
        assertEquals("+1 (111) 111-1111", header.phone)
        assertEquals("Come again!", header.footer)
        assertEquals("Edit business settings", w.books.changeLog().first().what)
    }

    // ---------------------------------------------------------------- wastage

    @Test fun wastageIsZeroToFivePercentDefaultTwoAndSurvivesReopening() = runBlocking {
        var w = first()
        assertEquals(200, w.settings.wastageBp())
        assertTrue(refused { w.setWastage(501) }.contains("between"))
        assertTrue(refused { w.setWastage(-1) }.contains("between"))
        w.setWastage(0)
        w.setWastage(500)
        w.setWastage(350)
        w = reopen(w)
        assertEquals(350, w.settings.wastageBp()) // stored in basis points
        assertEquals("350", w.settings.get(SettingKeys.WASTAGE_BP))
        assertEquals("Wastage", w.books.changeLog().first().what)
    }

    // ---------------------------------------------------------------- lock minutes

    @Test fun theLockTimeSurvivesReopeningAndTheDefaultIsFive() = runBlocking {
        var w = first()
        assertEquals(5, w.settings.lockMinutes())
        w.setLock()(12)
        w = reopen(w)
        assertEquals(12, w.settings.lockMinutes())
    }

    @Test fun theLockTimeSettingDrivesTheAppLock() = runBlocking {
        val w = first()
        val lock = AppLock(w.clock)
        lock.unlock()
        w.clock.advanceMinutes(4)
        assertFalse(lock.checkIdle(w.settings.lockMinutes())) // five minutes by default: still open after four
        w.setLock()(2)
        assertTrue(lock.checkIdle(w.settings.lockMinutes())) // two minutes now: four is too long
        lock.unlock()
        w.setLock()(10)
        w.clock.advanceMinutes(9)
        assertFalse(lock.checkIdle(w.settings.lockMinutes()))
        w.clock.advanceMinutes(1)
        assertTrue(lock.checkIdle(w.settings.lockMinutes()))
        // Coming back from the background uses the same setting.
        lock.unlock()
        w.setLock()(1)
        w.clock.advanceMinutes(1)
        lock.onForegrounded(w.settings.lockMinutes())
        assertTrue(lock.locked.value)
    }

    // ---------------------------------------------------------------- device code

    @Test fun theDeviceCodeIsUppercaseLettersAndDigitsOneToEightAndTypedLowercaseIsNormalised() = runBlocking {
        var w = first()
        assertEquals("W1", w.settings.deviceCode())
        for (bad in listOf("", "   ", "ABCDEFGHI", "AB-1", "A B", "W_1", "É1", "W1!")) {
            assertTrue("'$bad'", refused { w.setCode()(bad) }.contains("1 to 8"))
        }
        assertEquals("W1", w.settings.deviceCode())
        w.setCode()("a")
        w.setCode()("12345678")
        w.setCode()(" shop2 ")
        w = reopen(w)
        assertEquals("SHOP2", w.settings.deviceCode())
        val log = w.books.changeLog().first()
        assertEquals("Device code", log.what)
        assertEquals("12345678", log.before)
        assertEquals("SHOP2", log.after)
    }

    @Test fun changingTheDeviceCodeOnlyAffectsBillsMadeAfterwards() = runBlocking {
        val w = first()
        w.price(ReferenceIds.TYPE_RETAIL, 260, 290)
        val a = w.makeBill(w.cashBill())
        val b = w.makeBill(w.cashBill())
        assertEquals(listOf("MAM-W1-0001", "MAM-W1-0002"), listOf(a.number, b.number))
        w.setCode()("ab7")
        val c = w.makeBill(w.cashBill())
        assertEquals("MAM-AB7-0003", c.number) // the sequence goes on, the code is the new one
        val stored = w.sales.state().invoices.associate { it.id to it.number }
        assertEquals("MAM-W1-0001", stored[a.id]) // bills already made never change
        assertEquals("MAM-W1-0002", stored[b.id])
        assertEquals("MAM-AB7-0003", stored[c.id])
        assertTrue(Regex("^MAM-[A-Z0-9]+-[0-9]{4,}$").matches(c.number))
    }

    // ---------------------------------------------------------------- the bill reads the settings only

    @Test fun theHeaderAndTheSalesmanLineComeFromTheSettings() = runBlocking {
        var w = first()
        w.price(ReferenceIds.TYPE_RETAIL, 260, 290)
        w.saveSettings(BusinessSettings("Test Kitchen", "1 Test Road", "555-0100", "Thanks!"))
        w.setOwner()("Pat Tester")
        w = reopen(w)
        val invoice = w.makeBill(w.cashBill())
        val lines = layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false, w.settings.businessHeader()))
        assertTrue(lines.any { it.trim() == "Test Kitchen" })
        assertTrue(lines.any { it.trim() == "1 Test Road" })
        assertTrue(lines.any { it.trim() == "Ph: 555-0100" })
        assertTrue(lines.contains("Salesman: Pat Tester"))
        assertTrue(lines.any { it.trim() == "Thanks!" })
        // A later change reaches the next bill; the saved bill keeps the salesman it was made with.
        w.setOwner()("Sam Other")
        val next = w.makeBill(w.cashBill())
        assertTrue(layoutInvoiceReceipt(invoiceReceiptOf(next, emptyList(), false, w.settings.businessHeader())).contains("Salesman: Sam Other"))
        assertTrue(layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false, w.settings.businessHeader())).contains("Salesman: Pat Tester"))
    }

    @Test fun withNothingSavedTheBillPrintsPlaceholdersAndNeverTheDeviceCode() = runBlocking {
        val w = first()
        w.price(ReferenceIds.TYPE_RETAIL, 260, 290)
        val invoice = w.makeBill(w.cashBill())
        val lines = layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false, w.settings.businessHeader()))
        assertTrue(lines.contains("Salesman: (owner name pending)"))
        assertTrue(lines.any { it.contains("(address / phone pending)") })
        assertFalse(lines.any { it.startsWith("Salesman:") && it.contains("W1") })
        val noName = w.settings.businessHeader().copy(name = "")
        assertTrue(layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false, noName)).any { it.contains("(business name pending)") })
    }

    @Test fun thePaymentReceiptTakesTheOwnerNameFromTheSettingsToo() = runBlocking {
        val w = first()
        w.setOwner()("Pat Tester")
        val c = w.customer("Spice Garden")
        val payment = w.recordPayment(w.payment(c.id, 500))
        val lines = layoutPaymentReceipt(paymentReceiptOf(payment, 0, w.settings.businessHeader()))
        assertTrue(lines.contains("Salesman: Pat Tester"))
    }
}
