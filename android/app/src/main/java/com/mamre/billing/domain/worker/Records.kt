package com.mamre.billing.domain.worker

import java.time.LocalDateTime

// Worker-side records (Doc 2 s4.2). Everything is a val: a confirmed invoice, payment or return
// is never edited or deleted (Doc 3 N3, Doc 2 I-4, I-9). The only change an invoice ever
// gets is Admin void (status ACTIVE to VOID with a reason), which is a new copy, and it
// happens on the Admin side. No cost, profit or expense field exists here (Doc 2 I-8).

enum class PaymentMethod(val label: String) {
    CASH("Cash"),
    ZELLE("Zelle"),
    CHECK("Check"),
    CARD("Card"),
    OTHER("Other"),
}

enum class InvoiceStatus { ACTIVE, VOID }

/** One product line. The unit price is a snapshot (Doc 2 I-7). */
data class InvoiceLine(
    val productId: String,
    val productName: String,
    val qtyPackets: Int,
    /** The price charged per packet. Equals [listPriceCents] unless a worker changed it (change set C3). */
    val unitPriceCents: Long,
    /** Chapathis in each packet: 6 for a standard packet, 1 to 200 for a custom one (change set C2). */
    val chapathisPerPacket: Int = DEFAULT_PACKET_SIZE,
    /** The price list says this per packet of this size; the default is the charged price. */
    val listPriceCents: Long = unitPriceCents,
    /** A packet of a size other than the product's standard one. */
    val isCustomPacket: Boolean = false,
) {
    init {
        require(qtyPackets > 0) { "qtyPackets must be positive" }
        require(unitPriceCents > 0) { "unitPriceCents must be positive (a missing price is never zero)" }
        require(listPriceCents > 0) { "listPriceCents must be positive" }
        require(isValidPacketSize(chapathisPerPacket)) { "a packet holds 1 to $MAX_PACKET_SIZE chapathis" }
    }

    val lineTotalCents: Long get() = lineTotal(qtyPackets, unitPriceCents)

    /** Chapathis sold on this line. */
    val chapathis: Int get() = qtyPackets * chapathisPerPacket

    /** True only when the charged price differs from the list price (change set C3). */
    val priceOverridden: Boolean get() = unitPriceCents != listPriceCents
}

data class InvoiceRecord(
    val id: String,
    val number: String,
    /** Null for a walk-in sale: there is no ledger (Doc 1 s4.1). */
    val customerId: String?,
    val customerName: String,
    val customerTypeName: String,
    val deviceCode: String,
    val issuedAt: LocalDateTime,
    val lines: List<InvoiceLine>,
    val totalCents: Long,
    val paidNowCents: Long,
    val method: PaymentMethod?,
    /** The customer's balance after this invoice and the payment made with it (0 for walk-in). */
    val balanceAfterCents: Long,
    val status: InvoiceStatus = InvoiceStatus.ACTIVE,
    val voidReason: String? = null,
    /** The salesman's name when the bill was made; the receipt prints it, never the device code (change set D1). */
    val salesmanName: String = "",
) {
    init {
        require(totalCents == invoiceTotal(lines)) { "invoice total must equal the sum of its lines (I-2)" }
        require(paidNowCents >= 0) { "paidNowCents must not be negative" }
        require((paidNowCents > 0) == (method != null)) { "a payment needs a method and no payment has none" }
    }

    val isVoid: Boolean get() = status == InvoiceStatus.VOID
}

/** A payment taken with no new invoice (Doc 1 s5.4, A-16). Saved customers only. */
data class PaymentRecord(
    val id: String,
    val receiptNumber: String,
    val customerId: String,
    val customerName: String,
    val deviceCode: String,
    val paidAt: LocalDateTime,
    val amountCents: Long,
    val method: PaymentMethod,
    val note: String,
    val salesmanName: String = "",
) {
    init {
        require(amountCents > 0) { "a payment must be more than zero" }
    }
}
