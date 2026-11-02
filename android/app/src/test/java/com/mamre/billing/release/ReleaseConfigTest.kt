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
}
