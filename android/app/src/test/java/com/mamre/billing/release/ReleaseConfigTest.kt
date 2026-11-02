package com.mamre.billing.release

import com.mamre.build.ReleaseConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** C1: version.properties parsing, keystore.properties parsing, and the signed/unsigned decision (Doc 4 F1). */
class ReleaseConfigTest {
    /** Unit tests run in android/app; a run from android/ also works. */
    private fun androidDir(): File = listOf(File(".."), File(".")).first { File(it, "app/src/main").isDirectory }.canonicalFile

    @Test fun versionPropertiesParse() {
        val v = ReleaseConfig.parseVersion("versionCode=7\nversionName=1.2.3-rc2\n")
        assertEquals(7, v.code)
        assertEquals("1.2.3-rc2", v.name)
    }

    @Test fun versionPropertiesMissingKeyFails() {
        try { ReleaseConfig.parseVersion("versionCode=7\n"); fail("expected failure") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("versionName")) }
        try { ReleaseConfig.parseVersion("versionCode=abc\nversionName=x\n"); fail("expected failure") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("versionCode")) }
        try { ReleaseConfig.parseVersion("versionCode=0\nversionName=x\n"); fail("expected failure") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("versionCode")) }
    }

    @Test fun theCommittedVersionFileStartsAtRc1() {
        val v = ReleaseConfig.parseVersion(File(androidDir(), "version.properties").readText())
        assertEquals(1, v.code)
        assertEquals("1.0.0-rc1", v.name)
    }

    @Test fun noKeystoreFileMeansUnsigned() {
        assertNull(ReleaseConfig.parseKeystore(null))
    }

    @Test fun keystorePropertiesParse() {
        val k = ReleaseConfig.parseKeystore("storeFile=C:/keys/a.jks\nstorePassword=sp\nkeyAlias=mamre\nkeyPassword=kp\n")!!
        assertEquals("C:/keys/a.jks", k.storeFile)
        assertEquals("mamre", k.keyAlias)
        assertFalse("password stays out of toString", k.toString().contains("sp") || k.toString().contains("kp"))
    }

    @Test fun incompleteKeystoreFileFailsLoudlyWithoutThePassword() {
        try { ReleaseConfig.parseKeystore("storeFile=a.jks\nstorePassword=secret123\n"); fail("expected failure") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("keyAlias"))
            assertFalse(e.message!!.contains("secret123"))
        }
    }

    @Test fun signingCheckRejectsUnsignedAndDebugKey() {
        assertTrue(ReleaseConfig.signingProblems(certs = emptyList()).isNotEmpty())
        assertTrue(ReleaseConfig.signingProblems(certs = listOf("CN=Android Debug,O=Android,C=US")).isNotEmpty())
        assertTrue(ReleaseConfig.signingProblems(certs = listOf("CN=Mamre Foods")).isEmpty())
    }

    private fun repoFile(path: String) = File(androidDir(), path).readText()

    /** The exact line make-keystore.ps1 writes for a Windows path (forward slashes), read back by parseKeystore. */
    @Test fun theLineTheKeystoreScriptWritesReadsBackAsTheRealPath() {
        val k = ReleaseConfig.parseKeystore("storeFile=C:/Users/chris/mamre-keys/mamre-release.jks\nstorePassword=abc12345\nkeyAlias=mamre\nkeyPassword=abc12345\n")!!
        assertEquals("C:/Users/chris/mamre-keys/mamre-release.jks", k.storeFile)
    }

    /** Raw backslashes would be read as escapes by java.util.Properties (C:Userschris...), so the script must convert them and refuse risky passwords. */
    @Test fun theKeystoreScriptConvertsBackslashesAndRefusesRiskyPasswords() {
        val script = File(androidDir(), "../tools/release/make-keystore.ps1").readText()
        val bs = 92.toChar()
        assertTrue("path must use forward slashes", script.contains("\$jks.Replace('$bs', '/')"))
        assertTrue("backslash passwords refused", script.contains("\$p1.Contains('$bs')"))
        assertTrue("leading or trailing space refused", script.contains("\$p1 -ne \$p1.Trim()"))
        val rawBackslashLine = "storeFile=C:" + 92.toChar() + "Users" + 92.toChar() + "chris" + 92.toChar() + "k.jks\nstorePassword=a\nkeyAlias=m\nkeyPassword=a\n"
        assertEquals("what a raw backslash path turns into", "C:Userschrisk.jks", ReleaseConfig.parseKeystore(rawBackslashLine)!!.storeFile)
    }

    /** The release signingConfig is set only under the keystore-present guard; without keystore.properties the release stays unsigned. */
    @Test fun theReleaseSigningConfigIsSetOnlyWhenKeystorePropertiesExists() {
        val gradle = repoFile("app/build.gradle.kts")
        val setLines = gradle.lines().filter { it.contains("signingConfig = signingConfigs") }
        assertEquals(1, setLines.size)
        assertTrue(setLines.single(), setLines.single().trimStart().startsWith("if (releaseKeystore != null)"))
        assertTrue(gradle.contains("val releaseKeystore = ReleaseConfig.parseKeystore(if (keystoreFile.exists()) keystoreFile.readText() else null)"))
        assertTrue(gradle.contains("if (releaseKeystore != null) {\n            create(\"mamreRelease\")") || gradle.replace("\r\n", "\n").contains("if (releaseKeystore != null) {\n            create(\"mamreRelease\")"))
    }
}
