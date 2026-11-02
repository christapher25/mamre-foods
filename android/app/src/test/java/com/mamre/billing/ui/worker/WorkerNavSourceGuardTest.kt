package com.mamre.billing.ui.worker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doc 1 s5.4: a bill opened from Today's bills is a reprint and opens marked DUPLICATE COPY. WorkerRoutesTest pins the route
 * function; this guard reads WorkerNav.kt so the call site cannot drift back to a plain receipt route.
 */
class WorkerNavSourceGuardTest {
    private fun workerNav(): String {
        val base = listOf(File("src"), File("app/src")).first { it.isDirectory }
        return File(base, "main/java/com/mamre/billing/ui/worker/WorkerNav.kt").readText()
    }

    @Test fun todaysBillsOpensTheReceiptThroughDuplicateReceipt() {
        val src = workerNav()
        val block = src.substringAfter("TodayScreen(").substringBefore("navigation(route = WorkerRoutes.INVOICE_GRAPH")
        assertTrue("the Today's bills onOpen must call WorkerRoutes.duplicateReceipt(it.id)", "WorkerRoutes.duplicateReceipt(it.id)" in block)
        assertEquals("no receipt may be opened with duplicate = false from the Today's bills block", false, Regex("""duplicate\s*=\s*false""").containsMatchIn(block))
    }

    @Test fun workerNavNeverWritesDuplicateEqualsFalse() {
        assertEquals(false, Regex("""duplicate\s*=\s*false""").containsMatchIn(workerNav()))
    }
}
