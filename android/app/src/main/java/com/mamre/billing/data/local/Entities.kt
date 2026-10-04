package com.mamre.billing.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

// The version 1 database (Doc 2 s4.2, s5.1). UUID string keys (s4.1). Money columns end in _cents (Long),
// material quantities are Long milli-units, times are UTC epoch milliseconds, calendar dates are ISO text.
// No floating point anywhere (I-1). Bills, bill items, payments, returns, purchases, expenses, production damage
// and the change log are protected: their DAOs have no update or delete (I-9, I-14; see ProtectedTables).
// There is no cost, profit or expense column on any sales table (I-8). Business details live in Settings, never in code.

@Entity(tableName = "customer_types")
data class CustomerTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "salesman_can_edit_price") val salesmanCanEditPrice: Boolean,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    @ColumnInfo(name = "standard_packet_size") val standardPacketSize: Int,
    @ColumnInfo(name = "yield_per_kg") val yieldPerKg: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

/**
 * Unique on the normalised name plus the normalised location (Doc 2 s4.2): the keys are case-folded, trimmed and
 * have inner spaces collapsed, so "FreshMart" at "Downtown" and "freshmart " at "downtown" are the same customer.
 * A missing location is its own empty key. A walk-in is not a row.
 */
@Entity(
    tableName = "customers",
    foreignKeys = [ForeignKey(CustomerTypeEntity::class, ["id"], ["type_id"])],
    indices = [Index("type_id"), Index(value = ["name_key", "location_key"], unique = true)],
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    val location: String,
    @ColumnInfo(name = "location_key") val locationKey: String,
    @ColumnInfo(name = "type_id") val typeId: String,
    val phone: String,
    val address: String,
    /** "cash" or "credit". */
    @ColumnInfo(name = "payment_mode") val paymentMode: String,
    @ColumnInfo(name = "is_corporate") val isCorporate: Boolean,
    @ColumnInfo(name = "opening_balance_cents") val openingBalanceCents: Long,
    val notes: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

/** History is kept: a price change is a new row with a later [effectiveFrom] (Doc 1 s4.3). */
@Entity(
    tableName = "price_defaults",
    foreignKeys = [
        ForeignKey(ProductEntity::class, ["id"], ["product_id"]),
        ForeignKey(CustomerTypeEntity::class, ["id"], ["customer_type_id"]),
    ],
    indices = [Index("product_id", "customer_type_id"), Index("customer_type_id")],
)
data class PriceDefaultEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "customer_type_id") val customerTypeId: String,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
)

@Entity(
    tableName = "price_overrides",
    foreignKeys = [
        ForeignKey(CustomerEntity::class, ["id"], ["customer_id"]),
        ForeignKey(ProductEntity::class, ["id"], ["product_id"]),
    ],
    indices = [Index("customer_id", "product_id"), Index("product_id")],
)
data class PriceOverrideEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "customer_id") val customerId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
    val note: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

/** Key and value (Doc 2 s4.2 Setting). The keys are in [SettingKeys]. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * A bill (Doc 2 "Billing Invoice"). Immutable except status active to void (I-4). The customer's name, location and
 * corporate flag are snapshots, so a reprint prints what the first bill printed (Doc 1 s5.3).
 */
@Entity(
    tableName = "invoices",
    foreignKeys = [ForeignKey(CustomerEntity::class, ["id"], ["customer_id"])],
    indices = [Index("number", unique = true), Index("customer_id"), Index("issued_at")],
)
data class InvoiceEntity(
    @PrimaryKey val id: String,
    val number: String,
    /** Null for a walk-in sale. */
    @ColumnInfo(name = "customer_id") val customerId: String?,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "customer_location") val customerLocation: String,
    @ColumnInfo(name = "is_corporate") val isCorporate: Boolean,
    @ColumnInfo(name = "salesman_name") val salesmanName: String,
    @ColumnInfo(name = "issued_at") val issuedAt: Long,
    @ColumnInfo(name = "total_cents") val totalCents: Long,
    /** "active" or "void". */
    val status: String,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
)

