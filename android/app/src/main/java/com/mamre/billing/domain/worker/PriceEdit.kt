package com.mamre.billing.domain.worker

import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.money.parseCents

// Worker price editing (change set C3, amends Doc 3 rule N4). A worker may change a line's price per packet only
// when the customer type's flag "Worker can edit price" is on (Retail and Catering by default). Money is Long cents.

/** The highest price a worker can type is this many times the list price: a typing slip, not a negotiation. */
const val MAX_PRICE_FACTOR = 10

/** The type whose flag a walk-in follows (Doc 1 s4.1: walk-in follows the Retail rules). */
const val WALK_IN_TYPE_NAME = "Retail"

enum class PriceEditProblem { NOT_ALLOWED, NOT_AN_AMOUNT, NOT_POSITIVE, TOO_HIGH }

sealed interface PriceEditResult {
    data class Ok(val priceCents: Long) : PriceEditResult

    data class Rejected(val problem: PriceEditProblem) : PriceEditResult
}

/**
 * THE rule for a charged price (change set C3, review finding 2): above zero and at most [MAX_PRICE_FACTOR] times the
 * list price of that packet. One function, called by [checkPriceEdit] (typed text), [buildPacketLines] and
 * DemoStore.confirmInvoice, so no layer can accept a price another layer would refuse. Null means the price is fine.
 */
fun priceLimitProblem(chargedCents: Long, listCents: Long): PriceEditProblem? = when {
    chargedCents <= 0 -> PriceEditProblem.NOT_POSITIVE
    chargedCents > listCents * MAX_PRICE_FACTOR -> PriceEditProblem.TOO_HIGH
    else -> null
}

/**
 * Checks a typed price per packet. [allowed] is the customer type's flag: when it is off nothing is accepted,
 * whatever is typed, so a screen that skips its own check still cannot change a price.
 */
fun checkPriceEdit(allowed: Boolean, text: String, listCents: Long): PriceEditResult {
    if (!allowed) return PriceEditResult.Rejected(PriceEditProblem.NOT_ALLOWED)
    val cents = parseCents(text) ?: return PriceEditResult.Rejected(PriceEditProblem.NOT_AN_AMOUNT)
    return priceLimitProblem(cents, listCents)?.let { PriceEditResult.Rejected(it) } ?: PriceEditResult.Ok(cents)
}

fun priceEditMessage(problem: PriceEditProblem, listCents: Long): String = when (problem) {
    PriceEditProblem.NOT_ALLOWED -> "The price of this customer type cannot be changed"
    PriceEditProblem.NOT_AN_AMOUNT -> "Enter a price like 2.50"
    PriceEditProblem.NOT_POSITIVE -> "The price must be above $0.00"
    PriceEditProblem.TOO_HIGH -> "The price cannot be more than ${formatCents(listCents * MAX_PRICE_FACTOR)}"
}

/** True when a worker may change prices for the chosen customer (null customer is a walk-in: the Retail flag). */
fun typeAllowsPriceEdit(types: List<CustomerType>, typeId: String?, walkIn: Boolean): Boolean {
    val type = if (walkIn) types.firstOrNull { it.name == WALK_IN_TYPE_NAME } else types.firstOrNull { it.id == typeId }
    return type?.workerCanEditPrice == true
}
