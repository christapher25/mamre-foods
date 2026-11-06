package com.mamre.billing.print

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Doc 4 s5 and A1 item 3: the chain tries drivers in order, remembers the winner, and never loses the bill. */
class PrinterChainTest {
    private class Driver(
        override val name: String,
        var available: Boolean = true,
        var works: Boolean = true,
        val log: MutableList<String>,
        override val rememberable: Boolean = true,
    ) : PrinterDriver {
        override fun isAvailable() = available
        override suspend fun print(doc: PrintDocument): PrintResult {
            log += name
            return if (works) PrintResult.Sent else PrintResult.Failed("$name failed")
        }
    }

    private class Store(var value: String? = null) : PrinterMethodStore {
        override suspend fun saved() = value
        override suspend fun save(name: String) { value = name }
    }

    private val doc = PrintDocument(listOf("A BILL"))
    private val log = mutableListOf<String>()
    private fun d(name: String, available: Boolean = true, works: Boolean = true, rememberable: Boolean = true) =
        Driver(name, available, works, log, rememberable)

    @Test fun triesTheDriversInOrderAndTheFirstSuccessWinsAndIsSaved() = runTest {
        val store = Store()
        val chain = PrinterChain(listOf(d("vendor", works = false), d("bluetooth"), d("android")), store)
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals(listOf("vendor", "bluetooth"), log)
        assertEquals("bluetooth", store.value)
    }

    @Test fun anUnavailableDriverIsSkippedWithoutBeingCalled() = runTest {
        val chain = PrinterChain(listOf(d("vendor", available = false), d("bluetooth")), Store())
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals(listOf("bluetooth"), log)
    }

    @Test fun aSavedDriverIsUsedFirst() = runTest {
        val chain = PrinterChain(listOf(d("vendor"), d("bluetooth"), d("android")), Store("android"))
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals(listOf("android"), log)
    }

    @Test fun whenTheSavedDriverFailsTheChainRunsOnceMoreAndSavesTheNewWinner() = runTest {
        val store = Store("bluetooth")
        val chain = PrinterChain(listOf(d("vendor"), d("bluetooth", works = false), d("android")), store)
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals(listOf("bluetooth", "vendor"), log)
        assertEquals("vendor", store.value)
    }

    @Test fun whenAllFailTheResultIsTheExactNotReadyMessageAndNothingIsSaved() = runTest {
        val store = Store()
        val chain = PrinterChain(
            listOf(d("vendor", works = false), d("bluetooth", works = false), d("android", available = false)),
            store,
        )
        assertEquals(PrintResult.Failed(PRINTER_NOT_READY), chain.print(doc))
        assertEquals("Printer not ready. The bill is saved. Tap Reprint.", PRINTER_NOT_READY)
        assertEquals(null, store.value)
    }

    @Test fun aDriverThatThrowsAnIoErrorIsTreatedAsFailedAndTheNextIsTried() = runTest {
        val thrower = object : PrinterDriver {
            override val name = "thrower"
            override fun isAvailable() = true
            override suspend fun print(doc: PrintDocument): PrintResult = throw java.io.IOException("socket closed")
        }
        val chain = PrinterChain(listOf(thrower, d("android")), Store())
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals(listOf("android"), log)
    }

    @Test fun permissionDeniedMovesToTheNextDriver() = runTest {
        val denied = object : PrinterDriver {
            override val name = "bluetooth"
            override fun isAvailable() = true
            override suspend fun print(doc: PrintDocument): PrintResult = PrintResult.Failed("permission denied")
        }
        val store = Store()
        val chain = PrinterChain(listOf(denied, d("android")), store)
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals("android", store.value)
    }

    @Test fun theSystemPrintDialogIsNeverSavedAsTheDetectedPrinter() = runTest {
        val store = Store()
        val chain = PrinterChain(listOf(d("vendor", works = false), d("bluetooth", available = false), d("android", rememberable = false)), store)
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals(listOf("vendor", "android"), log)
        assertEquals(null, store.value)
    }

    @Test fun afterTheDialogFallbackTheNextPrintTriesTheEarlierDriversFirstAndSavesTheOneThatWorks() = runTest {
        val store = Store()
        val vendor = d("vendor", works = false)
        val chain = PrinterChain(listOf(vendor, d("android", rememberable = false)), store)
        chain.print(doc)
        assertEquals(null, store.value)
        log.clear()
        vendor.works = true
        assertEquals(PrintResult.Sent, chain.print(doc))
        assertEquals(listOf("vendor"), log)
        assertEquals("vendor", store.value)
    }
}
