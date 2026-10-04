package com.mamre.billing.print

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No real-looking street address or phone number is written anywhere in the sources (main, debug, test, testDebug,
 * androidTest and the golden files), so none can reach a release APK, a fixture or a screenshot. The owner never
 * gave an address: the real one comes from Admin Settings through the catalog sync, and every demo value is
 * obviously fake ("123 Example Street", "Anytown, TX 00000", "+1 (000) 000-0000").
 *
 * The banned strings are written in pieces so this file does not contain them itself.
 */
class NoHardCodedAddressTest {
    private val banned = listOf(
        "Branch" + " Hollow",
        "Carroll" + "ton",
        "750" + "07",
        "927" + "-2119",
        "14" + "61 E",
        "(97" + "2)",
    )

    private fun sourceRoots(): List<File> {
        val base = listOf(File("src"), File("app/src")).first { it.isDirectory }
        return listOf("main", "debug", "test", "testDebug", "androidTest").map { File(base, it) }.filter { it.isDirectory }
    }

    private fun files(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "java", "json", "txt", "md", "yaml") }.toList()

    @Test fun noSourceSetHoldsAnAddressOrPhone() {
        val roots = sourceRoots()
        assertTrue("expected main, debug, test and testDebug", roots.map { it.name }.containsAll(listOf("main", "debug", "test", "testDebug")))
        val hits = roots.flatMap { root ->
            files(root).flatMap { f -> banned.filter { f.readText().contains(it) }.map { "${root.name}/${f.name}: $it" } }
        }
        assertTrue("an address is written in the sources: $hits", hits.isEmpty())
    }

    @Test fun theMainSourceSetAlsoHoldsNoDemoAddressAndNoBusinessName() {
        val main = files(sourceRoots().first { it.name == "main" })
        assertTrue("expected to find the main sources", main.size > 50)
        val demo = listOf("123 Example", "Anytown", "000) 000")
        val hits = main.flatMap { f -> demo.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertTrue("a demo address is in main: $hits", hits.isEmpty())
        val bill = main.filter { it.name == "ReceiptLayout.kt" || it.name == "ReceiptBuilders.kt" }
        assertTrue(bill.size == 2)
        assertTrue(bill.none { it.readText().contains("MAMRE FOODS") })
    }

    @Test fun theDemoValuesAreObviouslyFakeAndLiveOnlyInTheDebugSeed() {
        val seed = files(sourceRoots().first { it.name == "debug" }).first { it.name == "SharedPriceTable.kt" }.readText()
        assertTrue(seed.contains("123 Example Street") && seed.contains("Anytown, TX 00000") && seed.contains("+1 (000) 000-0000"))
    }
}
