package com.mamre.billing.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterReportTest {
    private val info = PrinterCheckInfo(
        "SUNMI", "SUNMI", "V2", "V2_PRO", "11", 30,
        listOf("InnerPrinter (0x0600)"), null, listOf("Default Print Service"), listOf("woyou.aidlservice.jiuiv5"),
        listOf("sunmi" to true, "bluetooth" to true, "android" to true), "sunmi",
    )

    @Test fun theReportHoldsEveryItemTheBriefNames() {
        val text = printerReportOf(info).joinToString("\n")
        for (s in listOf("Manufacturer: SUNMI", "Brand:", "Model: V2", "Device: V2_PRO", "Android: 11 (SDK 30)", "InnerPrinter (0x0600)",
            "Default Print Service", "woyou.aidlservice.jiuiv5", "sunmi: available")) assertTrue(s, text.contains(s))
    }

    @Test fun nothingFoundIsSaidPlainly() {
        val text = printerReportOf(info.copy(pairedDevices = emptyList(), printServices = emptyList(), printerPackages = emptyList())).joinToString("\n")
        assertEquals(3, Regex("  none").findAll(text).count())
    }

    @Test fun printerPackageWordsMatch() {
        assertTrue(isPrinterPackage("com.sunmi.printerhelper"))
        assertTrue(isPrinterPackage("com.telpo.x"))
        assertFalse(isPrinterPackage("com.android.calculator2"))
    }

    @Test fun wrappingKeepsEveryLineWithin32Columns() {
        val wrapped = wrapTo32(listOf("  com.some.very.long.package.name.that.goes.on.and.on", "Model: Some Long Model Name Here ok"))
        assertTrue(wrapped.all { it.length <= RECEIPT_WIDTH })
    }
}
