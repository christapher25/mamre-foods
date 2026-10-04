package com.mamre.billing.domain.worker

import java.time.LocalDate
import java.time.YearMonth

/** What changes a customer's balance (Doc 1 s6.3). */
sealed interface LedgerEntry {
    val date: LocalDate
}

data class InvoiceEntry(
    override val date: LocalDate,
    val totalCents: Long,
    val isVoid: Boolean = false,
) : LedgerEntry

data class PaymentEntry(
    override val date: LocalDate,
    val amountCents: Long,
    val method: PaymentMethod,
) : LedgerEntry

/** A return with resolution Credit. A replacement never reaches the ledger (Doc 1 s7.1). */
data class CreditEntry(
    override val date: LocalDate,
    val creditCents: Long,
) : LedgerEntry

/**
 * Balance = opening + non-void invoices - credits - payments (Doc 1 s6.3, Doc 2 I-6).
 * Always computed from records, never stored. Negative means credit on account (A-4).
 */
fun ledgerBalance(openingCents: Long, entries: List<LedgerEntry>): Long =
    openingCents + entries.sumOf {
        when (it) {
            is InvoiceEntry -> if (it.isVoid) 0L else it.totalCents
            is PaymentEntry -> -it.amountCents
            is CreditEntry -> -it.creditCents
        }
    }

/** The balance a month opens with: everything dated before its first day (Doc 1 s6.4). */
fun broughtForward(openingCents: Long, entries: List<LedgerEntry>, month: YearMonth): Long =
    ledgerBalance(openingCents, entries.filter { it.date.isBefore(month.atDay(1)) })
