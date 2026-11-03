package com.mamre.billing.release

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5: the first release APK crashed on start (IllegalAccessError: kotlin.collections.j extended by class xk) because R8 was allowed to
 * optimize and repackage the Kotlin standard library (packageScope had "kotlin.**"). The real guard is tools\release\smoke-start.ps1
 * on an emulator; this test keeps the setting from coming back unnoticed.
 */
class ReleaseOptimizationTest {
    private fun gradle() = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()

    @Test fun theOptimizationScopeNeverIncludesTheKotlinStandardLibrary() {
        val line = gradle().lines().single { it.contains("packageScope = setOf(") }
        assertFalse("kotlin.** in packageScope crashes the release app at start: $line", line.contains("\"kotlin.**\""))
        assertTrue("androidx.** stays in scope: $line", line.contains("\"androidx.**\""))
    }
}
