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
 * Doc 2 s7, Doc 4 s5: the real implementation is PrinterChain. The invoice is always saved before printing, so a failure never loses it (Doc 1 s5.4).
 */
interface ReceiptPrinter {
    suspend fun print(doc: PrintDocument): PrintResult
}

/** Runs a print and turns a thrown I/O error into [PrintResult.Failed], so callers only branch on the result. */
suspend fun ReceiptPrinter.printSafely(doc: PrintDocument): PrintResult =
    try {
        print(doc)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        PrintResult.Failed(e.message ?: "Printer error")
    }
