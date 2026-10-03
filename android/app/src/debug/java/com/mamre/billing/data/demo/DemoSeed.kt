package com.mamre.billing.data.demo

import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentRecord
import java.time.LocalDateTime

/** Ids shared by FakeApi's catalog and the demo ledger. DEMO DATA. */
object DemoIds {
    const val FRESH = "00000000-0000-4000-8000-0000000000f1"
    const val CHAPATHI = "00000000-0000-4000-8000-0000000000f2"
    const val RESTAURANT_TYPE = "00000000-0000-4000-8000-0000000000e1"
    const val SHOP_TYPE = "00000000-0000-4000-8000-0000000000e2"
    const val RETAIL_TYPE = "00000000-0000-4000-8000-0000000000e3"
    const val CATERING_TYPE = "00000000-0000-4000-8000-0000000000e4"
    const val RESTAURANT = "00000000-0000-4000-8000-0000000000c1"
    const val SHOP = "00000000-0000-4000-8000-0000000000c2"
    const val RETAIL_CUSTOMER = "00000000-0000-4000-8000-0000000000c3"
    const val CATERING = "00000000-0000-4000-8000-0000000000c4"
}

/**
 * DEMO DATA. The credit restaurant carries the Doc 1 s6.5 ledger, moved to September 2026 so
 * October opens with $120.00 brought forward (owner decision):
 *   Sep 3  Invoice 1  $120.00 -> $120.00
 *   Sep 10 Invoice 2   $90.00 -> $210.00
 *   Sep 12 Payment    -$150.00 ->  $60.00
 *   Sep 20 Invoice 3   $60.00 -> $120.00
 * Prices are the invented test prices in FakeApi's catalog (Doc 1 P-4 is pending).
 */
object DemoSeed {
    private const val SALESMAN = "Rajesh"
    private const val RESTAURANT_NAME = "Spice Garden"
    private const val RESTAURANT_TYPE_NAME = "Restaurant"
    private const val CHAPATHI_PRICE_CENTS = 250L

    fun state(): DemoState = DemoState(
        invoices = listOf(
            invoice("0001", 3, 48),
            invoice("0002", 10, 36),
            invoice("0003", 20, 24),
        ),
        payments = listOf(
            PaymentRecord(
                id = "00000000-0000-4000-8000-00000000d0a1",
                receiptNumber = "RCP-DEMO-0001",
                customerId = DemoIds.RESTAURANT,
                customerName = RESTAURANT_NAME,
                deviceCode = "DEMO",
                paidAt = LocalDateTime.of(2026, 9, 12, 10, 0),
                amountCents = 15000,
                method = PaymentMethod.CASH,
                note = "",
                salesmanName = SALESMAN,
            ),
        ),
    )

    /** 48 packets at $2.50 = $120.00, 36 = $90.00, 24 = $60.00. Balance after is the running total. */
    private fun invoice(seq: String, day: Int, packets: Int): InvoiceRecord {
        val line = InvoiceLine(DemoIds.CHAPATHI, "Mamre Chapathi", packets, CHAPATHI_PRICE_CENTS)
        val balanceAfter = when (seq) {
            "0001" -> 12000L
            "0002" -> 21000L
            else -> 12000L // Invoice 3 comes after the $150.00 payment: 60.00 + 60.00
        }
        return InvoiceRecord(
            id = "00000000-0000-4000-8000-0000000d$seq",
            number = "MAM-DEMO-$seq",
            customerId = DemoIds.RESTAURANT,
            customerName = RESTAURANT_NAME,
            customerTypeName = RESTAURANT_TYPE_NAME,
            deviceCode = "DEMO",
            issuedAt = LocalDateTime.of(2026, 9, day, 9, 0),
            lines = listOf(line),
            totalCents = line.lineTotalCents,
            paidNowCents = 0,
            method = null,
            balanceAfterCents = balanceAfter,
            salesmanName = SALESMAN,
        )
    }
}
