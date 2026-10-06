package com.mamre.billing.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doc 1 A-25 (PIN lock) and the Owner's addition A: the PIN screen is the single gate. No deep link, notification or other
 * entry may open a screen while the app is locked. The release manifest therefore has ONE entry, the launcher, which starts
 * at the PIN screen; it declares no deep link, no browsable or view filter, no service, receiver or provider. The debug-only
 * GalleryActivity shows fixed sample cards and reads no database and no repository, so it can show no business data.
 */
class SingleGateManifestTest {
    private fun app(): File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
    private fun read(path: String) = File(app(), path).readText()

    @Test fun theMainManifestHasOneEntryTheLauncherAndNoDeepLink() {
        val m = read("src/main/AndroidManifest.xml")
        assertEquals("one activity", 1, Regex("""<activity\s""").findAll(m).count())
        assertEquals("one intent filter", 1, Regex("<intent-filter").findAll(m).count())
        assertTrue(m.contains("android.intent.action.MAIN") && m.contains("android.intent.category.LAUNCHER"))
        for (banned in listOf("<data", "android.intent.action.VIEW", "BROWSABLE", "<service", "<receiver", "<provider", "<activity-alias")) {
            assertFalse("main manifest declares $banned", m.contains(banned))
        }
    }

    @Test fun theOnlyDebugEntryIsTheStateGalleryAndItReadsNoData() {
        val d = read("src/debug/AndroidManifest.xml")
        assertEquals(listOf("com.mamre.billing.GalleryActivity"), Regex("<activity android:name=\"([^\"]+)\"").findAll(d).map { it.groupValues[1] }.toList())
        for (banned in listOf("<intent-filter", "<data", "<service", "<receiver", "<provider")) assertFalse("debug manifest declares $banned", d.contains(banned))
        val code = read("src/debug/java/com/mamre/billing/GalleryActivity.kt")
        for (banned in listOf("Repository", "Database", "Dao", "Hilt", "Inject", "UnitOfWork")) assertFalse("GalleryActivity uses $banned", code.contains(banned))
    }
}
