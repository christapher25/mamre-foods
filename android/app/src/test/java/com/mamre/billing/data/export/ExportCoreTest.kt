package com.mamre.billing.data.export

import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.ChangePin
import com.mamre.billing.domain.usecase.SetPin
import com.mamre.billing.domain.usecase.VerifyPin
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Export core (Doc 2 s5.3, Doc 1 s11.1): one CSV per table plus manifest.txt, read from the real database. The files are parsed
 * back with an independent RFC 4180 reader written here, so the writer is not graded by itself.
 */
@RunWith(RobolectricTestRunner::class)
class ExportCoreTest {
    private val tableFiles = listOf(
        "customers", "customer_types", "products", "prices", "price_overrides", "invoices", "invoice_items", "payments", "returns",
        "materials", "purchases", "expenses", "expense_categories", "production_damage", "opening_stock", "recipe_items", "settings", "change_log",
    )

    /** The table behind each file, for the independent row count. */
    private val sqlTable = mapOf(
        "prices" to "price_defaults", "returns" to "return_records", "purchases" to "material_purchases",
    )

    /** An independent reader for RFC 4180: quoted fields, doubled quotes, commas and line breaks inside quotes, CRLF records. */
    private fun parseCsv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> quoted = true
                c == ',' -> { record.add(field.toString()); field.clear() }
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    record.add(field.toString()); field.clear(); records.add(record); record = mutableListOf(); i++
                }
                else -> field.append(c)
            }
            i++
        }
        assertFalse("the file ends inside a quoted field", quoted)
        assertTrue("the last record ends with CRLF", field.isEmpty() && record.isEmpty())
        return records
    }

    private fun ExportBundle.csv(name: String) = parseCsv(files.first { it.name == "$name.csv" }.text)

    private fun ExportBundle.rows(name: String): List<Map<String, String>> {
        val all = csv(name)
        return all.drop(1).map { r -> all[0].zip(r).toMap() }
    }

    private fun count(db: MamreDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun fullWorld(): World {
        val w = World(TestDatabase.inMemory())
        w.seed()
        w.settings.put(SettingKeys.OWNER_NAME, "Pat Tester")
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 280)
        w.price(ReferenceIds.TYPE_RETAIL, 260, 290)
        val c = w.customer("Mario's \"Pizza\", Plano", "Irving", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT, opening = 1_234)
        w.setOverride(c.id, ReferenceIds.PRODUCT_FRESH, 240, LocalDate.of(2026, 9, 1), "regular, big order")
        w.clock.set("2026-10-04T10:00:00Z")
        val bill = w.makeBill(w.draft(c.id, listOf(w.line(qty = 7, unit = 240, list = 240)), paid = 500, method = PaymentMethod.CASH))
        w.makeBill(w.draft(null, listOf(w.line(qty = 2, unit = 270, list = 260)), paid = 540, method = PaymentMethod.CASH)) // a changed price, Retail may
        w.recordPayment(w.payment(c.id, 300, PaymentMethod.ZELLE, note = "line one\nline two, \"quoted\""))
        w.recordReturn(w.returnOf(c.id, 1, credit = true, invoiceId = bill.id))
        val purchase = w.addPurchase(ReferenceIds.MATERIAL_WHEAT, LocalDate.of(2026, 10, 1), 25_000_000L, 4_250, "sack")
        w.reversePurchase(purchase.id, "typed the wrong weight")
        w.addExpense(ReferenceIds.CATEGORY_ELECTRICITY, LocalDate.of(2026, 10, 2), 9_950, "October")
        w.addDamage(ReferenceIds.PRODUCT_FRESH, LocalDate.of(2026, 10, 3), 24, "dropped tray")
        w.setOpeningStock(ReferenceIds.MATERIAL_OIL, YearMonth.of(2026, 10), 12_500L, 3_000)
        w.setRecipe(ReferenceIds.PRODUCT_CHAPATHI, ReferenceIds.MATERIAL_SORBATE, 1_500L)
        w.voidBill(w.makeBill(w.draft(null, listOf(w.line(qty = 1, unit = 260)), paid = 260, method = PaymentMethod.CASH)).id, "wrong item")
        return w
    }

    @Test fun everyTableIsWrittenWithAHeaderRowAndTheManifest() = runBlocking {
        val w = fullWorld()
        val bundle = ExportBuilder(w.db, w.clock).build()
        assertEquals(tableFiles.map { "$it.csv" } + "manifest.txt", bundle.files.map { it.name })
        for (name in tableFiles) {
            val sqlName = sqlTable[name] ?: name
            val columns = w.db.openHelper.readableDatabase.query("SELECT * FROM $sqlName LIMIT 0").use { it.columnNames.toList() }
            assertEquals("header of $name", columns, bundle.csv(name).first())
        }
    }

    @Test fun theManifestRowCountsMatchTheDatabaseAndTheFiles() = runBlocking {
        val w = fullWorld()
        val bundle = ExportBuilder(w.db, w.clock).build()
        val manifest = bundle.files.first { it.name == "manifest.txt" }.text.lines().filter { it.isNotBlank() }
        val kv = manifest.filter { ": " in it }.associate { it.substringBefore(": ") to it.substringAfter(": ") }
        assertEquals("1", kv["data_version"]) // the Room schema version
        assertEquals("2026-10-04T10:00:00Z", kv["exported_at"]) // the injected clock, ISO 8601 UTC
        for (name in tableFiles) {
            val expected = count(w.db, sqlTable[name] ?: name)
            assertEquals("manifest count of $name", expected.toString(), kv["rows.$name"])
            assertEquals("file rows of $name", expected, bundle.csv(name).size - 1)
        }
        assertTrue(count(w.db, "invoices") == 3 && count(w.db, "material_purchases") == 2)
    }

    @Test fun thePinHashSaltAndLockoutKeysAreInNoFile() = runBlocking {
        val w = fullWorld()
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("4821", "4821")
        val verify = VerifyPin(w.unitOfWork, w.settings, w.clock)
        repeat(5) { verify("0000") } // five wrong PINs write pin_fail_count and pin_locked_until
        val hash = w.settings.get(SettingKeys.PIN_HASH)!!
        val salt = w.settings.get(SettingKeys.PIN_SALT)!!
        assertEquals("5", w.settings.get(SettingKeys.PIN_FAIL_COUNT))
        assertTrue(w.settings.get(SettingKeys.PIN_LOCKED_UNTIL)!!.isNotEmpty())
        val bundle = ExportBuilder(w.db, w.clock).build()
        for (file in bundle.files) {
            for (needle in listOf(hash, salt, "pin_hash", "pin_salt", "pin_fail_count", "pin_locked_until", "pbkdf2")) {
                assertFalse("${file.name} contains $needle", file.text.contains(needle))
            }
        }
        // The other settings are there: the owner name, and the manifest count leaves the four PIN rows out.
        val keys = bundle.rows("settings").map { it["key"] }
        assertTrue("owner_name" in keys && "device_code" in keys && "next_bill_seq" in keys)
        assertEquals(count(w.db, "settings") - 4, keys.size)
        // The change log says a PIN was set but never what it is.
        assertTrue(bundle.rows("change_log").any { it["what"] == "PIN set" })
    }

    @Test fun anyKeyStartingWithPinIsLeftOutSoANewSecretCannotLeakByAccident() = runBlocking {
        val w = fullWorld()
        w.settings.put("pin_future_secret", "s3cret")
        val bundle = ExportBuilder(w.db, w.clock).build()
        assertFalse(bundle.files.any { it.text.contains("s3cret") || it.text.contains("pin_future_secret") })
    }

    @Test fun aCustomerNameWithQuotesAndCommasRoundTripsAndIsQuotedPerRfc4180() = runBlocking {
        val w = fullWorld()
        val bundle = ExportBuilder(w.db, w.clock).build()
        val row = bundle.rows("customers").single()
        assertEquals("Mario's \"Pizza\", Plano", row["name"])
        val text = bundle.files.first { it.name == "customers.csv" }.text
        assertTrue(text.contains("\"Mario's \"\"Pizza\"\", Plano\""))
        assertEquals("line one\nline two, \"quoted\"", bundle.rows("payments").first { it["method"] == "zelle" }["note"])
    }

    @Test fun lineEndsAreCrlfOutsideQuotesAndEveryRecordHasTheSameNumberOfFields() = runBlocking {
        val w = fullWorld()
        val bundle = ExportBuilder(w.db, w.clock).build()
        for (name in tableFiles) {
            val text = bundle.files.first { it.name == "$name.csv" }.text
            assertTrue("$name ends with CRLF", text.endsWith("\r\n"))
            assertEquals("$name has a bare LF outside quotes", 0, bareLineFeedsOutsideQuotes(text))
            val records = bundle.csv(name)
            assertTrue(records.all { it.size == records[0].size })
        }
    }

    private fun bareLineFeedsOutsideQuotes(text: String): Int {
        var quoted = false
        var bare = 0
        for ((i, c) in text.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == '\n' && !quoted && (i == 0 || text[i - 1] != '\r')) bare++
        }
        return bare
    }

    @Test fun moneyIsCentsQuantitiesAreMilliUnitsAndTimesAreIsoUtc() = runBlocking {
        val w = fullWorld()
        val bundle = ExportBuilder(w.db, w.clock).build()
        val item = bundle.rows("invoice_items").first { it["unit_price_cents"] == "240" }
        assertEquals("240", item["list_price_cents"])
        assertEquals("1680", item["line_total_cents"]) // 7 x 240, an integer
        assertEquals("0", item["price_overridden"]) // flags stay 0 and 1
        val changed = bundle.rows("invoice_items").first { it["unit_price_cents"] == "270" }
        assertEquals("260", changed["list_price_cents"])
        assertEquals("1", changed["price_overridden"])
        assertEquals("540", changed["line_total_cents"])
        val purchase = bundle.rows("purchases").first { it["reverses_id"].isNullOrEmpty() }
        assertEquals("25000000", purchase["qty_milli"])
        assertEquals("4250", purchase["total_paid_cents"])
        assertEquals("2026-10-01", purchase["purchased_on"]) // dates are ISO text
        val reversal = bundle.rows("purchases").first { it["reverses_id"]?.isNotEmpty() == true }
        assertEquals("-25000000", reversal["qty_milli"])
        assertEquals("-4250", reversal["total_paid_cents"])
        assertEquals("12500", bundle.rows("opening_stock").single()["qty_milli"])
        assertEquals("1234", bundle.rows("customers").single()["opening_balance_cents"])
        assertEquals("1500", bundle.rows("recipe_items").first { it["material_id"] == ReferenceIds.MATERIAL_SORBATE }["qty_milli_per_kg_wheat"])
        val invoice = bundle.rows("invoices").first { it["status"] == "void" }
        assertEquals("2026-10-04T10:00:00Z", invoice["issued_at"])
        assertEquals("2026-10-04T10:00:00Z", invoice["voided_at"])
        assertEquals("260", invoice["total_cents"])
        assertEquals("", bundle.rows("invoices").first { it["status"] == "active" }["voided_at"]) // a null is an empty field
        // Nothing in a money or quantity column has a decimal point, an exponent or a currency sign.
        val numeric = Regex("^-?[0-9]+$")
        for (name in tableFiles) for (row in bundle.rows(name)) for ((column, value) in row) {
            if (column.endsWith("_cents") || column.endsWith("_milli") || column.startsWith("qty_") || column.endsWith("_bp")) {
                assertTrue("$name.$column = '$value'", value.isEmpty() || numeric.matches(value))
            }
        }
    }

    @Test fun everyKeyIsAUuid() = runBlocking {
        val w = fullWorld()
        val bundle = ExportBuilder(w.db, w.clock).build()
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        for (name in tableFiles - setOf("settings")) for (row in bundle.rows(name)) assertTrue("$name ${row["id"]}", uuid.matches(row.getValue("id")))
    }

    @Test fun anEmptyDatabaseExportsHeadersOnly() = runBlocking {
        val w = fullWorld()
        val db = w.db.openHelper.writableDatabase
        db.execSQL("PRAGMA foreign_keys = OFF")
        for (t in listOf(
            "payments", "return_records", "invoice_items", "invoices", "price_overrides", "price_defaults", "recipe_items", "production_damage",
            "material_purchases", "opening_stock", "expenses", "expense_categories", "materials", "customers", "products", "customer_types",
            "change_log", "settings",
        )) db.execSQL("DELETE FROM $t")
        val bundle = ExportBuilder(w.db, w.clock).build()
        assertEquals(tableFiles.map { "$it.csv" } + "manifest.txt", bundle.files.map { it.name })
        for (name in tableFiles) {
            val records = bundle.csv(name)
            assertEquals("$name has the header and nothing else", 1, records.size)
            assertTrue(records[0].isNotEmpty() && records[0].all { it.isNotBlank() })
        }
        val manifest = bundle.files.first { it.name == "manifest.txt" }.text
        for (name in tableFiles) assertTrue("rows.$name: 0" in manifest)
    }

    @Test fun exportingChangesNothingInTheDatabase() = runBlocking {
        val w = fullWorld()
        val before = tableFiles.sumOf { count(w.db, sqlTable[it] ?: it) }
        ExportBuilder(w.db, w.clock).build()
        ExportBuilder(w.db, w.clock).build()
        assertEquals(before, tableFiles.sumOf { count(w.db, sqlTable[it] ?: it) })
    }

    @Test fun aChangedPinIsAlsoNeverExported() = runBlocking {
        val w = fullWorld()
        // A PIN is digits only, so its digits can occur by chance inside a hex UUID or an amount: the test therefore never
        // searches for the PIN's digits. It checks the four keys and the stored hash and salt (random, long, never a UUID).
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("4821", "4821")
        ChangePin(w.unitOfWork, w.settings, w.changeLog, w.clock)("4821", "9034", "9034")
        val hash = w.settings.get(SettingKeys.PIN_HASH)!!
        val salt = w.settings.get(SettingKeys.PIN_SALT)!!
        assertTrue(hash.isNotEmpty() && salt.isNotEmpty())
        val bundle = ExportBuilder(w.db, w.clock).build()
        val exportedKeys = bundle.rows("settings").map { it.getValue("key") }
        for (key in listOf("pin_hash", "pin_salt", "pin_fail_count", "pin_locked_until")) {
            assertFalse("$key is exported", key in exportedKeys)
        }
        assertTrue("the other settings are still exported", exportedKeys.isNotEmpty())
        assertTrue(exportedKeys.none { it.startsWith("pin_") })
        for (f in bundle.files) {
            assertFalse("${f.name} holds the PIN hash", f.text.contains(hash))
            assertFalse("${f.name} holds the PIN salt", f.text.contains(salt))
            assertFalse("${f.name} holds a PIN hash prefix", f.text.contains("pbkdf2-sha256"))
        }
    }
}
