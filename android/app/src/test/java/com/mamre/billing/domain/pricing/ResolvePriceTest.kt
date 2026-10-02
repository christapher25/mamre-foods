package com.mamre.billing.domain.pricing

import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.PriceDefault
import com.mamre.billing.domain.model.PriceOverride
import com.mamre.billing.domain.model.Product
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Doc 1 s4.2 and s4.3; same rules as the backend (DECISIONS: price resolution, walk-in). */
class ResolvePriceTest {
    private val restaurant = CustomerType("t-rest", "Restaurant", true)
    private val retail = CustomerType("t-retail", "Retail", true)
    private val product = Product("p1", "TEST", "Test product", 12, true)
    private val customer = Customer("c1", "Spice Garden", "t-rest", "", "", PaymentMode.CREDIT, true)
    private val day = LocalDate.of(2026, 10, 10)

    private fun default(type: String, cents: Long, from: LocalDate) =
        PriceDefault("d-$type-$from", "p1", type, cents, from)

    private fun override(cents: Long, from: LocalDate, active: Boolean = true) =
        PriceOverride("o-$from", "c1", "p1", cents, from, active)

    private fun book(
        defaults: List<PriceDefault> = emptyList(),
        overrides: List<PriceOverride> = emptyList(),
        types: List<CustomerType> = listOf(restaurant, retail),
    ) = PriceBook(types, defaults, overrides)

    private fun found(cents: Long, source: PriceSource) = PriceResult.Found(cents, source)

    private val noPrice = PriceResult.NoPrice(NoPriceReason.NO_PRICE)

    @Test fun overrideBeatsDefault() {
        val b = book(listOf(default("t-rest", 250, day)), listOf(override(200, day)))
        assertEquals(found(200, PriceSource.OVERRIDE), resolvePrice(customer, product, day, b))
    }

    @Test fun defaultUsedWhenNoOverride() {
        val b = book(listOf(default("t-rest", 250, day)))
        assertEquals(found(250, PriceSource.TYPE_DEFAULT), resolvePrice(customer, product, day, b))
    }

    @Test fun inactiveOverrideIsIgnored() {
        val b = book(listOf(default("t-rest", 250, day)), listOf(override(200, day, active = false)))
        assertEquals(found(250, PriceSource.TYPE_DEFAULT), resolvePrice(customer, product, day, b))
    }

    @Test fun latestEffectiveFromNotAfterDateWins() {
        val b = book(
            listOf(
                default("t-rest", 240, LocalDate.of(2026, 1, 1)),
                default("t-rest", 250, LocalDate.of(2026, 9, 1)),
                default("t-rest", 999, LocalDate.of(2026, 11, 1)), // future: ignored
            ),
        )
        assertEquals(found(250, PriceSource.TYPE_DEFAULT), resolvePrice(customer, product, day, b))
    }

    @Test fun priceEffectiveOnTheSameDayCounts() {
        val b = book(listOf(default("t-rest", 250, day)))
        assertEquals(found(250, PriceSource.TYPE_DEFAULT), resolvePrice(customer, product, day, b))
    }

    @Test fun overrideHistoryLatestNotAfterDateWins() {
        val b = book(
            overrides = listOf(
                override(190, LocalDate.of(2026, 1, 1)),
                override(180, LocalDate.of(2026, 10, 1)),
                override(1, LocalDate.of(2026, 12, 1)),
            ),
        )
        assertEquals(found(180, PriceSource.OVERRIDE), resolvePrice(customer, product, day, b))
    }

    @Test fun futureOnlyOverrideFallsBackToDefault() {
        val b = book(listOf(default("t-rest", 250, day)), listOf(override(100, day.plusDays(1))))
        assertEquals(found(250, PriceSource.TYPE_DEFAULT), resolvePrice(customer, product, day, b))
    }

    @Test fun noPriceIsNeverZero() {
        assertEquals(noPrice, resolvePrice(customer, product, day, book()))
    }

    @Test fun onlyFutureDefaultMeansNoPrice() {
        val b = book(listOf(default("t-rest", 250, day.plusDays(1))))
        assertEquals(noPrice, resolvePrice(customer, product, day, b))
    }

    @Test fun otherTypeProductOrCustomerDoNotLeak() {
        val b = book(
            defaults = listOf(
                default("t-retail", 300, day),
                PriceDefault("x", "other-product", "t-rest", 111, day),
            ),
            overrides = listOf(PriceOverride("o", "other-customer", "p1", 5, day, true)),
        )
        assertEquals(noPrice, resolvePrice(customer, product, day, b))
    }

    @Test fun nonPositiveStoredPriceIsTreatedAsNoPrice() {
        val b = book(listOf(default("t-rest", 0, day)))
        assertEquals(noPrice, resolvePrice(customer, product, day, b))
    }

    @Test fun zeroOverrideFallsBackToDefault() {
        val b = book(listOf(default("t-rest", 250, day)), listOf(override(0, day)))
        assertEquals(found(250, PriceSource.TYPE_DEFAULT), resolvePrice(customer, product, day, b))
    }

    @Test fun walkInUsesRetailDefault() {
        val b = book(listOf(default("t-retail", 300, day), default("t-rest", 250, day)))
        assertEquals(found(300, PriceSource.TYPE_DEFAULT), resolvePrice(null, product, day, b))
    }

    @Test fun walkInSkipsOverrides() {
        val b = book(listOf(default("t-retail", 300, day)), listOf(override(100, day)))
        assertEquals(found(300, PriceSource.TYPE_DEFAULT), resolvePrice(null, product, day, b))
    }

    @Test fun walkInWithoutRetailTypeIsNoPrice() {
        val b = book(listOf(default("t-rest", 250, day)), types = listOf(restaurant))
        assertEquals(
            PriceResult.NoPrice(NoPriceReason.NO_RETAIL_TYPE),
            resolvePrice(null, product, day, b),
        )
    }

    @Test fun walkInWithoutRetailPriceIsNoPrice() {
        val b = book(listOf(default("t-rest", 250, day)))
        assertEquals(noPrice, resolvePrice(null, product, day, b))
    }

    @Test fun deactivatedCustomerTypeStillPrices() {
        // Inactive rows are kept (DECISIONS); pricing is by id, pickers hide inactive rows.
        val types = listOf(restaurant.copy(isActive = false), retail)
        val b = book(listOf(default("t-rest", 250, day)), types = types)
        assertEquals(found(250, PriceSource.TYPE_DEFAULT), resolvePrice(customer, product, day, b))
    }
}
