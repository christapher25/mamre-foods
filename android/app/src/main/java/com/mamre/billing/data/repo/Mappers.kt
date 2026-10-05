package com.mamre.billing.data.repo

import com.mamre.billing.data.local.CustomerEntity
import com.mamre.billing.data.local.ExpenseCategoryEntity
import com.mamre.billing.data.local.MaterialEntity
import com.mamre.billing.data.local.CustomerTypeEntity
import com.mamre.billing.data.local.PriceDefaultEntity
import com.mamre.billing.data.local.PriceOverrideEntity
import com.mamre.billing.data.local.ProductEntity
import com.mamre.billing.data.local.Stored
import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.ExpenseCategory
import com.mamre.billing.domain.admin.ExpenseKind
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.MaterialUnit
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.PriceDefault
import com.mamre.billing.domain.model.PriceOverride
import com.mamre.billing.domain.model.Product
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnResolution
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// Entities to the domain models (Doc 2 s4.2). Times are UTC epoch milliseconds in the database and local date-times in
// the models, in the phone's time zone (Doc 2 s4.1).

fun Long.toLocalDateTime(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)

fun CustomerEntity.toCustomer() = Customer(
    id = id,
    name = name,
    typeId = typeId,
    phone = phone,
    address = address,
    paymentMode = if (paymentMode == Stored.CREDIT) PaymentMode.CREDIT else PaymentMode.CASH,
    isActive = isActive,
    location = location,
    isCorporate = isCorporate,
)

fun CustomerTypeEntity.toCustomerType() = CustomerType(id, name, isActive, workerCanEditPrice = salesmanCanEditPrice)

fun ProductEntity.toProduct() = Product(id, code, name, unitsPerPacket = standardPacketSize, isActive = isActive)

fun PriceDefaultEntity.toPriceDefault() = PriceDefault(id, productId, customerTypeId, unitPriceCents, effectiveFrom)

fun PriceOverrideEntity.toPriceOverride() = PriceOverride(id, customerId, productId, unitPriceCents, effectiveFrom, isActive)

fun PaymentMethod.stored(): String = name.lowercase()

fun paymentMethodOf(stored: String): PaymentMethod = PaymentMethod.valueOf(stored.uppercase())

fun ReturnReason.stored(): String = name.lowercase()

fun returnReasonOf(stored: String): ReturnReason = ReturnReason.valueOf(stored.uppercase())

fun ReturnResolution.stored(): String = name.lowercase()

fun returnResolutionOf(stored: String): ReturnResolution = ReturnResolution.valueOf(stored.uppercase())

/** The device code inside a number such as MAM-W1-0042 or RCP-W1-0007. */
fun deviceCodeOf(number: String): String = number.split("-").getOrElse(1) { "" }

fun CustomerEntity.toAdmin(typeName: String) = AdminCustomer(
    id = id,
    name = name,
    typeId = typeId,
    typeName = typeName,
    phone = phone,
    address = address,
    paymentMode = if (paymentMode == Stored.CREDIT) PaymentMode.CREDIT else PaymentMode.CASH,
    notes = notes,
    isActive = isActive,
    openingBalanceCents = openingBalanceCents,
    location = location,
    isCorporate = isCorporate,
)

fun ExpenseCategoryEntity.toAdmin() = ExpenseCategory(id, name, if (kind == Stored.DIRECT) ExpenseKind.DIRECT else ExpenseKind.INDIRECT)

private const val MILLI = 1_000L

/** The units a material can be entered in: its base unit, and its purchase unit when that differs (Doc 2 s4.2). */
fun MaterialEntity.toAdmin(): Material {
    val base = MaterialUnit(baseUnit, MILLI)
    val units = if (purchaseUnit == baseUnit) listOf(base) else listOf(base, MaterialUnit(purchaseUnit, basePerPurchaseUnit * MILLI))
    return Material(id, name, baseUnit, purchaseUnit, isPacking, units)
}
