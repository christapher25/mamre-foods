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
