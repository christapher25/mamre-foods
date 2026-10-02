package com.mamre.billing.domain.worker

import com.mamre.billing.domain.money.parseCents

/** Who is paying decides what the payment step allows (Doc 1 s4.1, s6.1, Doc 2 s10). */
enum class PayerKind { WALK_IN, CASH_CUSTOMER, CREDIT_CUSTOMER }

enum class PaymentProblem {
    NOT_AN_AMOUNT,
    NEGATIVE,

    /** Walk-in and cash customers pay in full at the time of sale. */
    MUST_PAY_IN_FULL,

    /** A payment with no invoice must be more than zero. */
    ZERO,
}

sealed interface PaymentCheck {
    data class Ok(val amountCents: Long, val balanceAfterCents: Long) : PaymentCheck {
        /** An overpayment becomes credit on account (Doc 1 A-4). */
        val creditOnAccountCents: Long get() = if (balanceAfterCents < 0) -balanceAfterCents else 0L
    }

    data class Rejected(val problem: PaymentProblem) : PaymentCheck
}

/**
 * The payment step of an invoice (Doc 1 s5.1 step 3, s6.1, A-4).
 *  - Text is parsed to cents without floating point; anything else is NOT_AN_AMOUNT.
 *  - No negative amounts.
 *  - A walk-in has no ledger, so it must pay exactly the total (nowhere for a surplus to go).
 *  - A cash customer must pay at least the total; a surplus becomes credit on account.
 *  - A credit customer may pay anything from $0, and an overpayment becomes credit on account.
 * Balance after = previous balance + total - amount (0 previous for a walk-in).
 */
fun checkInvoicePayment(
    kind: PayerKind,
    totalCents: Long,
    previousBalanceCents: Long,
    amountText: String,
): PaymentCheck {
    val amount = parseCents(amountText) ?: return PaymentCheck.Rejected(PaymentProblem.NOT_AN_AMOUNT)
    if (amount < 0) return PaymentCheck.Rejected(PaymentProblem.NEGATIVE)
    val previous = if (kind == PayerKind.WALK_IN) 0L else previousBalanceCents
    val payInFull = when (kind) {
        PayerKind.WALK_IN -> amount == totalCents
        PayerKind.CASH_CUSTOMER -> amount >= totalCents
        PayerKind.CREDIT_CUSTOMER -> true
    }
    if (!payInFull) return PaymentCheck.Rejected(PaymentProblem.MUST_PAY_IN_FULL)
    return PaymentCheck.Ok(amount, previous + totalCents - amount)
}

/** A payment with no new invoice (Doc 1 A-16): more than zero, not negative. */
fun checkStandalonePayment(previousBalanceCents: Long, amountText: String): PaymentCheck {
    val amount = parseCents(amountText) ?: return PaymentCheck.Rejected(PaymentProblem.NOT_AN_AMOUNT)
    if (amount < 0) return PaymentCheck.Rejected(PaymentProblem.NEGATIVE)
    if (amount == 0L) return PaymentCheck.Rejected(PaymentProblem.ZERO)
    return PaymentCheck.Ok(amount, previousBalanceCents - amount)
}
