package com.mamre.billing.data.db

import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.PriceDefault
import com.mamre.billing.domain.model.PriceOverride
import com.mamre.billing.domain.model.Product

fun ProductEntity.toDomain() = Product(id, code, name, unitsPerPacket, isActive)

fun CustomerTypeEntity.toDomain() = CustomerType(id, name, isActive, workerCanEditPrice)

fun CustomerEntity.toDomain() = Customer(
    id = id,
    name = name,
    typeId = typeId,
    phone = phone,
    address = address,
    paymentMode = when (paymentMode) {
        "cash" -> PaymentMode.CASH
        "credit" -> PaymentMode.CREDIT
        else -> error("Unknown payment_mode: $paymentMode") // contract allows cash or credit only
    },
    isActive = isActive,
)

fun PriceDefaultEntity.toDomain() = PriceDefault(id, productId, customerTypeId, unitPriceCents, effectiveFrom)

fun PriceOverrideEntity.toDomain() =
    PriceOverride(id, customerId, productId, unitPriceCents, effectiveFrom, isActive)
