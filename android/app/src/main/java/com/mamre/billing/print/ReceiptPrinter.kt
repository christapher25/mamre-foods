package com.mamre.billing.print

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** One receipt as text lines, already laid out at 32 columns. */
data class PrintDocument(val lines: List<String>)

sealed interface PrintResult {
    data object Sent : PrintResult

    data class Failed(val reason: String) : PrintResult
}

/**
 * Doc 2 s7: MockPrinter now (on-screen preview and a log), SymcodePrinter once P-1 arrives (P3).
 * The invoice is always saved before printing, so a failure never loses it (Doc 1 s5.4).
 */
interface ReceiptPrinter {
    suspend fun print(doc: PrintDocument): PrintResult
}

/** Pretends to print: keeps what it was sent so it can be inspected, never touches hardware. */
class MockPrinter : ReceiptPrinter {
    private val _printed = mutableListOf<PrintDocument>()
    val printed: List<PrintDocument> get() = _printed.toList()

    override suspend fun print(doc: PrintDocument): PrintResult {
        _printed += doc
        return PrintResult.Sent
    }
}

/** Message shown after a mock print. */
const val SENT_TO_MOCK_PRINTER = "Sent to printer (mock)"

/** Runs a print and turns a thrown I/O error into [PrintResult.Failed], so callers only branch on the result. */
suspend fun ReceiptPrinter.printSafely(doc: PrintDocument): PrintResult =
    try {
        print(doc)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        PrintResult.Failed(e.message ?: "Printer error")
    }