/** A price snapshot (I-7). line_total = qty_packets x unit_price (I-2). */
@Entity(
    tableName = "invoice_items",
    foreignKeys = [
        ForeignKey(InvoiceEntity::class, ["id"], ["invoice_id"]),
        ForeignKey(ProductEntity::class, ["id"], ["product_id"]),
    ],
    indices = [Index("invoice_id"), Index("product_id")],
)
data class InvoiceItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "invoice_id") val invoiceId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "product_name") val productName: String,
    @ColumnInfo(name = "qty_packets") val qtyPackets: Int,
    @ColumnInfo(name = "chapathis_per_packet") val chapathisPerPacket: Int,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long,
    @ColumnInfo(name = "list_price_cents") val listPriceCents: Long,
    @ColumnInfo(name = "price_overridden") val priceOverridden: Boolean,
    @ColumnInfo(name = "line_total_cents") val lineTotalCents: Long,
)

/** Append-only. A payment taken with a bill carries that bill's id. Walk-in payments have no customer. */
@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(CustomerEntity::class, ["id"], ["customer_id"]),
        ForeignKey(InvoiceEntity::class, ["id"], ["invoice_id"]),
    ],
    indices = [Index("receipt_number", unique = true), Index("customer_id"), Index("invoice_id"), Index("paid_at")],
)
data class PaymentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "receipt_number") val receiptNumber: String,
    @ColumnInfo(name = "customer_id") val customerId: String?,
    @ColumnInfo(name = "invoice_id") val invoiceId: String?,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    /** cash, zelle, check, card or other. */
    val method: String,
    @ColumnInfo(name = "paid_at") val paidAt: Long,
    val note: String,
)

/** A customer return. A credit reduces the balance, a replacement does not (Doc 1 s7.1). */
@Entity(
    tableName = "return_records",
    foreignKeys = [
        ForeignKey(CustomerEntity::class, ["id"], ["customer_id"]),
        ForeignKey(InvoiceEntity::class, ["id"], ["invoice_id"]),
        ForeignKey(ProductEntity::class, ["id"], ["product_id"]),
    ],
    indices = [Index("customer_id"), Index("invoice_id"), Index("product_id"), Index("occurred_at")],
)
data class ReturnEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "customer_id") val customerId: String,
    @ColumnInfo(name = "invoice_id") val invoiceId: String?,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "qty_packets") val qtyPackets: Int,
    @ColumnInfo(name = "chapathis_per_packet") val chapathisPerPacket: Int,
    val reason: String,
    /** "credit" or "replacement". */
    val resolution: String,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long,
    @ColumnInfo(name = "credit_cents") val creditCents: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val note: String,
)

/** One shared list of materials: wheat, oil, sugar, salt, baking powder, potassium sorbate, packing. */
@Entity(tableName = "materials")
data class MaterialEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** g, ml or piece. */
    @ColumnInfo(name = "base_unit") val baseUnit: String,
    /** The unit purchases and stock are shown in, such as kg, L or piece. */
    @ColumnInfo(name = "purchase_unit") val purchaseUnit: String,
    /** Base units in one purchase unit (1000 g in a kg). */
    @ColumnInfo(name = "base_per_purchase_unit") val basePerPurchaseUnit: Long,
    @ColumnInfo(name = "is_packing") val isPacking: Boolean,
)

/**
 * Add-only. A mistake is a linked reversing row with negative quantity and total and a reason. The unique index on
 * [reversesId] lets a row be reversed once; a reversal is never reversed (use case, Doc 1 s9.4).
 */
