package com.mamre.billing.domain.worker

/**
 * What the Sales screens read, built from the database records (Doc 2 s4.2, s10). Immutable: a new state replaces the
 * old one. [payments] are the payments taken with no new bill (a bill's own payment is part of its [InvoiceRecord]).
 * Balances are always computed from the records and never stored (Doc 1 s6.3, Doc 2 I-6).
 */
class SalesState(
    val invoices: List<InvoiceRecord> = emptyList(),
    val payments: List<PaymentRecord> = emptyList(),
    val returns: List<ReturnRecord> = emptyList(),
    private val ledgers: Map<String, List<LedgerEntry>> = emptyMap(),
    private val openingBalances: Map<String, Long> = emptyMap(),
) {
    /** Dated ledger entries of one customer: its bills, every payment it made, and its Credit returns (Doc 1 s6.3, s7.1). */
    fun ledgerOf(customerId: String): List<LedgerEntry> = ledgers[customerId].orEmpty()

    fun openingOf(customerId: String): Long = openingBalances[customerId] ?: 0L

    /** Opening balance + non-void bills - credits - payments. For use cases and the Admin area. */
    fun balanceOf(customerId: String): Long = ledgerBalance(openingOf(customerId), ledgerOf(customerId))

    /**
     * The balance a Sales-area screen may show: never for a corporate account (Doc 1 s4.1, Doc 2 I-16), which is
     * tracked but not shown there. The Sales screens use this and not [balanceOf].
     */
    fun balanceShownInSales(customerId: String, isCorporate: Boolean): Long? = if (isCorporate) null else balanceOf(customerId)
}
