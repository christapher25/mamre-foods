package com.mamre.billing.print

import com.mamre.build.ReleaseManifestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doc 2 s6, review item 8: the rule that the Gradle task verifyReleaseComponents runs, on sample manifest TEXT. The task calls the
 * very same function ([ReleaseManifestRules.problems]); the task adds only the sentence "the merged release manifest opens doors it
 * should not" around these lines.
 */
class ReleaseManifestRulesTest {
    private val allowed = setOf("androidx.startup.InitializationProvider", "androidx.room.MultiInstanceInvalidationService")

    private val launcher = """
        <activity android:name="com.mamre.billing.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>"""

    private fun manifest(vararg extra: String) = """<?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.mamre.billing">
            <application>$launcher
            ${extra.joinToString("\n")}
            </application>
        </manifest>"""

    private fun problems(vararg extra: String) = ReleaseManifestRules.problems(manifest(*extra), allowed)

    @Test fun theLauncherAloneIsFine() = assertEquals(emptyList<String>(), problems())

    @Test fun anExportedActivityIsRejected() {
        val p = problems("""<activity android:name="com.x.Other" android:exported="true" />""")
        assertEquals(listOf("activity com.x.Other is not the launcher"), p)
    }

    @Test fun aSecondLauncherIsRejected() {
        val p = problems("""<activity-alias android:name="com.x.Alias" android:targetActivity="com.mamre.billing.MainActivity" android:exported="true">
            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
            </activity-alias>""")
        assertTrue(p.toString(), p.any { it.startsWith("expected exactly one launcher activity, found 2") })
    }

    @Test fun anExportedServiceIsRejected() {
        val p = problems("""<service android:name="com.x.Svc" android:exported="true" />""")
        assertEquals(listOf("exported service com.x.Svc is not on the allowlist"), p)
    }

    @Test fun anExportedProviderIsRejected() {
        val p = problems("""<provider android:name="com.x.Prov" android:authorities="com.x" android:exported="true" />""")
        assertEquals(listOf("exported provider com.x.Prov is not on the allowlist"), p)
    }

    @Test fun anExportedReceiverIsRejectedWithTheRulesOwnMessage() {
        val p = problems("""<receiver android:name="com.x.Rcv" android:exported="true" />""")
        assertEquals(listOf("exported receiver com.x.Rcv is not on the allowlist"), p)
    }

    @Test fun aReceiverWithAnIntentFilterAndNoExportedAttributeCountsAsExported() {
        val p = problems("""<receiver android:name="com.x.Rcv"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter></receiver>""")
        assertEquals(listOf("exported receiver com.x.Rcv is not on the allowlist"), p)
    }

    @Test fun anAllowlistedEntryIsAccepted() {
        val p = problems("""<provider android:name="androidx.startup.InitializationProvider" android:authorities="x" android:exported="true" />""")
        assertEquals(emptyList<String>(), p)
    }

    @Test fun aNonExportedServiceProviderAndReceiverAreAccepted() {
        val p = problems(
            """<service android:name="com.x.Svc" android:exported="false" />""",
            """<provider android:name="androidx.core.content.FileProvider" android:authorities="x" android:exported="false" />""",
            """<receiver android:name="com.x.Rcv" android:exported="false"><intent-filter><action android:name="a" /></intent-filter></receiver>""",
        )
        assertEquals(emptyList<String>(), p)
    }

    @Test fun aManifestWithoutAnApplicationOrWithADoctypeIsNotSilentlyAccepted() {
        val bad = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"y\">]><manifest/>"
        val failed = runCatching { ReleaseManifestRules.problems(bad, allowed) }.isFailure
        assertTrue("a doctype or a missing application must not pass as clean", failed)
    }
}
