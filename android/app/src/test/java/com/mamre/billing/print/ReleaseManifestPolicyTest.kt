package com.mamre.billing.print

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doc 2 s6, review item 8: the merged RELEASE manifest may hold only the launcher activity and no exported provider, service or
 * receiver beyond a short list of library entries. The rule itself is the Gradle task verifyReleaseComponents (it needs the merged
 * manifest, which only the build makes); this test pins that it is part of assembleRelease and that the allowlist stays short.
 */
class ReleaseManifestPolicyTest {
    private fun app(): File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }

    private val gradle get() = File(app(), "build.gradle.kts").readText()

    @Test fun assembleReleaseRunsTheComponentCheck() {
        val line = gradle.lines().single { it.contains("tasks.named(\"assembleRelease\")") }
        assertTrue("assembleRelease must depend on verifyReleaseComponents: $line", line.contains("verifyReleaseComponents"))
        assertTrue("and on the network check: $line", line.contains("verifyReleaseManifest"))
    }

    @Test fun theAllowlistIsTheThreeKnownLibraryEntriesAndNothingOfOurs() {
        val block = gradle.substringAfter("val releaseManifestAllowedExported = setOf(").substringBefore(")")
        val names = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "androidx.startup.InitializationProvider",
                "androidx.room.MultiInstanceInvalidationService",
                "androidx.profileinstaller.ProfileInstallReceiver",
            ),
            names,
        )
    }

    @Test fun ourOwnManifestDeclaresOneActivityAndTheProviderIsNotExported() {
        val main = File(app(), "src/main/AndroidManifest.xml").readText()
        assertEquals(1, Regex("""<activity\b""").findAll(main).count())
        assertTrue(Regex("""FileProvider[\s\S]*?android:exported="false"""").containsMatchIn(main))
    }
}
