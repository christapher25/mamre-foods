package com.mamre.billing.domain.setup

import com.mamre.billing.data.local.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doc 1 s2.1 FR-3: the Finish setup card lists Business details and Prices (with the count missing); nothing missing, no card. */
class SetupGapsTest {
    private val done = mapOf(
        SettingKeys.BUSINESS_NAME to "Mamre Foods",
        SettingKeys.ADDRESS to "1 Main Road",
        SettingKeys.PHONE to "9876543210",
    )
    private val types = listOf("restaurant", "shop")
    private val products = listOf("chapathi", "fresh")
    private val allPrices = products.flatMap { p -> types.map { t -> PriceKey(p, t) } }

    @Test fun aFreshInstallMissesBothThings() {
        val gaps = setupGaps(mapOf(SettingKeys.BUSINESS_NAME to "Mamre Foods"), products, types, emptyList())
        assertTrue(gaps.businessDetails)
        assertEquals(4, gaps.missingPrices)
        assertTrue(gaps.any)
    }

    @Test fun whenEverythingIsDoneThereIsNoCard() {
        val gaps = setupGaps(done, products, types, allPrices)
        assertFalse(gaps.businessDetails)
        assertEquals(0, gaps.missingPrices)
        assertFalse(gaps.any)
    }

    @Test fun onlyPricesMissingCountsEachProductAndTypePair() {
        val gaps = setupGaps(done, products, types, allPrices.drop(3))
        assertFalse(gaps.businessDetails)
        assertEquals(3, gaps.missingPrices)
        assertTrue(gaps.any)
    }

    @Test fun aProductAddedLaterBringsTheCardBack() {
        assertFalse(setupGaps(done, products, types, allPrices).any)
        val gaps = setupGaps(done, products + "rotti", types, allPrices)
        assertEquals(2, gaps.missingPrices)
        assertTrue(gaps.any)
    }

    @Test fun aSkippedSetupScreenLeavesTheAddressAndPhoneEmptyAndTheCardListsThem() {
        val skipped = done - SettingKeys.ADDRESS - SettingKeys.PHONE
        assertTrue(setupGaps(skipped, products, types, allPrices).businessDetails)
    }

    @Test fun anyOneOfAddressOrPhoneEmptyOrBlankMeansBusinessDetails() {
        for (key in listOf(SettingKeys.ADDRESS, SettingKeys.PHONE)) {
            assertTrue(key, setupGaps(done + (key to "  "), products, types, allPrices).businessDetails)
            assertTrue(key, setupGaps(done - key, products, types, allPrices).businessDetails)
        }
    }

    @Test fun aMissingOwnerNameIsNotAGapBecauseThereIsNoOwnerNameAnyMore() {
        // B8 L2: the Salesman line is the logged-in account name, so only the address and the phone make Business details.
        assertTrue(setupGaps(done, products, types, allPrices).businessDetails.not())
        assertTrue(setupGaps(done - "owner_name", products, types, allPrices).businessDetails.not())
    }

    @Test fun aMissingPriceIsNeverTreatedAsZero() {
        // A price of 0 cents is a price that exists; a missing row is a gap (CLAUDE.md: a missing price is never zero).
        val gaps = setupGaps(done, listOf("chapathi"), listOf("shop"), emptyList())
        assertEquals(1, gaps.missingPrices)
    }
}
