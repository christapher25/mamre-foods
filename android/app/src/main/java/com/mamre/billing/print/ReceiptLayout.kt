package com.mamre.billing.print

import com.mamre.billing.domain.model.BusinessHeader
import com.mamre.billing.domain.money.centsToPlain
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 32 characters for a 58 mm printer; verify on the real device (Doc 2 s7, P-1). */
const val RECEIPT_WIDTH = 32

private const val RULE_CHAR = '-'
private const val LABEL_WIDTH = 10
private const val ADDRESS_PHONE_PENDING = "(address / phone pending)"
private const val ADDRESS_PENDING = "(address pending)"
private const val PHONE_PENDING = "(phone pending)"
private const val NAME_PENDING = "(business name pending)"
private const val DUPLICATE_COPY = "DUPLICATE COPY"
private const val VOID_MARK = "*** VOID ***"
private val DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd")

// The item table: Item Desc 13 + Qty 4 + Price 7 + Amt 8 = 32 columns (change set D3).
private const val COL_ITEM = 13
private const val COL_QTY = 4
private const val COL_PRICE = 7
private const val COL_AMT = 8

// Receipt data. Plain values only: no cost, profit or expense appears here (Doc 2 I-8). The customer type is not
// part of it at all, because the bill never prints it (change set D3).

data class ReceiptItem(
    val productName: String,
    val qtyPackets: Int,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
    /** Chapathis per packet, printed after the name as "12NOS" for a standard and a custom packet alike. */
    val chapathisPerPacket: Int,
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
    /** Name, address lines, phone and footer from the synced business settings (change set E4). */
    val header: BusinessHeader,
    val number: String,
    val issuedAt: LocalDateTime,
    /** The salesman's name, never the device code (change set D1). */
    val salesmanName: String,
    val customerName: String,
    /** Printed on the line under the name, indented 10 (change set D2). */
    val customerLocation: String = "",
    val items: List<ReceiptItem>,
    val totalCents: Long,
    val paidNowCents: Long,
    val method: PaymentMethod?,
    /** Null for a walk-in: there is no ledger (Doc 1 s4.1). */
    val balanceAfterCents: Long?,
    /** Null for a customer without a month summary (walk-in, cash). */
    val month: MonthSummary?,
    /**
     * A corporate account (change set D4): the bill prints TOTAL, CREDIT and a signature block and NEVER a balance,
     * whatever [balanceAfterCents] and [month] hold.
     */
    val isCorporate: Boolean = false,
    val duplicate: Boolean = false,
    val isVoid: Boolean = false,
)

data class PaymentReceipt(
    val header: BusinessHeader,
    val receiptNumber: String,
    val paidAt: LocalDateTime,
    val salesmanName: String,
    val customerName: String,
    val customerLocation: String = "",
    val amountCents: Long,
    val method: PaymentMethod,
    val balanceAfterCents: Long,
    val note: String,
    /** A corporate account never gets a balance on paper (change set D4). */
    val isCorporate: Boolean = false,
    val duplicate: Boolean = false,
)

/**
 * The bill as text lines, 32 columns wide (Doc 2 s7.1, change set D3). A pure function: the same input always gives
 * the same lines, so golden tests pin it. A reprint carries the line DUPLICATE COPY (Doc 1 s5.4); a voided invoice is
 * marked VOID and keeps its number.
 *
 * Order: Bill No, Date, Time, Salesman, Customer (name, then location indented 10), the item table, TOTAL (USD),
 * then the payment block: a PAID line for what was taken now, a CREDIT line for the unpaid part, and for a normal
 * customer Balance after and the THIS MONTH summary; for a corporate account a signature block instead.
 */
fun layoutInvoiceReceipt(r: InvoiceReceipt): List<String> = buildList {
    addHeader(r.header, r.duplicate, r.isVoid)
    addAll(labelled("Bill No:  ", r.number))
    add("Date:     ${r.issuedAt.format(DATE)}")
    add("Time:     ${r.issuedAt.format(TIME)}")
    addAll(labelled("Salesman: ", r.salesmanName))
    addAll(customerLines(r.customerName, r.customerLocation))
    add(rule())
    add(itemHeader())
    for (item in r.items) {
        addAll(wrap(itemTitle(item)))
        addAll(itemRow(item.qtyPackets, centsToPlain(item.unitPriceCents), centsToPlain(item.lineTotalCents)))
    }
    add(rule())
    add(leftRight("TOTAL (USD)", centsToPlain(r.totalCents)))
    if (r.paidNowCents > 0) {
        add(leftRight(r.method?.let { "PAID (${it.label})" } ?: "PAID", centsToPlain(r.paidNowCents)))
    }
    val unpaid = r.totalCents - r.paidNowCents
    if (unpaid > 0) add(leftRight("CREDIT", centsToPlain(unpaid)))
    if (r.isCorporate) {
        addSignatureBlock()
    } else {
        r.balanceAfterCents?.let { add(leftRight("Balance after", centsToPlain(it))) }
        r.month?.let { addMonthSummary(it) }
    }
    add(rule())
    addFooter(r.header.footer)
}

