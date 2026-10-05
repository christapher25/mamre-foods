package com.mamre.billing.print

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doc 2 s1.1, s6, Doc 3 N18: version 1 has no network. The release app requests no INTERNET permission, so no data can leave
 * the phone except through an export the Owner starts, and no network library or server setting is built into it.
 * The same check runs on the merged release manifest at build time (Gradle task verifyReleaseManifest, part of assembleRelease).
 */
class NoNetworkTest {
    private fun app(): File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }

    private fun read(path: String) = File(app(), path).readText()

    private val network = Regex("""android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE)""")

    @Test fun theMainAndDebugManifestsRequestNoNetworkPermission() {
        for (m in listOf("src/main/AndroidManifest.xml", "src/debug/AndroidManifest.xml")) {
            assertEquals("$m requests a network permission", emptyList<String>(), network.findAll(read(m)).map { it.value }.toList())
        }
    }

    @Test fun theMergedReleaseManifestRequestsNoNetworkPermissionWhenItHasBeenBuilt() {
        val merged = File(app(), "build/intermediates").walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && it.path.replace('\\', '/').contains("release", ignoreCase = true) && it.path.replace('\\', '/').contains("merged_manifest") }
            .toList()
        for (m in merged) assertEquals("${m.path} requests a network permission", emptyList<String>(), network.findAll(m.readText()).map { it.value }.toList())
    }

    @Test fun noNetworkOrBackgroundSyncLibraryIsDeclared() {
        val libs = File(app(), "../gradle/libs.versions.toml").readText() + read("build.gradle.kts")
        val banned = Regex("""retrofit|okhttp|kotlinx-serialization|kotlin-serialization|serialization-json|androidx-work|work-runtime|security-crypto|androidx\.work|androidx\.security""", RegexOption.IGNORE_CASE)
        assertEquals("a network library is declared", emptyList<String>(), banned.findAll(libs).map { it.value }.distinct().toList())
    }

    @Test fun noServerAddressOrFakeApiSwitchIsBuiltIn() {
        val gradle = read("build.gradle.kts")
        assertEquals(emptyList<String>(), Regex("BASE_URL|USE_FAKE_API").findAll(gradle).map { it.value }.toList())
        assertTrue("the empty sync folder is still there", !File(app(), "src/main/java/com/mamre/billing/sync").exists())
    }
}
