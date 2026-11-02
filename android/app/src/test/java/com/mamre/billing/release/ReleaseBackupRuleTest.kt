package com.mamre.billing.release

import com.mamre.build.ReleaseManifestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doc 1 A-26: the merged release manifest must switch backup off (ReleaseManifestRules.backupProblems, run by verifyReleaseComponents). */
class ReleaseBackupRuleTest {
    private fun manifest(attr: String) =
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application $attr><activity android:name="x"/></application></manifest>"""

    @Test fun allowBackupFalseIsClean() = assertEquals(emptyList<String>(), ReleaseManifestRules.backupProblems(manifest("""android:allowBackup="false"""")))

    @Test fun allowBackupTrueIsRejected() = assertTrue(ReleaseManifestRules.backupProblems(manifest("""android:allowBackup="true"""")).isNotEmpty())

    @Test fun aMissingAllowBackupIsRejectedBecauseTheAndroidDefaultIsOn() = assertTrue(ReleaseManifestRules.backupProblems(manifest("")).isNotEmpty())
}
