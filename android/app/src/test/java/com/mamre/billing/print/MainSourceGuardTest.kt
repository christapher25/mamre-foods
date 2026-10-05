package com.mamre.billing.print

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release build must contain no fake API, no demo logins, no demo data and no demo address (Doc 2 s8, AT-17), and no
 * destructive migration (Doc 2 I-15). The debug source set holds all demo code; this guard reads the MAIN sources, so a
 * demo name written in main fails here before it can reach a release package. The release APK is also unzipped and
 * scanned by hand before a release (DECISIONS).
 */
class MainSourceGuardTest {
    private fun mainFiles(): List<File> {
        val base = listOf(File("src"), File("app/src")).first { it.isDirectory }
        return File(base, "main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "java", "kts") }.toList()
    }

    private fun hits(pattern: Regex): List<String> =
        mainFiles().flatMap { f -> pattern.findAll(f.readText()).map { "${f.name}: ${it.value}" }.toList() }

    @Test fun mainNeverNamesDemoCredentialsOrFakeOrDemoClasses() {
        assertTrue("expected the main sources", mainFiles().size > 80)
        val found = hits(Regex("""FakeCredentials|admin5|\bFake[A-Z]\w*|\bDemo[A-Z]\w*|DEMO[ _]DATA|DEMO_"""))
        assertEquals("a demo or fake name is written in main: $found", emptyList<String>(), found)
    }

    @Test fun mainHasNoDestructiveMigrationAndNoNetworkClient() {
        assertEquals(emptyList<String>(), hits(Regex("fallbackToDestructiveMigration")))
        assertEquals(emptyList<String>(), hits(Regex("""import (retrofit2|okhttp3)\b""")))
        assertEquals(emptyList<String>(), hits(Regex("""BASE_URL|USE_FAKE_API""")))
    }

    @Test fun mainHoldsNoBusinessDetailAsAStringLiteral() {
        val found = hits(Regex(""""(MAMRE FOODS|Mamre Foods)""""))
        // The one allowed place is the first-run default of the business name setting (Doc 2 s5.2).
        assertEquals(listOf("ReferenceSeed.kt: \"Mamre Foods\""), found)
    }
}