@Entity(
    tableName = "material_purchases",
    foreignKeys = [ForeignKey(MaterialEntity::class, ["id"], ["material_id"])],
    indices = [Index("material_id"), Index("purchased_on"), Index("reverses_id", unique = true)],
)
data class MaterialPurchaseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "material_id") val materialId: String,
    @ColumnInfo(name = "purchased_on") val purchasedOn: LocalDate,
    @ColumnInfo(name = "qty_milli") val qtyMilli: Long,
    @ColumnInfo(name = "total_paid_cents") val totalPaidCents: Long,
    val note: String,
    @ColumnInfo(name = "reverses_id") val reversesId: String?,
    /** Required when [reversesId] is set. */
    val reason: String,
)

/** Set once per material (Doc 2 s4.2): the unique index on the material. */
@Entity(
    tableName = "opening_stock",
    foreignKeys = [ForeignKey(MaterialEntity::class, ["id"], ["material_id"])],
    indices = [Index("material_id", unique = true)],
)
data class OpeningStockEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "material_id") val materialId: String,
    /** YYYY-MM. */
    val month: String,
    @ColumnInfo(name = "qty_milli") val qtyMilli: Long,
    @ColumnInfo(name = "value_cents") val valueCents: Long,
)

/** Per 1 kg of wheat; null while unset (potassium sorbate, Doc 1 P-2). Packing is one piece per packet. */
@Entity(
    tableName = "recipe_items",
    foreignKeys = [
        ForeignKey(ProductEntity::class, ["id"], ["product_id"]),
        ForeignKey(MaterialEntity::class, ["id"], ["material_id"]),
    ],
    indices = [Index("product_id", "material_id", unique = true), Index("material_id")],
)
data class RecipeItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "material_id") val materialId: String,
    @ColumnInfo(name = "qty_milli_per_kg_wheat") val qtyMilliPerKgWheat: Long?,
)

/** Add-only; counts in material usage (Doc 1 s7.2). */
@Entity(
    tableName = "production_damage",
    foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["product_id"])],
    indices = [Index("product_id"), Index("damaged_on")],
)
data class ProductionDamageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "damaged_on") val damagedOn: LocalDate,
    val chapathis: Int,
    val note: String,
)

@Entity(tableName = "expense_categories", indices = [Index("name", unique = true)])
data class ExpenseCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** "direct" or "indirect". */
    val kind: String,
)

/** Add-only, with reversing entries like [MaterialPurchaseEntity]. */
@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(ExpenseCategoryEntity::class, ["id"], ["category_id"])],
    indices = [Index("category_id"), Index("expense_date"), Index("reverses_id", unique = true)],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "expense_date") val expenseDate: LocalDate,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val description: String,
    @ColumnInfo(name = "reverses_id") val reversesId: String?,
    val reason: String,
)

/** Written for price changes, price-switch changes, voids, settings changes and customer edits (Doc 2 s4.2). */
@Entity(tableName = "change_log", indices = [Index("at")])
data class ChangeLogEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val what: String,
    @ColumnInfo(name = "before_text") val beforeText: String,
    @ColumnInfo(name = "after_text") val afterText: String,
)

/** Setting keys (Doc 2 s4.2). The PIN keys are written in step 2 and never exported. */
object SettingKeys {
    const val OWNER_NAME = "owner_name"
    const val DEVICE_CODE = "device_code"
    const val BUSINESS_NAME = "business_name"
    /** Address lines separated by a newline. */
    const val ADDRESS = "address"
    const val PHONE = "phone"
    const val FOOTER_TEXT = "footer_text"
    const val WASTAGE_BP = "wastage_bp"
    const val LOCK_MINUTES = "lock_minutes"
    const val PIN_HASH = "pin_hash"
    const val PIN_SALT = "pin_salt"
    const val NEXT_BILL_SEQ = "next_bill_seq"
    const val NEXT_RECEIPT_SEQ = "next_receipt_seq"
}

/** Stored text of the small closed sets. */
object Stored {
    const val ACTIVE = "active"
    const val VOID = "void"
    const val CASH = "cash"
    const val CREDIT = "credit"
    const val DIRECT = "direct"
    const val INDIRECT = "indirect"
}
