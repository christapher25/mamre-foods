package com.mamre.billing.print

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Change set E4: no street address or phone number is written in the main source set (and so none can be in a
 * release APK). The demo values exist only as the debug seed of the business settings.
 */
class NoHardCodedAddressTest {
    private fun mainSources(): List<File> {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "java", "json", "txt") }.toList()
    }

    @Test fun theMainSourceSetHoldsNoStreetAddressOrPhone() {
        val banned = listOf("Branch Hollow", "Carrollton", "75007", "927-2119", "(972)")
        val files = mainSources()
        assertTrue("expected to find the main sources", files.size > 50)
        val hits = files.flatMap { f -> banned.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertTrue("an address is written in main: $hits", hits.isEmpty())
    }

    @Test fun theBillCodeKnowsNoBusinessNameEither() {
        val layout = mainSources().filter { it.name == "ReceiptLayout.kt" || it.name == "ReceiptBuilders.kt" }
        assertTrue(layout.size == 2)
        assertTrue(layout.none { it.readText().contains("MAMRE FOODS") })
    }
}
