package com.mamre.billing.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AT-10 (Doc 2 I-4, I-9, I-14, s4.1): no DAO method updates or deletes a bill, bill item, payment, return, purchase,
 * expense, production damage entry or change log row. The only change is a bill going active to void, and a
 * correction is a linked reversing row. The scan reads the DAO sources, so a new method cannot slip in.
 */
class DaoScanTest {
    /** The DAOs of the protected tables. Every DAO in the package must be listed in one of the two sets. */
    private val protectedDaos = setOf(
        "InvoiceDao", "InvoiceItemDao", "PaymentDao", "ReturnDao",
        "MaterialPurchaseDao", "ProductionDamageDao", "ExpenseDao", "ChangeLogDao", "OpeningStockDao",
    )
    private val otherDaos = setOf(
        "CustomerTypeDao", "ProductDao", "CustomerDao", "PriceDefaultDao", "PriceOverrideDao", "SettingDao",
        "MaterialDao", "RecipeDao", "ExpenseCategoryDao",
    )
    private val allowedMethods = setOf("InvoiceDao.markVoid")

    private fun daoSource(): String {
        val base = listOf(File("src"), File("app/src")).first { it.isDirectory }
        val dir = File(base, "main/java/com/mamre/billing/data/local")
        return dir.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }

    @Test fun noDaoMethodUpdatesOrDeletesAProtectedTable() {
        val found = violations(daoSource())
        assertTrue("forbidden DAO methods: $found", found.isEmpty())
    }

    @Test fun everyDaoIsClassifiedSoANewOneCannotBeMissed() {
        val names = Regex("""interface\s+(\w+Dao)\b""").findAll(stripComments(daoSource())).map { it.groupValues[1] }.toSet()
        assertEquals(protectedDaos + otherDaos, names)
    }

    @Test fun theOnlyVoidStatementOnlyMovesActiveToVoid() {
        val sql = Regex("""@Query\("(UPDATE invoices[^"]*)"\)""").find(daoSource())!!.groupValues[1]
        assertEquals(
            "UPDATE invoices SET status = 'void', void_reason = :reason, voided_at = :at WHERE id = :id AND status = 'active'",
            sql,
        )
    }

    @Test fun openingStockIsProtectedItIsSetOnceAndNeverEditedOrDeleted() {
        assertTrue("opening_stock" in ProtectedTables.names)
        val bad = "@Dao interface OpeningStockDao { @Update suspend fun edit(row: OpeningStockEntity): Int }"
        assertTrue(violations(bad).isNotEmpty())
        val badQuery = "@Dao interface OpeningStockDao { @Query(\"DELETE FROM opening_stock\") suspend fun wipe() }"
        assertTrue(violations(badQuery).isNotEmpty())
    }

    @Test fun theProtectedTableListMatchesTheEntities() {
        val entities = Regex("""@Entity\(\s*tableName = "(\w+)"""").findAll(daoSource()).map { it.groupValues[1] }.toSet()
        assertTrue(entities.containsAll(ProtectedTables.names))
    }

    // The scanner itself is tested against bad samples, so a scanner that finds nothing cannot pass by accident.

    @Test fun theScannerCatchesAnUpdateAnnotationOnAProtectedDao() {
        val bad = "@Dao interface PaymentDao { @Update suspend fun edit(row: PaymentEntity) }"
        assertTrue(violations(bad).isNotEmpty())
    }

    @Test fun theScannerCatchesDeleteUpsertRawAndReplaceOnAProtectedDao() {
        val dao = "@Dao interface ExpenseDao { %s }"
        listOf(
            "@Delete suspend fun del(row: ExpenseEntity)",
            "@Upsert suspend fun put(row: ExpenseEntity)",
            "@RawQuery suspend fun raw(q: SupportSQLiteQuery): List<ExpenseEntity>",
            "@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: ExpenseEntity)",
            "@Query(\"DELETE FROM expenses\") suspend fun wipe()",
            "@Query(\"UPDATE expenses SET amount_cents = 0\") suspend fun zero()",
            "@Query(\"INSERT OR REPLACE INTO expenses SELECT * FROM expenses\") suspend fun dup()",
        ).forEach { assertTrue("not caught: $it", violations(dao.format(it)).isNotEmpty()) }
    }

    @Test fun theScannerCatchesAStatementOnAProtectedTableFromAnyOtherDao() {
        val bad = "@Dao interface CustomerDao { @Query(\"DELETE FROM payments\") suspend fun wipe() }"
        assertTrue(violations(bad, checkClassified = false).isNotEmpty())
    }

    @Test fun theScannerAllowsUpdatesOnTablesThatAreNotProtected() {
        val ok = "@Dao interface CustomerDao { @Update suspend fun update(row: CustomerEntity): Int " +
            "@Query(\"UPDATE customers SET phone = :p WHERE id = :id\") suspend fun phone(id: String, p: String) }"
        assertEquals(emptyList<String>(), violations(ok, checkClassified = false))
    }

    private fun stripComments(src: String) = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.replace(Regex("//.*$"), "") }

    private fun violations(source: String, checkClassified: Boolean = true): List<String> {
        val src = stripComments(source)
        val out = mutableListOf<String>()
        val starts = Regex("""interface\s+(\w+Dao)\b""").findAll(src).toList()
        starts.forEachIndexed { i, m ->
            val dao = m.groupValues[1]
            val body = src.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: src.length)
            val isProtected = dao in protectedDaos
            if (checkClassified && dao !in protectedDaos && dao !in otherDaos) out += "$dao is not classified"
            if (isProtected) {
                Regex("""@(Update|Delete|Upsert|RawQuery)\b""").findAll(body).forEach { out += "$dao uses @${it.groupValues[1]}" }
                if (Regex("""onConflict\s*=\s*OnConflictStrategy\.REPLACE""").containsMatchIn(body)) out += "$dao inserts with REPLACE"
            }
            val queries = Regex("""@Query\(\s*"((?:[^"\\]|\\.)*)"\s*\)\s*(?:suspend\s+)?fun\s+(\w+)""").findAll(body)
            for (q in queries) {
                val (sql, fn) = q.destructured
                if ("$dao.$fn" in allowedMethods) continue
                if (isProtected && Regex("""(?i)\b(UPDATE|DELETE|REPLACE|INSERT)\b""").containsMatchIn(sql)) out += "$dao.$fn writes with a query"
                for (t in ProtectedTables.names) {
                    val hit = Regex("""(?i)\b(UPDATE|DELETE\s+FROM|INSERT\s+(OR\s+\w+\s+)?INTO|REPLACE\s+INTO)\s+`?$t`?\b""").containsMatchIn(sql)
                    if (hit) out += "$dao.$fn writes to $t"
                }
            }
        }
        return out.distinct()
    }
}
