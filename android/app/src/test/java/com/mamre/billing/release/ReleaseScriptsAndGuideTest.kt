package com.mamre.billing.release

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2 round 1: source checks on the release scripts and the update guide (the scripts run on a laptop and an emulator, not in CI). */
class ReleaseScriptsAndGuideTest {
    private val root = File(listOf(File(".."), File(".")).first { File(it, "app/src/main").isDirectory }.canonicalFile, "..").canonicalFile
    private fun text(path: String) = File(root, path).readText()

    /** B2 puts "Set up your business" right after the first PIN. The upgrade test must work before and after B2. */
    @Test fun theUpgradeTestSkipsTheFirstRunSetupScreenWhenItIsThere() {
        val lines = text("tools/release/upgrade-test.ps1").lines().map { it.trim() }
        val savePin = lines.indexOf("Tap-Text 'Save PIN'")
        assertTrue("Save PIN step exists", savePin >= 0)
        val setup = lines.indexOfFirst { it.contains("Set up your business") }
        val skip = lines.indexOfFirst { it.contains("Tap-Text 'Skip for now'") }
        assertTrue("the Set up your business screen is checked after Save PIN", setup > savePin)
        assertTrue("Skip for now is tapped after that check", skip > setup)
        val home = lines.indexOfFirst { it.contains("'PIN saved, Home is shown'") }
        assertTrue("Home ('New invoice') is expected only after the skip step", home > skip)
    }

    /** build-release.ps1 runs with ErrorActionPreference Continue (native tools write to stderr), so the file steps must stop on error. */
    @Test fun theBuildReleaseScriptStopsOnAFailedCopyOrHash() {
        val lines = text("tools/release/build-release.ps1").lines()
        val copy = lines.single { it.trimStart().startsWith("Copy-Item") }
        assertTrue("Copy-Item stops on error: $copy", copy.contains("-ErrorAction Stop"))
        val hash = lines.single { it.contains("Get-FileHash") }
        assertTrue("Get-FileHash stops on error: $hash", hash.contains("-ErrorAction Stop"))
    }

    /** The release app blocks screenshots, so the guide is text only and quotes the screen words. */
    @Test fun theUpdateGuideIsTextOnlyAndMatchesDoc1ExportWording() {
        val guide = text("docs/UPDATE-GUIDE.txt")
        assertFalse("no screenshot placeholder", guide.contains("[screenshot", ignoreCase = true) || guide.contains(".png"))
        assertFalse("no USB stick (Doc 1 s11.1: mail, Drive, a cable)", guide.contains("USB", ignoreCase = true))
        assertTrue("copy to the laptop with the cable", guide.contains("copy them to the laptop with the cable"))
        for (word in listOf("Update", "Admin area", "More", "Settings", "Export data", "Export and share", "NEVER UNINSTALL", "Open")) {
            assertTrue("the guide quotes '$word'", guide.contains(word))
        }
    }
}
