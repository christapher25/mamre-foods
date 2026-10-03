package com.mamre.billing.domain.worker

import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.PaymentMode
import java.time.LocalDateTime

/** Search by any part of the name, ignoring case, listed by name (Doc 2 s10 Select customer). */
fun filterCustomers(customers: List<Customer>, query: String): List<Customer> {
    val q = query.trim().lowercase()
    return customers
        .filter { q.isEmpty() || it.name.lowercase().contains(q) }
        .sortedBy { it.name.lowercase() }
}

/** Walk-in is a null customer (Doc 1 s4.1); a saved customer is Cash or Credit. */
fun payerKind(customer: Customer?): PayerKind = when {
    customer == null -> PayerKind.WALK_IN
    customer.paymentMode == PaymentMode.CASH -> PayerKind.CASH_CUSTOMER
    else -> PayerKind.CREDIT_CUSTOMER
}

private val RECEIPT_NUMBER_PREFIX = "RCP"

/** RCP-<DEVICE>-<SEQ>. The format is a demo choice: Doc 2 has no receipt number format (QUESTIONS). */
fun receiptNumber(deviceCode: String, sequence: Int): String {
    require(deviceCode.isNotEmpty() && deviceCode.all { it in 'A'..'Z' || it in '0'..'9' }) {
        "device code must be A-Z and 0-9"
    }
    require(sequence >= 1) { "sequence starts at 1" }
    return "$RECEIPT_NUMBER_PREFIX-$deviceCode-${sequence.toString().padStart(4, '0')}"
}

fun receiptSequenceOf(number: String, deviceCode: String): Int? {
    val prefix = "$RECEIPT_NUMBER_PREFIX-$deviceCode-"
    if (!number.startsWith(prefix)) return null
    return number.removePrefix(prefix).takeIf { it.length >= 4 && it.all(Char::isDigit) }?.toIntOrNull()
}

/** Reasons from Doc 1 s7.1. */
enum class ReturnReason(val label: String) {
    DAMAGED("Damaged"),
    QUALITY_COMPLAINT("Quality complaint"),
    WRONG_ITEM("Wrong item"),
    EXPIRED("Expired"),
    OTHER("Other"),
}

/** Credit reduces the balance; Replacement hands over free packets and changes no balance (Doc 1 s7.1). */
enum class ReturnResolution(val label: String) {
    CREDIT("Credit"),
    REPLACEMENT("Replacement"),
}

/** credit = packets x unit price for a Credit; a Replacement credits nothing (Doc 1 s7.1, AT-9). */
fun returnCreditCents(resolution: ReturnResolution, qtyPackets: Int, unitPriceCents: Long): Long =
    if (resolution == ReturnResolution.CREDIT) qtyPackets.toLong() * unitPriceCents else 0L

/** A customer return (Doc 2 s4.2 ReturnRecord). Append-only. No cost field exists (Doc 2 I-8). */
data class ReturnRecord(
    val id: String,
    val customerId: String,
    val customerName: String,
    val productId: String,
    val productName: String,
    val qtyPackets: Int,
    val reason: ReturnReason,
    val resolution: ReturnResolution,
    val unitPriceCents: Long,
    val creditCents: Long,
    val deviceCode: String,
    val occurredAt: LocalDateTime,
) {
    init {
        require(qtyPackets > 0) { "a return needs at least one packet" }
        require(unitPriceCents > 0) { "a return needs a price (a missing price is never zero)" }
        require(creditCents == returnCreditCents(resolution, qtyPackets, unitPriceCents)) {
            "credit must match the resolution"
        }
    }
}

/** A product with the price it sells for to the chosen customer; null means no price is set. */
data class PricedProduct(
    val productId: String,
    val name: String,
    /** The price of a standard packet, or null when no price is set. */
    val unitPriceCents: Long?,
    /** Chapathis in this product's standard packet (6 by default, set by the Admin). */
    val standardPacketSize: Int = DEFAULT_PACKET_SIZE,
)

/**
 * The invoice lines for the packets entered (Doc 1 s5.1). A product with no price never becomes a
 * line, whatever quantity is typed: a missing price is not zero (Doc 1 s4.2, Doc 3 N5).
 */
fun buildInvoiceLines(products: List<PricedProduct>, quantities: Map<String, Int>): List<InvoiceLine> =
    products.mapNotNull { p ->
        val qty = quantities[p.productId] ?: 0
        val price = p.unitPriceCents
        if (qty > 0 && price != null) InvoiceLine(p.productId, p.name, qty, price) else null
    }

/** Continue is allowed only once there is at least one packet (Doc 2 s10 Invoice builder). */
fun canContinueInvoice(lines: List<InvoiceLine>): Boolean = lines.isNotEmpty()

/** Doc 1 s6.1: Other is accepted with a note. */
fun noteMissingForOther(method: PaymentMethod, note: String): Boolean =
    method == PaymentMethod.OTHER && note.isBlank()

/**
 * A return can be recorded once a product, packets, a reason and a resolution are chosen and the
 * product has a price: no price means no credit and no record, never a zero (Doc 1 s4.2, s7.1).
 */
fun canRecordReturn(
    hasProduct: Boolean,
    qtyPackets: Int,
    unitPriceCents: Long?,
    reason: ReturnReason?,
    resolution: ReturnResolution?,
): Boolean = hasProduct && qtyPackets > 0 && unitPriceCents != null && reason != null && resolution != null
