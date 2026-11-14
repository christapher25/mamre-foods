package com.mamre.billing.release

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C4 (Doc 4 F7, install part; Doc 1 s2.1 FR-1 to FR-3, s2.2 AC-1): docs/INSTALL-GUIDE.txt is plain text, in the
 * agreed order, and every screen word it quotes is a real string literal in the app source.
 */
class InstallGuideTest {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "docs").isDirectory && File(it, "android").isDirectory }
    private val guideFile = File(repoRoot, "docs/INSTALL-GUIDE.txt")
    private val guide: String get() = guideFile.readText()
    private val guideFlat: String get() = guide.replace(Regex("\\s+"), " ")

    /** Words the Android system or this guide's own page names show; they are not app strings. */
    private val SYSTEM_WORDS = setOf("Install", "Open", "Mamre Foods", "How to update the app")

    /** Every double-quoted app word the guide uses (all but the Android system words of section 2). */
    private val QUOTED_PHRASES = listOf(
        "If you forget the admin password, the only way back in is to clear the app's data, which deletes everything.",
        "Create the admin account",
        "The admin sees everything. Choose a password of at least 6 characters and enter it twice.",
        "Your name (printed on bills)", "Username", "Password", "Password again", "Create account", "Log in",
        "Set up your business", "This prints on your bills. You can change it later in Admin, Settings.",
        "Business name", "Address (up to 4 lines)", "Phone", "Bill footer", "Save and continue", "Skip for now",
        "New invoice", "Record payment", "Return", "Today's invoices", "Admin area",
        "Finish setup", "Business details", "Prices:", "missing", "Prices",
        "Default prices", "No price", "Set new price", "New price per packet", "Effective from", "Save price",
        "No price set - contact admin",
        "Customers", "Add customer", "Save customer",
        "More", "Settings", "Users", "Add salesman", "Name (printed on bills)", "Sales area",
        "Walk-in sale", "Continue", "Amount received now", "Full", "Confirm invoice", "Confirm invoice?", "Confirm",
        "Printer not ready. The bill is saved. Tap Reprint.", "Print", "Reprint", "Done", "Payment",
        "A confirmed invoice cannot be edited.",
        "Log out", "Export data", "Export and share",
    )

    private val HEADINGS = listOf(
        "1. BEFORE YOU START", "2. INSTALL", "3. CREATE THE ADMIN ACCOUNT", "4. SET UP YOUR BUSINESS",
        "5. THE HOME SCREEN AND \"Finish setup\"", "6. SET THE PRICES", "7. ADD YOUR CUSTOMERS AND SALESMEN",
        "8. YOUR FIRST BILL", "9. EVERY DAY", "10. THE RULES TO REMEMBER",
    )

    @Test
    fun `guide exists, has the ten headings in order and no line over 80 characters`() {
        assertTrue("docs/INSTALL-GUIDE.txt is missing", guideFile.isFile)
        val lines = guide.lines()
        assertEquals("MAMRE FOODS - HOW TO INSTALL THE APP", lines.first())
        var from = 0
        for (h in HEADINGS) {
            val at = lines.indexOfFirst { it == h }.also { assertTrue("heading missing: $h", it >= 0) }
            assertTrue("heading out of order: $h", at >= from)
            from = at
        }
        val long = lines.filter { it.length > 80 }
        assertTrue("lines over 80 characters: $long", long.isEmpty())
    }

    @Test
    fun `every quoted phrase is in the guide and in the app source, and no other phrase is quoted`() {
        val source = File(repoRoot, "android/app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        val missingFromGuide = QUOTED_PHRASES.filter { "\"$it\"" !in guideFlat }
        assertTrue("not quoted in the guide: $missingFromGuide", missingFromGuide.isEmpty())
        // A phrase must be a whole string literal in the app; only these are part of a longer literal (a template or a second sentence).
        val partOfLiteral = setOf("Prices:", "missing", "A confirmed invoice cannot be edited.")
        val missingFromSource = QUOTED_PHRASES.filter { p ->
            if (p in partOfLiteral) !Regex("\"[^\"\\n]*" + Regex.escape(p) + "[^\"\\n]*\"").containsMatchIn(source)
            else "\"$p\"" !in source
        }
        assertTrue("not a string literal in the app: $missingFromSource", missingFromSource.isEmpty())
        // The guide quotes each phrase exactly: the quotes are paired and every quoted phrase is known.
        val quoted = Regex("\"([^\"]+)\"").findAll(guideFlat).map { it.groupValues[1] }.toSet()
        val unknown = quoted.filter { it !in QUOTED_PHRASES && it !in SYSTEM_WORDS }
        assertTrue("quoted in the guide but not listed: $unknown", unknown.isEmpty())
    }

    @Test
    fun `guide has none of the banned words`() {
        val text = guide
        assertTrue(Regex("\\bPIN\\b").find(text) == null)
        assertTrue(Regex("\\bAPK\\b").find(text) == null)
        for (w in listOf("screenshot", "picture", ".png")) {
            assertTrue("banned: $w", !text.contains(w, ignoreCase = true))
        }
        for (w in listOf("USB stick", "admin5", "Demo")) assertTrue("banned: $w", w !in text)
    }

    @Test
    fun `guide has the no-recovery sentence word for word and the never-uninstall rule`() {
        assertTrue(
            "If you forget the admin password, the only way back in is to clear the app's data, which deletes everything." in guideFlat,
        )
        assertTrue("NEVER UNINSTALL" in guide)
    }
}
