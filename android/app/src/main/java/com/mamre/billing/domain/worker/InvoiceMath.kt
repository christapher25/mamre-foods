package com.mamre.billing.domain.worker

/**
 * line_total = qty x unit_price (Doc 2 I-2). Quantity is a whole number of packets (Doc 1 A-1)
 * and the price is integer cents, so the product is exact and needs no rounding.
 */
fun lineTotal(qtyPackets: Int, unitPriceCents: Long): Long = qtyPackets.toLong() * unitPriceCents

/** invoice.total = sum of line totals (Doc 2 I-2). */
fun invoiceTotal(lines: List<InvoiceLine>): Long = lines.sumOf { it.lineTotalCents }

private val INVOICE_NUMBER_PATTERN = Regex("^MAM-[A-Z0-9]+-[0-9]{4,}$")

/**
 * MAM-<DEVICE>-<SEQ>, for example MAM-W1-0001 (Doc 1 s5.2, Doc 2 I-3). The sequence grows by
 * one on the device and is never reused; it has at least four digits.
 */
fun invoiceNumber(deviceCode: String, sequence: Int): String {
    require(deviceCode.isNotEmpty() && deviceCode.all { it in 'A'..'Z' || it in '0'..'9' }) {
        "device code must be A-Z and 0-9"
    }
    require(sequence >= 1) { "sequence starts at 1" }
    return "MAM-$deviceCode-${sequence.toString().padStart(4, '0')}"
}

fun isValidInvoiceNumber(number: String): Boolean = INVOICE_NUMBER_PATTERN.matches(number)

/** The sequence part of an invoice number for this device, or null for another device. */
fun sequenceOf(number: String, deviceCode: String): Int? {
    val prefix = "MAM-$deviceCode-"
    if (!isValidInvoiceNumber(number) || !number.startsWith(prefix)) return null
    return number.removePrefix(prefix).toIntOrNull()
}
