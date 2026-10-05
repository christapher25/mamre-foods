package com.mamre.billing.print

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
