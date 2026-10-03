package com.mamre.billing.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire types from docs/api/openapi.yaml v0.2.0. Names are the exact snake_case keys.
// Money is integer cents (Doc 2 I-1); effective_from is an ISO date string.

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenPair(val access: String, val refresh: String)

@Serializable
data class RefreshRequest(val refresh: String)

@Serializable
data class AccessToken(val access: String)

@Serializable
data class Me(
    val id: String,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val role: String,
    @SerialName("device_code") val deviceCode: String? = null,
)

/** Error body from Doc 2 s5.2. */
@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CatalogPull(
    val cursor: Long,
    val products: List<ProductDto> = emptyList(),
    @SerialName("customer_types") val customerTypes: List<CustomerTypeDto> = emptyList(),
    val customers: List<CustomerDto> = emptyList(),
    @SerialName("price_defaults") val priceDefaults: List<PriceDefaultDto> = emptyList(),
    @SerialName("price_overrides") val priceOverrides: List<PriceOverrideDto> = emptyList(),
    val settings: List<SettingDto> = emptyList(),
)

@Serializable
data class ProductDto(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("units_per_packet") val unitsPerPacket: Int,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class CustomerTypeDto(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean,
    /** Owner decision C3: the worker may change a line's price for customers of this type. */
    @SerialName("worker_can_edit_price") val workerCanEditPrice: Boolean = false,
)

@Serializable
data class CustomerDto(
    val id: String,
    val name: String,
    @SerialName("type_id") val typeId: String,
    val phone: String,
    val address: String,
    @SerialName("payment_mode") val paymentMode: String,
    @SerialName("is_active") val isActive: Boolean,
    /** Owner decision D2: area or branch. Not in openapi.yaml v0.2.0 yet (QUESTIONS), so it may be missing. */
    val location: String = "",
)

@Serializable
data class PriceDefaultDto(
    val id: String,
    @SerialName("product_id") val productId: String,
    @SerialName("customer_type_id") val customerTypeId: String,
    @SerialName("unit_price_cents") val unitPriceCents: Long,
    @SerialName("effective_from") val effectiveFrom: String,
)

@Serializable
data class PriceOverrideDto(
    val id: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("unit_price_cents") val unitPriceCents: Long,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class SettingDto(val key: String, val value: String)
