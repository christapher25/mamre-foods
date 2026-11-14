package com.mamre.billing.release

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2: source checks on the release scripts and the update guide (the scripts run on a laptop and an emulator, not in CI). */
class ReleaseScriptsAndGuideTest {
    private val root = File(listOf(File(".."), File(".")).first { File(it, "app/src/main").isDirectory }.canonicalFile, "..").canonicalFile
    private fun text(path: String) = File(root, path).readText()
    private val pinWord = Regex("\\bPIN\\b")

    /** Doc 1 s2.1 FR-1: the seed creates the admin account; B2 then shows "Set up your business", which the test skips; then Home. */
    @Test fun theUpgradeTestCreatesTheAdminAccountThenSkipsTheFirstRunSetupScreen() {
        val lines = text("tools/release/upgrade-test.ps1").lines().map { it.trim() }
        val create = lines.indexOfFirst { it.contains("Check 'fresh install shows") && it.contains("Create the admin account") }
        assertTrue("the first screen is checked to be Create the admin account", create >= 0)
        for (label in listOf("Your name (printed on bills)", "Username", "Password", "Password again")) {
            val at = lines.indexOfFirst { it.startsWith("Type-Into '$label'") }
            assertTrue("'$label' is typed after the first-screen check", at > create)
        }
        val submit = lines.indexOf("Tap-Text 'Create account'")
        assertTrue("Create account is tapped", submit > create)
        val setup = lines.indexOfFirst { it.contains("Set up your business") }
        val skip = lines.indexOfFirst { it.contains("Tap-Text 'Skip for now'") }
        assertTrue("the Set up your business screen is checked after Create account", setup > submit)
        assertTrue("Skip for now is tapped after that check", skip > setup)
        val home = lines.indexOfFirst { it.contains("'admin account created, Home is shown'") }
        assertTrue("Home ('New invoice') is expected only after the skip step", home > skip)
        assertFalse("the Owner name setting is gone", lines.any { it.contains("Owner name") || it.contains("Save owner name") })
    }

    /** After the update the app must show Log in (not Create the admin account) and the same login must work. */
    @Test fun afterTheUpdateTheAppShowsLogInAndTheSameAdminLogsIn() {
        val lines = text("tools/release/upgrade-test.ps1").lines().map { it.trim() }
        val update = lines.indexOfFirst { it.contains("'install N+1 over N") }
        val shows = lines.indexOfFirst { it.contains("Check 'after the update the app shows Log in") }
        assertTrue("the update step exists", update >= 0)
        assertTrue("the Log in check follows the update", shows > update)
        val check = lines[shows]
        assertTrue("it looks for Log in", check.contains("Has-Text 'Log in'"))
        assertTrue("and for the absence of Create the admin account", check.contains("-not (Has-Text 'Create the admin account'"))
        val user = lines.withIndex().firstOrNull { it.index > shows && it.value == "Type-Into 'Username' \$adminUser" }
        assertTrue("the same username is typed after the check", user != null)
        val pass = lines.withIndex().firstOrNull { it.index > shows && it.value == "Type-Into 'Password' \$adminPassword" }
        assertTrue("the same password is typed after the check", pass != null)
        val login = lines.withIndex().firstOrNull { it.index > shows && it.value == "Tap-Text 'Log in' -Last" }
        assertTrue("the Log in button is tapped", login != null)
        val home = lines.indexOfFirst { it.contains("'after the update the admin logs in with the same password and Home is shown'") }
        assertTrue("Home is expected after the login", home > login!!.index)
    }

    /** Emulator finding (14 Nov): a freshly booted keyboard can drop input. Focus is checked before typing, the text is read back after. */
    @Test fun typeIntoChecksFocusBeforeAndTheTextAfterTyping() {
        val text = text("tools/release/upgrade-test.ps1")
        val body = text.substringAfter("function Type-Into").substringBefore("function Scroll-Down")
        val focus = body.indexOf("Focused -eq 'true'")
        val typing = body.indexOf("input text")
        val readBack = body.lastIndexOf("Focused -eq 'true'")
        assertTrue("focus is checked before typing", focus in 0 until typing)
        assertTrue("the text is read back after typing", readBack > typing && body.contains("\$now.Text"))
        assertTrue("an empty box stops the step", body.contains("stayed empty"))
        // A box that is not focused must stop the typing: the gate sits between the focus query and the first input.
        val query = body.indexOf("\$edit = Get-Screen")
        val gate = body.indexOf("if (-not \$edit) { continue }")
        assertTrue("no focus, no typing (the gate is after the query and before input text)", query in 0 until gate && gate < typing)
        assertTrue("a retry is logged so a dropped first input is visible", body.contains("Write-Host \"NOTE  Type-Into"))
    }

    /** The evidence file must be written or the run stops (the first emulator run lost its output silently). */
    @Test fun theEvidenceFileIsWrittenOrTheRunStops() {
        val lines = text("tools/release/upgrade-test.ps1").lines()
        assertTrue("OutputFile parameter", lines.first { it.startsWith("param(") }.contains("\$OutputFile"))
        assertTrue("each result is appended with -ErrorAction Stop", lines.any { it.contains("Add-Content -Path \$OutputFile") && it.contains("-ErrorAction Stop") })
        assertTrue("the folder is created with -ErrorAction Stop", lines.any { it.contains("New-Item") && it.contains("Split-Path \$OutputFile") && it.contains("-ErrorAction Stop") })
        assertTrue("the file is created with -ErrorAction Stop", lines.any { it.contains("Set-Content -Path \$OutputFile") && it.contains("-ErrorAction Stop") })
    }

    /** The Prices screen starts with the price rules (also a "Retail" row), so the price button is found by name, not by position. */
    @Test fun theRetailPriceButtonIsFoundByNameNotByPosition() {
        val text = text("tools/release/upgrade-test.ps1")
        assertFalse("no positional button index", text.contains("\$buttons[2]"))
        assertTrue("it looks under the Mamre Chapathi header", text.contains("-eq 'Mamre Chapathi'") && text.contains("-eq 'Mamre Fresh Chapathi'"))
    }

    @Test fun theUpgradeTestScriptHasNoPin() {
        assertFalse("no PIN left in upgrade-test.ps1", pinWord.containsMatchIn(text("tools/release/upgrade-test.ps1")))
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

    /** Accounts replace the PIN (Doc 1 s2.2): the guide names the login screen and the first-run screen, and has no PIN. */
    @Test fun theUpdateGuideUsesTheLoginWordsAndHasNoPin() {
        val guide = text("docs/UPDATE-GUIDE.txt")
        assertFalse("no PIN in the update guide", pinWord.containsMatchIn(guide))
        for (word in listOf("\"Log in\"", "\"Username\"", "\"Password\"", "\"Create the admin account\"")) {
            assertTrue("the guide quotes $word", guide.contains(word))
        }
        assertTrue("log in with your username and password", guide.contains("log in with your username and password"))
    }
}
