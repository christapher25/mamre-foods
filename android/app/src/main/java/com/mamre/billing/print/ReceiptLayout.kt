package com.mamre.billing.print

import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 32 characters for a 58 mm printer; verify on the real device (Doc 2 s7, P-1). */
const val RECEIPT_WIDTH = 32

private const val RULE_CHAR = '-'
private const val LABEL_WIDTH = 10
private const val ADDRESS_PENDING = "(address / phone pending)"
private const val THANK_YOU = "Thank you!"
private const val DUPLICATE_COPY = "DUPLICATE COPY"
private const val VOID_MARK = "*** VOID ***"
private val DATE_TIME = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
private val SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd")

// Receipt data. Plain values only: no cost, profit or expense appears here (Doc 2 I-8).

data class ReceiptItem(
    val productName: String,
    val qtyPackets: Int,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
    /** Chapathis per packet, set only for a custom packet; the receipt then reads "(N pcs)" (change set C2). */
    val customPacketSize: Int? = null,
)

data class MonthPayment(val date: LocalDate, val method: PaymentMethod, val amountCents: Long)

/** The credit customer's month so far (Doc 1 s5.3, s6.4). */
data class MonthSummary(
    val monthLabel: String,
    val broughtForwardCents: Long,
    val invoicedCents: Long,
    val creditsCents: Long,
    val payments: List<MonthPayment>,
    val totalDueCents: Long,
)

data class InvoiceReceipt(
    val businessName: String,
    /** Null until the business supplies it (Doc 1 P-6): the receipt then says so. */
    val addressLine: String?,
    val number: String,
    val issuedAt: LocalDateTime,
    val deviceCode: String,
    val customerName: String,
    val customerTypeName: String,
    val items: List<ReceiptItem>,
    val totalCents: Long,
    val paidNowCents: Long,
    val method: PaymentMethod?,
    /** Null for a walk-in: there is no ledger (Doc 1 s4.1). */
    val balanceAfterCents: Long?,
    /** Null for a customer without a month summary (walk-in, cash). */
    val month: MonthSummary?,
    val duplicate: Boolean = false,
    val isVoid: Boolean = false,
)

data class PaymentReceipt(
    val businessName: String,
    val addressLine: String?,
    val receiptNumber: String,
    val paidAt: LocalDateTime,
    val deviceCode: String,
    val customerName: String,
    val customerTypeName: String,
    val amountCents: Long,
    val method: PaymentMethod,
    val balanceAfterCents: Long,
    val note: String,
    val duplicate: Boolean = false,
)

/**
 * The invoice receipt as text lines, 32 columns wide (Doc 2 s7.1). A pure function: the same
 * input always gives the same lines, so a golden test can pin it. A reprint carries the line
 * DUPLICATE COPY (Doc 1 s5.4); a voided invoice is marked VOID and keeps its number.
 */
fun layoutInvoiceReceipt(r: InvoiceReceipt): List<String> = buildList {
    addHeader(r.businessName, r.addressLine, r.duplicate, r.isVoid)
    add("Invoice: ${r.number}")
    add("Date:    ${r.issuedAt.format(DATE_TIME)}")
    add("Worker:  ${r.deviceCode}")
    addAll(labelled("Customer: ", r.customerName))
    addAll(labelled("Type:     ", r.customerTypeName))
    add(rule())
    for (item in r.items) {
        addAll(wrap(if (item.customPacketSize != null) "${item.productName} (${item.customPacketSize} pcs)" else item.productName))
        add(leftRight("  ${item.qtyPackets} x ${formatCents(item.unitPriceCents)}", formatCents(item.lineTotalCents)))
    }
    add(rule())
    add(leftRight("TOTAL", formatCents(r.totalCents)))
    val paidLabel = r.method?.let { "Paid now (${it.label})" } ?: "Paid now"
    add(leftRight(paidLabel, formatCents(r.paidNowCents)))
    r.balanceAfterCents?.let { add(leftRight("Balance after", formatCents(it))) }
    r.month?.let { month ->
        add(rule())
        add("THIS MONTH (${month.monthLabel})")
        add(leftRight("Brought forward", formatCents(month.broughtForwardCents)))
        add(leftRight("Invoiced", formatCents(month.invoicedCents)))
        if (month.creditsCents > 0) add(leftRight("Credits", formatCents(-month.creditsCents)))
        if (month.payments.isNotEmpty()) {
            add("Payments:")
            for (p in month.payments) {
                add(leftRight(" ${p.date.format(SHORT_DATE)} ${p.method.label}", formatCents(p.amountCents)))
            }
        }
        add(leftRight("TOTAL DUE", formatCents(month.totalDueCents)))
    }
    add(rule())
    add(centered(THANK_YOU))
}

/** The receipt for a payment taken with no new invoice (Doc 1 s5.4, A-16). */
fun layoutPaymentReceipt(r: PaymentReceipt): List<String> = buildList {
    addHeader(r.businessName, r.addressLine, r.duplicate, isVoid = false)
    add("Receipt: ${r.receiptNumber}")
    add("Date:    ${r.paidAt.format(DATE_TIME)}")
    add("Worker:  ${r.deviceCode}")
    addAll(labelled("Customer: ", r.customerName))
    addAll(labelled("Type:     ", r.customerTypeName))
    add(rule())
    add(leftRight("Payment received (${r.method.label})", formatCents(r.amountCents)))
    add(leftRight("Balance after", formatCents(r.balanceAfterCents)))
    if (r.note.isNotBlank()) addAll(labelled("Note: ", r.note))
    add(rule())
    add(centered(THANK_YOU))
}

private fun MutableList<String>.addHeader(business: String, address: String?, duplicate: Boolean, isVoid: Boolean) {
    addAll(wrap(business).map(::centered))
    addAll(wrap(address ?: ADDRESS_PENDING).map(::centered))
    if (duplicate) add(centered(DUPLICATE_COPY))
    if (isVoid) add(centered(VOID_MARK))
    add(rule())
}

private fun rule() = RULE_CHAR.toString().repeat(RECEIPT_WIDTH)

/** Centres a line of at most 32 characters; an odd leftover space goes on the right. */
private fun centered(text: String): String = " ".repeat(((RECEIPT_WIDTH - text.length) / 2).coerceAtLeast(0)) + text

/** Left text and right text on one line, the right one flush to column 32. */
private fun leftRight(left: String, right: String): String {
    val room = RECEIPT_WIDTH - right.length - 1
    val l = if (left.length > room) left.take(room.coerceAtLeast(0)) else left
    return l + " ".repeat((RECEIPT_WIDTH - l.length - right.length).coerceAtLeast(1)) + right
}

/** A label of fixed width followed by a value that wraps under itself. */
private fun labelled(label: String, value: String): List<String> {
    val pad = " ".repeat(label.length.coerceAtMost(LABEL_WIDTH))
    val chunks = wrap(value, RECEIPT_WIDTH - label.length)
    return chunks.mapIndexed { i, c -> (if (i == 0) label else pad) + c }
}

/** Breaks text at spaces to the width; a word longer than the width is cut. */
private fun wrap(text: String, width: Int = RECEIPT_WIDTH): List<String> {
    val lines = mutableListOf<String>()
    var current = ""
    for (word in text.trim().split(" ").filter { it.isNotEmpty() }) {
        var w = word
        while (w.length > width) {
            if (current.isNotEmpty()) { lines += current; current = "" }
            lines += w.take(width)
            w = w.drop(width)
        }
        current = when {
            current.isEmpty() -> w
            current.length + 1 + w.length <= width -> "$current $w"
            else -> { lines += current; w }
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines.ifEmpty { listOf("") }
}
