package com.mamre.billing.print

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Doc 1 s5.4, AT-11: the one text shown when no printer worked. The bill is already saved. */
const val PRINTER_NOT_READY = "Printer not ready. The bill is saved. Tap Reprint."

/** Message shown after a successful print. */
const val SENT_TO_PRINTER = "Sent to printer"

/** One way of reaching a printer (Doc 4 s5). Each driver gets its Context in its constructor. */
interface PrinterDriver {
    /** Stored in the setting printer_method; never changes once shipped. */
    val name: String

    /** Cheap check: is this way of printing present on this phone at all. */
    fun isAvailable(): Boolean

    suspend fun print(doc: PrintDocument): PrintResult
}

/** Reads and writes the setting printer_method (settings table, no schema change). */
interface PrinterMethodStore {
    suspend fun saved(): String?

    suspend fun save(name: String)
}

/**
 * Doc 4 s5: tries the drivers in order, remembers the first that works, and uses it first next time. When the remembered one
 * fails the whole chain runs once more and the new winner is saved. When all fail the result is [PRINTER_NOT_READY]; the
 * bill was saved before printing, so nothing is lost.
 */
class PrinterChain(private val drivers: List<PrinterDriver>, private val store: PrinterMethodStore) : ReceiptPrinter {
    override suspend fun print(doc: PrintDocument): PrintResult {
        val saved = store.saved()?.let { name -> drivers.firstOrNull { it.name == name } }
        if (saved != null && saved.isAvailable() && attempt(saved, doc) == PrintResult.Sent) return PrintResult.Sent
        for (driver in drivers) {
            if (!driver.isAvailable()) continue
            if (attempt(driver, doc) == PrintResult.Sent) {
                store.save(driver.name)
                return PrintResult.Sent
            }
        }
        return PrintResult.Failed(PRINTER_NOT_READY)
    }

    private suspend fun attempt(driver: PrinterDriver, doc: PrintDocument): PrintResult =
        try {
            driver.print(doc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            PrintResult.Failed(e.message ?: "Printer error")
        } catch (e: SecurityException) {
            PrintResult.Failed(e.message ?: "Permission denied")
        }
}
