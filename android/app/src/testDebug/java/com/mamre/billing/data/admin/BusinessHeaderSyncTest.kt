package com.mamre.billing.data.admin

import com.mamre.billing.data.FakeCustomerDao
import com.mamre.billing.data.FakeCustomerTypeDao
import com.mamre.billing.data.FakePriceDefaultDao
import com.mamre.billing.data.FakePriceOverrideDao
import com.mamre.billing.data.FakeProductDao
import com.mamre.billing.data.FakeSettingDao
import com.mamre.billing.data.FakeSyncStateDao
import com.mamre.billing.data.FakeTransactionRunner
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.InvoiceDraft
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.model.BusinessHeader
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.print.invoiceReceiptOf
import com.mamre.billing.print.layoutInvoiceReceipt
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Change set E4: the bill header (name, address, phone, footer) comes from the business settings the Admin edits and
 * the catalog sync carries. It reaches the bill only at Sync now, a missing value prints a placeholder, and the
 * demo values live only in the debug seed.
 */
class BusinessHeaderSyncTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.systemDefault()
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val table = SharedPriceTable.seeded(today)
    private val admin = FakeAdminApi(clock, table)
    private val api = FakeApi(table)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeSettingDao(), FakeTransactionRunner(),
        CatalogRemote { cursor -> api.catalog(api.login("user1", "user1").access, cursor) },
    )
    private val store = DemoStore(clock, DemoSeed.state())

    private fun center(text: String) = " ".repeat((32 - text.length) / 2) + text

    private fun billLines(header: BusinessHeader): List<String> {
        val invoice = store.confirmInvoice(
            InvoiceDraft(
                "d-${store.state.value.invoices.size}", null, "Walk-in", "Retail", "W1",
                listOf(InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", 2, 250)), 500, PaymentMethod.CASH, salesmanName = "Rajesh",
            ),
        )
        return layoutInvoiceReceipt(invoiceReceiptOf(invoice, emptyList(), false, header))
    }

    @Test fun beforeTheFirstSyncTheSalesmanAppHasNoHeaderAndTheBillSaysSo() = runTest {
        val header = repo.businessHeader()
        assertEquals(BusinessHeader(), header)
        val lines = billLines(header)
        assertTrue(lines.contains(center("(business name pending)")))
        assertTrue(lines.contains(center("(address / phone pending)")))
        assertFalse(lines.any { it.contains("Example") || it.contains("Anytown") || it.contains("Ph:") })
    }

    @Test fun afterTheFirstSyncTheHeaderIsTheDebugSeedOfTheBusinessSettings() = runTest {
        repo.refresh()
        val header = repo.businessHeader()
        assertEquals("MAMRE FOODS", header.name)
        assertEquals(listOf("123 Example Street", "Anytown, TX 00000"), header.addressLines)
        assertEquals("+1 (000) 000-0000", header.phone)
        assertEquals("Thank you!", header.footer)
        // The Admin's Settings screen starts from the very same values.
        val s = admin.settings()
        assertEquals("123 Example Street\nAnytown, TX 00000", s.address)
        assertEquals(header.phone, s.phone)
        val lines = billLines(header)
        assertEquals(center("123 Example Street"), lines[1])
        assertEquals(center("Ph: +1 (000) 000-0000"), lines[3])
    }

    @Test fun anAddressChangedByTheAdminReachesTheBillOnlyAfterSyncNow() = runTest {
        repo.refresh()
        val old = admin.settings()
        admin.saveSettings(old.copy(address = "9 Sample Road\nOtherville, TX 11111", phone = "+1 (111) 111-1111", footerText = "Come again!"), "Test Admin")
        // Not yet: the salesman still prints the old header.
        val before = billLines(repo.businessHeader())
        assertTrue(before.any { it.contains("Example Street") })
        assertFalse(before.any { it.contains("Sample Road") })
        repo.refresh() // Sync now
        val after = billLines(repo.businessHeader())
        assertTrue(after.any { it.trim() == "9 Sample Road" })
        assertTrue(after.any { it.trim() == "Otherville, TX 11111" })
        assertTrue(after.any { it.trim() == "Ph: +1 (111) 111-1111" })
        assertTrue(after.any { it.trim() == "Come again!" })
        assertFalse(after.any { it.contains("Example Street") || it.contains("Anytown") || it.contains("Thank you") })
    }

    @Test fun aClearedAddressPrintsThePlaceholderAfterSyncNow() = runTest {
        repo.refresh()
        admin.saveSettings(admin.settings().copy(address = "", phone = ""), "Test Admin")
        repo.refresh()
        val header = repo.businessHeader()
        assertTrue(header.addressLines.isEmpty() && header.phone.isEmpty())
        val lines = billLines(header)
        assertTrue(lines.contains(center("(address / phone pending)")))
        assertFalse(lines.any { it.contains("Example") || it.contains("Anytown") })
    }

    @Test fun anUnchangedSaveRaisesNoNewVersionAndAChangeIsLogged() = runTest {
        val before = table.version
        admin.saveSettings(admin.settings(), "Test Admin") // the same values
        assertEquals(before, table.version)
        admin.saveSettings(admin.settings().copy(businessName = "Mamre Foods LLC"), "Test Admin")
        assertTrue(table.version > before)
        assertEquals("Edit business settings", admin.changeLog.value.first().what)
        assertEquals("Mamre Foods LLC", table.settings()["business_name"])
    }

    @Test fun theNameIsStillRequiredByTheAdmin() = runTest {
        try {
            admin.saveSettings(BusinessSettings("", "x", "y", "z"), "Test Admin")
            throw AssertionError("expected a refusal")
        } catch (e: AdminRuleException) {
            assertTrue(e.message!!.contains("name"))
        }
    }
}
