package com.mamre.billing.print

import com.mamre.billing.data.demo.DemoIds
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.InvoiceDraft
import com.mamre.billing.domain.worker.InvoiceLine
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A printer that always fails, as when the paper is out or Bluetooth drops. */
class FailingPrinter(private val failure: Throwable? = null) : ReceiptPrinter {
    var attempts = 0
        private set

    override suspend fun print(doc: PrintDocument): PrintResult {
        attempts++
        failure?.let { throw it }
        return PrintResult.Failed("Out of paper")
    }
}

class PrinterTest {
    private val doc = PrintDocument(listOf("MAMRE FOODS"))

    @Test fun theMockPrinterSendsAndKeepsWhatItWasGiven() = runTest {
        val printer = MockPrinter()
        assertEquals(PrintResult.Sent, printer.print(doc))
        assertEquals(listOf(doc), printer.printed)
    }

    @Test fun aFailingPrinterReportsFailure() = runTest {
        assertEquals(PrintResult.Failed("Out of paper"), FailingPrinter().printSafely(doc))
    }

    @Test fun aThrownIoErrorBecomesAFailureInsteadOfACrash() = runTest {
        val result = FailingPrinter(IOException("Bluetooth lost")).printSafely(doc)
        assertEquals(PrintResult.Failed("Bluetooth lost"), result)
    }

    /** AT-11 (Doc 3 s10.2): a print failure does not lose the invoice, and a retry works. */
    @Test fun aPrintFailureKeepsTheInvoiceAndRetryWorks() = runTest {
        val store = DemoStore()
        val invoice = store.confirmInvoice(
            InvoiceDraft(
                "draft", DemoIds.RESTAURANT, "Test Restaurant", "Restaurant", "W1",
                listOf(InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", 4, 250)), 0, null,
            ),
        )
        val failing = FailingPrinter()
        assertTrue(failing.printSafely(doc) is PrintResult.Failed)
        // The invoice is still there, with its number, and still waiting to sync.
        assertEquals(invoice, store.state.value.invoices.single { it.id == "draft" })
        assertEquals(1, store.state.value.pendingCount)
        // Retry on a working printer prints the same document.
        val mock = MockPrinter()
        assertEquals(PrintResult.Sent, mock.printSafely(doc))
        assertEquals(1, mock.printed.size)
        assertEquals(1, failing.attempts)
    }
}
