package com.mamre.billing.print

import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.World
import com.mamre.billing.data.local.section65World
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.model.BusinessHeader
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.worker.PaymentMethod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Change set E4 on the real database (Doc 1 A-31, Doc 2 s7): the bill header (name, address, phone, footer) comes from the
 * Settings the Admin edits and from nothing in the code. A missing value prints a placeholder, and a change reaches the very
 * next bill (there is no sync now).
 */
@RunWith(RobolectricTestRunner::class)
class BusinessHeaderTest {
    private fun center(text: String) = " ".repeat((32 - text.length) / 2) + text

    private suspend fun World.billLines(): List<String> {
        val invoice = makeBill(draft(null, listOf(line(qty = 2, unit = 100)), paid = 200, method = PaymentMethod.CASH))
        return layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false, settings.businessHeader()))
    }

    @Test fun onFirstRunTheHeaderIsOnlyTheDefaultNameAndTheBillSaysTheAddressIsPending() = runBlocking {
        val (w, _) = section65World()
        val header = w.settings.businessHeader()
        assertEquals(BusinessHeader(name = "Mamre Foods"), header) // the Doc 2 s5.2 default; nothing else is invented
        val lines = w.billLines()
        assertTrue(lines.contains(center("Mamre Foods")))
        assertTrue(lines.contains(center("(address / phone pending)")))
        assertFalse(lines.any { it.contains("Example") || it.contains("Anytown") || it.contains("Ph:") })
    }

    @Test fun anAddressSavedByTheAdminIsOnTheVeryNextBill() = runBlocking {
        val (w, _) = section65World()
        w.saveSettings(BusinessSettings("Mamre Foods", "9 Sample Road\nOtherville, TX 11111", "+1 (111) 111-1111", "Come again!"))
        val header = w.settings.businessHeader()
        assertEquals(listOf("9 Sample Road", "Otherville, TX 11111"), header.addressLines)
        assertEquals("+1 (111) 111-1111", header.phone)
        assertEquals("Come again!", header.footer)
        val lines = w.billLines()
        assertTrue(lines.any { it.trim() == "9 Sample Road" })
        assertTrue(lines.any { it.trim() == "Otherville, TX 11111" })
        assertTrue(lines.any { it.trim() == "Ph: +1 (111) 111-1111" })
        assertTrue(lines.any { it.trim() == "Come again!" })
    }

    @Test fun aClearedAddressPrintsThePlaceholderAgain() = runBlocking {
        val (w, _) = section65World()
        w.saveSettings(BusinessSettings("Mamre Foods", "1 Test Road", "555", "Thanks"))
        w.saveSettings(BusinessSettings("Mamre Foods", "", "", ""))
        val header = w.settings.businessHeader()
        assertTrue(header.addressLines.isEmpty() && header.phone.isEmpty())
        assertTrue(w.billLines().contains(center("(address / phone pending)")))
    }

    @Test fun everySaveIsChangeLoggedAndTheNameIsStillRequired() = runBlocking {
        val (w, _) = section65World()
        w.saveSettings(BusinessSettings("Mamre Foods LLC", "", "", ""))
        assertEquals("Edit business settings", w.books.changeLog().first().what)
        try {
            w.saveSettings(BusinessSettings("", "x", "y", "z"))
            fail("expected a refusal")
        } catch (e: RuleException) {
            assertTrue(e.message!!.contains("name"))
        }
        assertEquals("Mamre Foods LLC", w.settings.businessHeader().name)
    }

    @Test fun theBillHeaderIsReadFromTheSettingsAndTheOwnerNameFromTheSettingToo() = runBlocking {
        val (w, id) = section65World()
        val invoice = w.makeBill(w.draft(id, listOf(w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = 1, unit = 100))))
        assertEquals("Rajesh", invoice.salesmanName) // owner_name in Settings
    }
}
