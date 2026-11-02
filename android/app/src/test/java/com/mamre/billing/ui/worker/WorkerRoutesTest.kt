package com.mamre.billing.ui.worker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Doc 1 s5.4: a bill opened from Today's bills is a reprint, so the receipt is marked DUPLICATE COPY. */
class WorkerRoutesTest {
    @Test fun aBillOpenedFromTodaysBillsOpensTheReceiptAsADuplicate() {
        val route = WorkerRoutes.duplicateReceipt("inv-1")
        assertEquals("sales/receipt/invoice/inv-1?duplicate=true", route)
        assertEquals(WorkerRoutes.receipt(WorkerRoutes.KIND_INVOICE, "inv-1", duplicate = true), route)
    }

    @Test fun aFreshBillOrPaymentReceiptIsNotADuplicate() {
        assertEquals("sales/receipt/invoice/inv-1?duplicate=false", WorkerRoutes.receipt(WorkerRoutes.KIND_INVOICE, "inv-1"))
    }
}
