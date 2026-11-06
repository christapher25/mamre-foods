package com.mamre.billing.domain.setup

import com.mamre.billing.data.local.SettingKeys

/** A default price that exists for a product and a customer type (any effective date). */
data class PriceKey(val productId: String, val customerTypeId: String)

/**
 * What the Finish setup card lists (Doc 1 s2.1 FR-3). [businessDetails] is true while the owner name, address or phone is empty;
 * [missingPrices] counts the (active product, customer type) pairs with no default price. A missing price is a gap, never zero.
 */
data class SetupGaps(val businessDetails: Boolean, val missingPrices: Int) {
    val any: Boolean get() = businessDetails || missingPrices > 0
}

fun setupGaps(
    settings: Map<String, String>,
    activeProductIds: List<String>,
    customerTypeIds: List<String>,
    prices: List<PriceKey>,
): SetupGaps {
    fun empty(key: String) = settings[key].orEmpty().isBlank()
    val have = prices.toHashSet()
    val missing = activeProductIds.sumOf { p -> customerTypeIds.count { t -> PriceKey(p, t) !in have } }
    return SetupGaps(
        businessDetails = empty(SettingKeys.OWNER_NAME) || empty(SettingKeys.ADDRESS) || empty(SettingKeys.PHONE),
        missingPrices = missing,
    )
}
