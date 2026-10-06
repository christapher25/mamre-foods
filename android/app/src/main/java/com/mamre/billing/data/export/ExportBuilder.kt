package com.mamre.billing.data.export

import androidx.room.withTransaction
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.domain.export.csvDocument
import java.time.Clock
import java.time.Instant

// Export to files (Doc 2 s5.3, Doc 1 s11.1): one CSV per table plus manifest.txt. This is the only way data leaves the phone, and it
// only reads. Money is the stored cents and quantities the stored milli-units, as plain integers; dates are the stored ISO text;
// the times that are stored as UTC epoch milliseconds are written as ISO 8601 UTC. Keys are the UUIDs the tables already use.

/** One file of the export: its name and its text (UTF-8 when written). */
data class ExportFile(val name: String, val text: String)

/** Everything one export writes: the table files first, in the order of [ExportTables], then manifest.txt. */
data class ExportBundle(val files: List<ExportFile>)

/** The table files, by the name the Owner sees (a few differ from the SQL table name) and the stored-epoch columns to write as ISO times. */
private class ExportTable(val file: String, val table: String, val timeColumns: Set<String> = emptySet())

private val TABLES = listOf(
    ExportTable("customers", "customers"),
    ExportTable("customer_types", "customer_types"),
    ExportTable("products", "products"),
    ExportTable("prices", "price_defaults"),
    ExportTable("price_overrides", "price_overrides"),
    ExportTable("invoices", "invoices", setOf("issued_at", "voided_at")),
    ExportTable("invoice_items", "invoice_items"),
    ExportTable("payments", "payments", setOf("paid_at")),
    ExportTable("returns", "return_records", setOf("occurred_at")),
    ExportTable("materials", "materials"),
    ExportTable("purchases", "material_purchases"),
    ExportTable("expenses", "expenses"),
    ExportTable("expense_categories", "expense_categories"),
    ExportTable("production_damage", "production_damage"),
    ExportTable("opening_stock", "opening_stock"),
    ExportTable("recipe_items", "recipe_items"),
    ExportTable("settings", "settings"),
    ExportTable("change_log", "change_log", setOf("at")),
)

object ExportTables {
    /** The CSV files an export writes, in order. */
    val fileNames: List<String> = TABLES.map { "${it.file}.csv" }
    const val MANIFEST = "manifest.txt"
}

/**
 * The settings that are never exported: the PIN hash and salt and the wrong-PIN count and lock time. Any key that starts with
 * "pin_" is left out too, so a PIN setting added later cannot reach a file by accident.
 */
fun isNeverExported(settingKey: String): Boolean = settingKey.startsWith("pin_")

class ExportBuilder(private val db: MamreDatabase, private val clock: Clock = Clock.systemUTC()) {
    /** Reads every table in ONE transaction, so the files and the manifest counts describe the same moment. */
    suspend fun build(): ExportBundle = db.withTransaction {
        val sqlite = db.openHelper.readableDatabase
        val counts = LinkedHashMap<String, Int>()
        val files = ArrayList<ExportFile>()
        for (t in TABLES) {
            sqlite.query("SELECT * FROM ${t.table} ORDER BY rowid").use { cursor ->
                val header = cursor.columnNames.toList()
                val rows = ArrayList<List<String>>()
                while (cursor.moveToNext()) {
                    val row = header.indices.map { i -> cell(cursor.getType(i), header[i] in t.timeColumns, cursor, i) }
                    if (t.table == "settings" && isNeverExported(row[header.indexOf("key")])) continue
                    rows.add(row)
                }
                counts[t.file] = rows.size
                files.add(ExportFile("${t.file}.csv", csvDocument(header, rows)))
            }
        }
        val version = sqlite.version
        files.add(ExportFile(ExportTables.MANIFEST, manifest(version, clock.instant(), counts)))
        ExportBundle(files)
    }

    private fun cell(type: Int, isTime: Boolean, cursor: android.database.Cursor, i: Int): String = when (type) {
        android.database.Cursor.FIELD_TYPE_NULL -> ""
        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i).let { if (isTime) Instant.ofEpochMilli(it).toString() else it.toString() }
        android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
        // No table has a floating point or a blob column (Doc 2 I-1); refuse rather than write a value that may not read back.
        else -> error("column ${cursor.getColumnName(i)} holds a value the export does not write")
    }

    private fun manifest(version: Int, at: Instant, counts: Map<String, Int>): String = buildString {
        append("Billing data export\r\n")
        append("data_version: $version\r\n")
        append("exported_at: $at\r\n")
        counts.forEach { (file, n) -> append("rows.$file: $n\r\n") }
        append("The PIN and its lockout state are not included.\r\n")
    }
}
