package com.mamre.billing.data.api

import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.domain.model.PaymentMode
import java.time.LocalDate

/**
 * Stand-in for the server while none is hosted, chosen by BuildConfig.USE_FAKE_API.
 *
 * TEST DATA ONLY. Doc 1 P-4 (selling prices) is pending: the prices come from the shared demo
 * table and are invented placeholders so the app can be exercised, never real prices. The fake accepts only the
 * accounts in [FakeCredentials] and answers 401 invalid_credentials for anything else. It
 * issues tokens that look like the real ones in behaviour (an access token and a refresh
 * token) so the 401 then refresh path can run. The role is carried inside the token and
 * comes back from /me.
 */
class FakeApi(
    private val prices: SharedPriceTable = SharedPriceTable.seeded(LocalDate.now()),
) : BackendApi {
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

    /**
     * The whole worker catalog (products, types with their price-edit flag, customers, default and override
     * prices) comes from the table shared with the Admin's server stand-in, so anything the Admin adds or
     * changes reaches the worker at the next pull from an older cursor. The cursor is the highest sync
     * version: nothing newer means an empty answer.
     */
    override suspend fun catalog(accessToken: String, cursor: Long): CatalogPull {
        requireAccess(accessToken)
        val version = prices.version
        if (cursor >= version) return CatalogPull(cursor = cursor)
        return CatalogPull(
            cursor = version,
            products = prices.productsAfter(cursor).map {
                ProductDto(it.id, it.code, it.name, it.standardPacketSize, it.isActive)
            },
            customerTypes = prices.typesAfter(cursor).map {
                CustomerTypeDto(it.id, it.name, it.isActive, it.workerCanEditPrice)
            },
            customers = prices.customersAfter(cursor).map {
                CustomerDto(
                    it.id, it.name, it.typeId, it.phone, it.address,
                    if (it.paymentMode == PaymentMode.CREDIT) "credit" else "cash", it.isActive, it.location,
                )
            },
            priceDefaults = prices.entriesAfter(cursor).map {
                PriceDefaultDto(
                    id = it.id,
                    productId = it.productId,
                    customerTypeId = it.customerTypeId,
                    unitPriceCents = it.unitPriceCents,
                    effectiveFrom = it.effectiveFrom.toString(),
                )
            },
            priceOverrides = prices.overridesAfter(cursor).map {
                PriceOverrideDto(it.id, it.customerId, it.productId, it.unitPriceCents, it.effectiveFrom.toString(), it.isActive)
            },
        )
    }

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
    }
}
