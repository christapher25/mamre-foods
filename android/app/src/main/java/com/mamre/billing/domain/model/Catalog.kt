package com.mamre.billing.domain.model

import java.time.LocalDate

// Catalog models (Doc 2 s4.2). Money is Long cents (Doc 2 I-1). Worker-facing only:
// no cost, profit or expense fields exist here (Doc 2 I-8).

enum class PaymentMode { CASH, CREDIT }

data class Product(
    val id: String,
    val code: String,
    val name: String,
    val unitsPerPacket: Int,
    val isActive: Boolean,
)

data class CustomerType(
    val id: String,
    val name: String,
    val isActive: Boolean,
)

data class Customer(
    val id: String,
    val name: String,
    val typeId: String,
    val phone: String,
    val address: String,
    val paymentMode: PaymentMode,
    val isActive: Boolean,
)

/** Price history row. Has no is_active: it is never removed (DECISIONS: PriceDefault). */
data class PriceDefault(
    val id: String,
    val productId: String,
    val customerTypeId: String,
    val unitPriceCents: Long,
    val effectiveFrom: LocalDate,
)

data class PriceOverride(
    val id: String,
    val customerId: String,
    val productId: String,
    val unitPriceCents: Long,
    val effectiveFrom: LocalDate,
    val isActive: Boolean,
)