/** The receipt for a payment taken with no new invoice (Doc 1 s5.4, A-16). */
fun layoutPaymentReceipt(r: PaymentReceipt): List<String> = buildList {
    addHeader(r.header, r.duplicate, isVoid = false)
    addAll(labelled("Receipt:  ", r.receiptNumber))
    add("Date:     ${r.paidAt.format(DATE)}")
    add("Time:     ${r.paidAt.format(TIME)}")
    addAll(labelled("Salesman: ", r.salesmanName))
    addAll(customerLines(r.customerName, r.customerLocation))
    add(rule())
    add(leftRight("Payment received (${r.method.label})", formatCents(r.amountCents)))
    if (!r.isCorporate) add(leftRight("Balance after", formatCents(r.balanceAfterCents)))
    if (r.note.isNotBlank()) addAll(labelled("Note: ", r.note))
    add(rule())
    addFooter(r.header.footer)
}

private fun MutableList<String>.addHeader(header: BusinessHeader, duplicate: Boolean, isVoid: Boolean) {
    addAll(wrap(header.name.ifBlank { NAME_PENDING }).map(::centered))
    val hasAddress = header.addressLines.isNotEmpty()
    val hasPhone = header.phone.isNotBlank()
    // A missing value prints a placeholder, never a made-up one (change set E4).
    when {
        !hasAddress && !hasPhone -> add(centered(ADDRESS_PHONE_PENDING))
        !hasAddress -> add(centered(ADDRESS_PENDING))
        else -> for (line in header.addressLines) addAll(wrap(line).map(::centered))
    }
    if (hasPhone) addAll(wrap("Ph: ${header.phone}").map(::centered)) else if (hasAddress) add(centered(PHONE_PENDING))
    if (duplicate) add(centered(DUPLICATE_COPY))
    if (isVoid) add(centered(VOID_MARK))
    add(rule())
}

/** The footer text of the business settings, centred; nothing when none is set. */
private fun MutableList<String>.addFooter(footer: String) {
    if (footer.isNotBlank()) addAll(wrap(footer).map(::centered))
}

/** THIS MONTH for a credit customer (Doc 1 s5.3): plain amounts like the rest of the bill, no dollar sign (change set E2). */
private fun MutableList<String>.addMonthSummary(month: MonthSummary) {
    add(rule())
    add("THIS MONTH (${month.monthLabel})")
    add(leftRight("Brought forward", centsToPlain(month.broughtForwardCents)))
    add(leftRight("Invoiced", centsToPlain(month.invoicedCents)))
    if (month.creditsCents > 0) add(leftRight("Credits", centsToPlain(-month.creditsCents)))
    if (month.payments.isNotEmpty()) {
        add("Payments:")
        for (p in month.payments) {
            add(leftRight(" ${p.date.format(SHORT_DATE)} ${p.method.label}", centsToPlain(p.amountCents)))
        }
    }
    add(leftRight("TOTAL DUE", centsToPlain(month.totalDueCents)))
}

/** What a corporate customer signs: the bill itself, no balance. */
private fun MutableList<String>.addSignatureBlock() {
    add(rule())
    add("Received by:")
    add("")
    add("_".repeat(RECEIPT_WIDTH))
    add("Sign and stamp")
}

private fun rule() = RULE_CHAR.toString().repeat(RECEIPT_WIDTH)

private fun itemHeader(): String =
    "Item Desc".padEnd(COL_ITEM) + "Qty".padStart(COL_QTY) + "Price".padStart(COL_PRICE) + "Amt".padStart(COL_AMT)

/** MAMRE CHAPATHI 12NOS: the name in capitals and the chapathis per packet, standard or custom (change set D3). */
private fun itemTitle(item: ReceiptItem) = "${item.productName.trim().uppercase()} ${item.chapathisPerPacket}NOS"

/**
 * Qty, Price and Amt right-aligned under their headings. A value too wide for its column (a huge amount) squeezes
 * the three to single spaces, and if even that is wider than the paper each goes on its own line, so nothing is cut.
 */
private fun itemRow(qty: Int, price: String, amount: String): List<String> {
    val columns = " ".repeat(COL_ITEM) + qty.toString().padStart(COL_QTY) + price.padStart(COL_PRICE) + amount.padStart(COL_AMT)
    if (columns.length <= RECEIPT_WIDTH) return listOf(columns)
    val squeezed = listOf(qty.toString(), price, amount).joinToString(" ")
    if (squeezed.length <= RECEIPT_WIDTH) return listOf(squeezed.padStart(RECEIPT_WIDTH))
    return listOf(qty.toString(), price, amount).map { it.padStart(RECEIPT_WIDTH) }
}

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

/** "Customer: Name" with the location on the next line, indented under the name (change set D2, D3). */
private fun customerLines(name: String, location: String): List<String> {
    val head = labelled("Customer: ", name)
    if (location.isBlank()) return head
    val pad = " ".repeat(LABEL_WIDTH)
    return head + wrap(location, RECEIPT_WIDTH - LABEL_WIDTH).map { pad + it }
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
