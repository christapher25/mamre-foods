package com.mamre.billing.release

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5 round 1 (Doc 1 A-26: no backup, so uninstalling deletes every bill): tools\release\smoke-start.ps1 uninstalls the app, so it must
 * refuse to run on anything but the emulator, and the check must come BEFORE the first adb install / uninstall / pm line.
 */
class SmokeStartGuardTest {
    private val script: String = File(listOf(File(".."), File(".")).first { File(it, "app/src/main").isDirectory }.canonicalFile, "../tools/release/smoke-start.ps1").readText()
    private val refusal = "Refused: this script only runs on the emulator. It would delete the app's data on a real phone."

    /** The script text without the function bodies' definitions line, so we look at the call order of the main flow. */
    private fun mainFlow() = script.lines().filterNot { it.trimStart().startsWith("#") }

    @Test fun theGuardFunctionHasTheExactRefusalAndChecksEmulatorSerialAndQemuProperty() {
        assertTrue("function Assert-Emulator exists", script.contains("function Assert-Emulator"))
        assertTrue("plain refusal text", script.contains(refusal))
        assertTrue("serial must start with emulator-", script.contains("emulator-"))
        assertTrue("ro.kernel.qemu is read", script.contains("ro.kernel.qemu"))
        assertTrue("more than one device and no -Serial is refused", script.contains("more than one device"))
    }

    @Test fun theGuardRunsBeforeTheFirstInstallUninstallOrClear() {
        val lines = mainFlow()
        val call = lines.indexOfFirst { it.trim() == "Assert-Emulator" }
        assertTrue("Assert-Emulator is called", call >= 0)
        val danger = lines.indexOfFirst { l -> !l.trimStart().startsWith("function ") && Regex("""\b(uninstall|install|pm clear|pm uninstall)\b""").containsMatchIn(l) }
        assertTrue("an install/uninstall line exists", danger >= 0)
        assertTrue("the guard (line $call) comes before the first install/uninstall (line $danger)", call < danger)
    }

    @Test fun thereIsNoSwitchThatSkipsTheGuard() {
        val param = script.lines().first { it.trimStart().startsWith("param(") }
        assertFalse("no [switch] parameter at all: $param", param.contains("[switch]"))
        for (word in listOf("Force", "Override", "Skip", "NoCheck", "Yes", "Really")) assertFalse("$word in param: $param", param.contains(word, ignoreCase = true))
        assertEquals("the guard is called exactly once", 1, mainFlow().count { it.trim() == "Assert-Emulator" })
    }

    /** B8 L11: a release build starts on the "Create the admin account" screen; the old PIN texts are gone from the script. */
    @Test fun theScriptWaitsForTheCreateTheAdminAccountScreenAndNotForAPin() {
        assertTrue(mainFlow().any { it.contains("text=\"Create the admin account\"") })
        assertFalse("no PIN text is waited for", script.contains("New PIN") || script.contains("Save PIN") || script.contains("PIN setup"))
        assertTrue("the PASS text names the new screen", script.contains("shows the first-run screen (Create the admin account)"))
    }
}
