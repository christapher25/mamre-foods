package com.mamre.billing.domain.pricing

import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.PriceDefault
import com.mamre.billing.domain.model.PriceOverride
import com.mamre.billing.domain.model.Product
import java.time.LocalDate

/** The local price data resolvePrice reads. Plain lists, so it needs no database. */
data class PriceBook(
    val customerTypes: List<CustomerType>,
    val defaults: List<PriceDefault>,
    val overrides: List<PriceOverride>,
)

enum class PriceSource { OVERRIDE, TYPE_DEFAULT }

enum class NoPriceReason { NO_PRICE, NO_RETAIL_TYPE }

sealed interface PriceResult {
    data class Found(val unitPriceCents: Long, val source: PriceSource) : PriceResult

    /** The product cannot be invoiced: "No price set - contact admin" (Doc 1 s4.2). Never zero. */
    data class NoPrice(val reason: NoPriceReason) : PriceResult
}

/** Walk-in sales use the default prices of the customer type with this name (DECISIONS). */
const val WALK_IN_CUSTOMER_TYPE_NAME = "Retail"

/**
 * Doc 1 s4.2 and s4.3, same rules as the backend:
 *  1. an active override for the customer and product,
 *  2. otherwise the default for the customer's type and product,
 *  3. otherwise [PriceResult.NoPrice].
 * Within each list the latest effective_from not after [date] wins. A walk-in
 * (customer null) skips overrides and uses the Retail type default. Inactive customer
 * types still price, because deactivated rows are kept locally.
 */
fun resolvePrice(
    customer: Customer?,
    product: Product,
    date: LocalDate,
    book: PriceBook,
): PriceResult {
    val typeId: String
    if (customer == null) {
        val retail = book.customerTypes.firstOrNull { it.name == WALK_IN_CUSTOMER_TYPE_NAME }
            ?: return PriceResult.NoPrice(NoPriceReason.NO_RETAIL_TYPE)
        typeId = retail.id
    } else {
        val override = book.overrides
            .filter {
                it.isActive && it.customerId == customer.id && it.productId == product.id &&
                    it.unitPriceCents > 0 && !it.effectiveFrom.isAfter(date)
            }
            .maxByOrNull { it.effectiveFrom }
        if (override != null) {
            return PriceResult.Found(override.unitPriceCents, PriceSource.OVERRIDE)
        }
        typeId = customer.typeId
    }
    val default = book.defaults
        .filter {
            it.customerTypeId == typeId && it.productId == product.id &&
                it.unitPriceCents > 0 && !it.effectiveFrom.isAfter(date)
        }
        .maxByOrNull { it.effectiveFrom }
        ?: return PriceResult.NoPrice(NoPriceReason.NO_PRICE)
    return PriceResult.Found(default.unitPriceCents, PriceSource.TYPE_DEFAULT)
}
