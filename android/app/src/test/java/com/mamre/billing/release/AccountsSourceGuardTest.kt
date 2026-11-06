package com.mamre.billing.release

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B8 guards, read from the source files (the release build compiles only src/main):
 *  - L2: no owner name setting, field, use case or Finish setup condition is left;
 *  - item j: no PIN screen, use case or setting key is left;
 *  - item k: no demo account, demo login or demo text is in src/main, so none can reach a release package.
 * The release APK itself is unzipped and searched in the report (out\B8) on top of this.
 */
class AccountsSourceGuardTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }.canonicalFile

    private fun mainText(): String =
        File(app, "src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.joinToString("\n") { it.readText() }

    @Test fun noOwnerNameSettingFieldOrUseCaseIsLeft() {
        val text = mainText()
        for (word in listOf("owner_name", "OWNER_NAME", "ownerName", "SetOwnerName", "cleanOwnerName", "MIN_OWNER_NAME")) {
            assertTrue("$word is still in src/main", !text.contains(word))
        }
    }

    @Test fun noPinScreenUseCaseOrSettingKeyIsLeft() {
        val text = mainText()
        for (word in listOf("PIN_HASH", "PIN_SALT", "PIN_FAIL_COUNT", "PIN_LOCKED_UNTIL", "SetPin", "VerifyPin", "ChangePin", "PinStatus", "PinScreen", "PinViewModel")) {
            assertTrue("$word is still in src/main", !text.contains(word))
        }
    }

    @Test fun noDemoAccountOrDemoLoginIsInTheReleaseSources() {
        val text = mainText()
        for (word in listOf("admin5", "user1", "Demo Admin", "Demo Salesman", "DemoLoginGate", "DemoSeeder", "DEMO DATA")) {
            assertTrue("$word is in src/main, so it would ship in the release", !text.contains(word))
        }
    }

    @Test fun theDemoAccountsExistOnlyInTheDebugSources() {
        val debug = File(app, "src/debug").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(debug.contains("admin5") && debug.contains("user1") && debug.contains("Demo Admin") && debug.contains("Demo Salesman"))
        assertEquals(false, File(app, "src/debug/java/com/mamre/billing/ui/login/DemoLoginGate.kt").exists())
    }

    /** B8 item e: the Admin tile, the Export card and the Finish setup card (its items open Admin screens) are hidden for a salesman. */
    @Test fun theSalesHomeHidesTheAdminTileTheExportCardAndTheFinishSetupCardFromASalesman() {
        val home = File(app, "src/main/java/com/mamre/billing/ui/worker/HomeScreen.kt").readText()
        assertTrue("isAdmin is read from the logged-in account's role", home.contains("val isAdmin = account?.role == Role.ADMIN"))
        assertTrue("Finish setup card", home.contains("if (isAdmin && state.setup.any) FinishSetupCard("))
        assertTrue("Export card", home.contains("if (isAdmin) state.exportDue?.let { ExportReminderCard("))
        val tile = home.indexOf("GridTile(\"Admin area\"")
        assertTrue("Admin tile exists", tile > 0)
        assertTrue("Admin tile sits inside an isAdmin branch", home.lastIndexOf("if (isAdmin) {", tile) in (tile - 200) until tile)
    }
}
