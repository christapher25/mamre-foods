package com.mamre.billing.release

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5 round 1 + C2 (Doc 1 A-26: no backup, so uninstalling deletes every bill). tools\release\smoke-start.ps1 and upgrade-test.ps1
 * uninstall the app, so both must refuse to run on anything but the emulator. The guard is ONE function (Assert-Emulator in
 * emulator-guard.ps1); each script must load it and call it exactly once BEFORE its first adb install / uninstall / pm line.
 * Each condition of the guard is pinned on its own, so replacing one by `if ($false)` fails a test.
 */
class SmokeStartGuardTest {
    private val tools = File(listOf(File(".."), File(".")).first { File(it, "app/src/main").isDirectory }.canonicalFile, "../tools/release").canonicalFile
    private val guard = File(tools, "emulator-guard.ps1").readText()
    private val scripts = mapOf("smoke-start.ps1" to File(tools, "smoke-start.ps1").readText(), "upgrade-test.ps1" to File(tools, "upgrade-test.ps1").readText())
    private val refusal = "Refused: this script only runs on the emulator. It would delete the app's data on a real phone."

    private fun code(text: String) = text.lines().filterNot { it.trimStart().startsWith("#") }
    private fun guardLines() = code(guard).map { it.trim() }

    @Test fun theGuardHasTheExactRefusalAndTheMultiDeviceRule() {
        assertTrue("function Assert-Emulator exists", guard.contains("function Assert-Emulator"))
        assertTrue("plain refusal text", guard.contains(refusal))
        assertTrue("more than one device and no -Serial is refused", guard.contains("more than one device"))
        assertTrue("no [switch] in the guard", !guard.contains("[switch]"))
    }

    @Test fun theSerialMustStartWithEmulatorAndFailsWithTheRefusal() {
        assertTrue(guardLines().contains("if (\$script:Serial -notlike 'emulator-*') { Fail \$refusal }"))
    }

    @Test fun theDeviceMustReportQemuAndFailsWithTheRefusal() {
        val lines = guardLines()
        assertTrue("ro.kernel.qemu is read", lines.any { it.contains("getprop ro.kernel.qemu") })
        assertTrue("ro.boot.qemu is the fallback", lines.any { it.contains("getprop ro.boot.qemu") })
        val check = lines.indexOf("if (\$qemu -ne '1') { Fail \$refusal }")
        assertTrue("the final qemu check fails with the refusal", check >= 0)
        assertTrue("it comes after both property reads", check > lines.indexOfFirst { it.contains("getprop ro.boot.qemu") })
    }

    @Test fun bothScriptsLoadTheGuardAndCallItOnceBeforeTheFirstInstallUninstallOrClear() {
        for ((name, text) in scripts) {
            val lines = code(text).map { it.trim() }
            assertTrue("$name loads the guard", lines.any { it == ". (Join-Path \$PSScriptRoot 'emulator-guard.ps1')" })
            assertEquals("$name calls the guard exactly once", 1, lines.count { it == "Assert-Emulator" })
            val call = lines.indexOf("Assert-Emulator")
            val danger = lines.indexOfFirst { l -> !l.startsWith("function ") && Regex("""\b(uninstall|install|pm clear|pm uninstall)\b""").containsMatchIn(l) }
            assertTrue("$name: an install/uninstall line exists", danger >= 0)
            assertTrue("$name: the guard (line $call) comes before the first install/uninstall (line $danger)", call < danger)
        }
    }

    @Test fun thereIsNoSwitchThatSkipsTheGuard() {
        for ((name, text) in scripts) {
            val param = text.lines().first { it.trimStart().startsWith("param(") }
            for (word in listOf("Force", "Override", "NoCheck", "SkipGuard", "SkipCheck", "Yes", "Really")) assertFalse("$name: $word in param: $param", param.contains(word, ignoreCase = true))
        }
        assertFalse("smoke-start has no [switch] at all", scripts.getValue("smoke-start.ps1").lines().first { it.trimStart().startsWith("param(") }.contains("[switch]"))
    }
}
