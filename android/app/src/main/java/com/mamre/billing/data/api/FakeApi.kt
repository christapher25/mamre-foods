package com.mamre.billing.data.api

import com.mamre.billing.data.demo.DemoIds

/**
 * Stand-in for the server while none is hosted, chosen by BuildConfig.USE_FAKE_API.
 *
 * TEST DATA ONLY. Doc 1 P-4 (selling prices) is pending: the prices below are invented
 * placeholders so the app can be exercised, never real prices. The fake accepts only the
 * accounts in [FakeCredentials] and answers 401 invalid_credentials for anything else. It
 * issues tokens that look like the real ones in behaviour (an access token and a refresh
 * token) so the 401 then refresh path can run. The role is carried inside the token and
 * comes back from /me.
 */
class FakeApi : BackendApi {
    private var counter = 0

    override suspend fun login(username: String, password: String): TokenPair {
        val account = FakeCredentials.find(username, password) ?: throw invalidCredentials()
        counter++
        val role = account.role.wire
        return TokenPair("$ACCESS_PREFIX$role-$counter", "$REFRESH_PREFIX$role-$counter")
    }

    override suspend fun refresh(refreshToken: String): String {
        if (!refreshToken.startsWith(REFRESH_PREFIX)) {
            throw ApiException(401, "token_not_valid", "Token is invalid or expired.")
        }
        counter++
        val role = refreshToken.removePrefix(REFRESH_PREFIX).substringBefore('-')
        return "$ACCESS_PREFIX$role-$counter"
    }

    override suspend fun me(accessToken: String): Me {
        val account = accountFor(accessToken)
        return Me(
            id = "00000000-0000-4000-8000-0000000000a${account.role.ordinal + 1}",
            username = account.username,
            fullName = account.fullName,
            role = account.role.wire,
            deviceCode = account.deviceCode,
        )
    }

    override suspend fun catalog(accessToken: String, cursor: Long): CatalogPull {
        requireAccess(accessToken)
        if (cursor >= CATALOG_CURSOR) return CatalogPull(cursor = cursor)
        return CatalogPull(
            cursor = CATALOG_CURSOR,
            products = listOf(
                ProductDto(FRESH, "FRESH", "Mamre Fresh Chapathi", 12, true),
                ProductDto(CHAPATHI, "CHAPATHI", "Mamre Chapathi", 12, true),
            ),
            customerTypes = listOf(
                CustomerTypeDto(RESTAURANT, "Restaurant", true),
                CustomerTypeDto(SHOP, "Shop", true),
                CustomerTypeDto(RETAIL, "Retail", true),
            ),
            customers = listOf(
                CustomerDto(DemoIds.RESTAURANT, "Test Restaurant", RESTAURANT, "", "", "credit", true),
                CustomerDto(DemoIds.SHOP, "Test Shop", SHOP, "", "", "credit", true),
                CustomerDto(DemoIds.RETAIL_CUSTOMER, "Test Retail Customer", RETAIL, "", "", "cash", true),
            ),
            priceDefaults = listOf(
                priceDefault("d1", FRESH, RESTAURANT, 280),
                priceDefault("d2", CHAPATHI, RESTAURANT, 250),
                priceDefault("d3", FRESH, SHOP, 300),
                priceDefault("d4", CHAPATHI, SHOP, 270),
                priceDefault("d5", FRESH, RETAIL, 350),
                priceDefault("d6", CHAPATHI, RETAIL, 320),
            ),
        )
    }

    private fun priceDefault(n: String, product: String, type: String, cents: Long) = PriceDefaultDto(
        id = "00000000-0000-4000-8000-0000000000$n",
        productId = product,
        customerTypeId = type,
        unitPriceCents = cents,
        effectiveFrom = "2026-01-01",
    )

    private fun requireAccess(token: String) {
        accountFor(token)
    }

    private fun accountFor(token: String): FakeCredentials.Account {
        val role = if (token.startsWith(ACCESS_PREFIX)) {
            token.removePrefix(ACCESS_PREFIX).substringBefore('-')
        } else {
            null
        }
        return FakeCredentials.accounts.firstOrNull { it.role.wire == role }
            ?: throw ApiException(401, "not_authenticated", "Authentication credentials were not provided.")
    }

    private fun invalidCredentials() =
        ApiException(401, "invalid_credentials", "Wrong username or password.")

    private companion object {
        const val ACCESS_PREFIX = "fake-access-"
        const val REFRESH_PREFIX = "fake-refresh-"
        const val CATALOG_CURSOR = 2L
        const val FRESH = DemoIds.FRESH
        const val CHAPATHI = DemoIds.CHAPATHI
        const val RESTAURANT = DemoIds.RESTAURANT_TYPE
        const val SHOP = DemoIds.SHOP_TYPE
        const val RETAIL = DemoIds.RETAIL_TYPE
    }
}
