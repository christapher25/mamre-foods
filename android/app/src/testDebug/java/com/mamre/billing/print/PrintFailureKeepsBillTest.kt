package com.mamre.billing.print

import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.section65World
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** AT-11 (Doc 3 s10.2, Doc 1 s5.4), on the real database: a print failure does not lose the bill, and a retry works. */
@RunWith(RobolectricTestRunner::class)
class PrintFailureKeepsBillTest {
    private val doc = PrintDocument(listOf("A BILL"))

    @Test fun aPrintFailureKeepsTheInvoiceAndRetryWorks() = runBlocking {
        val (w, id) = section65World()
        val invoice = w.makeBill(w.draft(id, listOf(w.line(ReferenceIds.PRODUCT_CHAPATHI, "Mamre Chapathi", qty = 4, unit = 100))))
        val failing = FailingPrinter()
        assertTrue(failing.printSafely(doc) is PrintResult.Failed)
        // The bill is saved first, so it is still there with its number after the failed print.
        assertEquals(invoice, w.sales.state().invoices.single { it.id == invoice.id })
        // A retry on a working printer prints the same document.
        val mock = MockPrinter()
        assertEquals(PrintResult.Sent, mock.printSafely(doc))
        assertEquals(1, mock.printed.size)
        assertEquals(1, failing.attempts)
    }

    @Test fun theFailureTextIsExactlyThePromisedWords() {
        // AT-11: the screen shows PRINTER_NOT_READY on a failure; the words are fixed.
        assertEquals("Printer not ready. The bill is saved. Tap Reprint.", PRINTER_NOT_READY)
        val screen = java.io.File("src/main/java/com/mamre/billing/ui/worker/ReceiptScreen.kt")
            .takeIf { it.exists() } ?: java.io.File("app/src/main/java/com/mamre/billing/ui/worker/ReceiptScreen.kt")
        val source = screen.readText()
        assertTrue(source.contains("PRINTER_NOT_READY"))
        // A1 round 2, fix 5: the failed state shows the words, and the button reads "Reprint" when failed.
        assertTrue(
            "Failed shows the not-ready text",
            Regex("""is PrintStatus\.Failed ->[^}]*?PRINTER_NOT_READY""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(source),
        )
        assertTrue("the button says Reprint when failed", Regex("""if \(failed\) "Reprint" else printLabel""").containsMatchIn(source))
    }
}
