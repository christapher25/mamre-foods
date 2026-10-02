package com.mamre.billing.data.api

/**
 * Stand-in for the server while none is hosted, chosen by BuildConfig.USE_FAKE_API.
 *
 * TEST DATA ONLY. Doc 1 P-4 (selling prices) is pending: the prices below are invented
 * placeholders so the app can be exercised, never real prices. The fake accepts any
 * non-blank username and password. It issues tokens that look like the real ones in
 * behaviour (an access token and a refresh token) so the 401 then refresh path can run.
 */
class FakeApi : BackendApi {
    private var counter = 0

    override suspend fun login(username: String, password: String): TokenPair {
        if (username.isBlank() || password.isBlank()) throw invalidCredentials()
        counter++
        return TokenPair("$ACCESS_PREFIX$counter", "$REFRESH_PREFIX$counter")
    }

    override suspend fun refresh(refreshToken: String): String {
        if (!refreshToken.startsWith(REFRESH_PREFIX)) {
            throw ApiException(401, "token_not_valid", "Token is invalid or expired.")
        }
        counter++
        return "$ACCESS_PREFIX$counter"
    }

    override suspend fun me(accessToken: String): Me {
        requireAccess(accessToken)
        return Me(
            id = "00000000-0000-4000-8000-0000000000a1",
            username = "worker",
            fullName = "Test Worker",
            role = "worker",
            deviceCode = "W1",
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
                CustomerDto(
                    "00000000-0000-4000-8000-0000000000c1", "Test Restaurant", RESTAURANT,
                    "", "", "credit", true,
                ),
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
        if (!token.startsWith(ACCESS_PREFIX)) {
            throw ApiException(401, "not_authenticated", "Authentication credentials were not provided.")
        }
    }

    private fun invalidCredentials() =
        ApiException(401, "invalid_credentials", "Wrong username or password.")

    private companion object {
        const val ACCESS_PREFIX = "fake-access-"
        const val REFRESH_PREFIX = "fake-refresh-"
        const val CATALOG_CURSOR = 1L
        const val FRESH = "00000000-0000-4000-8000-0000000000f1"
        const val CHAPATHI = "00000000-0000-4000-8000-0000000000f2"
        const val RESTAURANT = "00000000-0000-4000-8000-0000000000e1"
        const val SHOP = "00000000-0000-4000-8000-0000000000e2"
        const val RETAIL = "00000000-0000-4000-8000-0000000000e3"
    }
}
