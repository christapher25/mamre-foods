package com.mamre.billing.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doc 1 A-25 (PIN lock) and the Owner's addition A: the PIN screen is the single gate. No deep link, notification or other
 * entry may open a screen while the app is locked. The release manifest therefore has ONE entry, the launcher, which starts
 * at the PIN screen; it declares no deep link, no browsable or view filter, no service and no receiver, and its one provider is the private FileProvider of the export share. The debug-only
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
        for (banned in listOf("<data", "android.intent.action.VIEW", "BROWSABLE", "<service", "<receiver", "<activity-alias")) {
            assertFalse("main manifest declares $banned", m.contains(banned))
        }
    }

    /** The one provider is androidx's FileProvider for the export share (Doc 2 s5.3): private to the app, a grant per share, cache only. */
    @Test fun theOnlyProviderIsTheFileProviderForTheExportAndItIsNotExported() {
        val m = read("src/main/AndroidManifest.xml")
        val providers = Regex("""<provider\b[^>]*>""", RegexOption.DOT_MATCHES_ALL).findAll(m).map { it.value }.toList()
        assertEquals("one provider", 1, providers.size)
        val p = providers.single()
        assertTrue(p.contains("android:name=\"androidx.core.content.FileProvider\""))
        assertTrue(p.contains("android:authorities=\"\${applicationId}.exports\""))
        assertTrue("not exported", p.contains("android:exported=\"false\""))
        assertTrue("a grant per share", p.contains("android:grantUriPermissions=\"true\""))
        assertTrue(m.contains("android.support.FILE_PROVIDER_PATHS") && m.contains("@xml/file_paths"))
        val paths = read("src/main/res/xml/file_paths.xml")
        assertEquals("one path", 1, Regex("<[a-z-]+-path\\b").findAll(paths).count())
        assertTrue("the cache exports folder and nothing else", Regex("""<cache-path\s+name="exports"\s+path="exports/"\s*/>""").containsMatchIn(paths))
    }

    @Test fun theMainManifestAsksForNoPermissionAtAll() {
        val m = read("src/main/AndroidManifest.xml")
        assertFalse("a permission is declared", m.contains("<uses-permission"))
        for (banned in listOf("INTERNET", "STORAGE", "MANAGE_EXTERNAL", "READ_MEDIA")) assertFalse(banned, m.contains(banned))
    }

    @Test fun theOnlyDebugEntryIsTheStateGalleryAndItReadsNoData() {
        val d = read("src/debug/AndroidManifest.xml")
        assertEquals(listOf("com.mamre.billing.GalleryActivity"), Regex("<activity android:name=\"([^\"]+)\"").findAll(d).map { it.groupValues[1] }.toList())
        for (banned in listOf("<intent-filter", "<data", "<service", "<receiver", "<provider")) assertFalse("debug manifest declares $banned", d.contains(banned))
        val code = read("src/debug/java/com/mamre/billing/GalleryActivity.kt")
        for (banned in listOf("Repository", "Database", "Dao", "Hilt", "Inject", "UnitOfWork")) assertFalse("GalleryActivity uses $banned", code.contains(banned))
    }
}
