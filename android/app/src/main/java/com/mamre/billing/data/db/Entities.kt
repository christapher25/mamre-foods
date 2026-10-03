package com.mamre.billing.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

// Room tables for the catalog (Doc 2 s4.2, s6.4). String UUID ids, money as Long cents
// (Doc 2 I-1), dates as LocalDate. No cost, profit or expense columns (Doc 2 I-8).
// Inactive rows are kept with is_active = 0; PriceDefault has no is_active.

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    @ColumnInfo(name = "units_per_packet") val unitsPerPacket: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

@Entity(tableName = "customer_types")
data class CustomerTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "worker_can_edit_price") val workerCanEditPrice: Boolean = false,
)

@Entity(tableName = "customers", indices = [Index("type_id")])
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "type_id") val typeId: String,
    val phone: String,
    val address: String,
    @ColumnInfo(name = "payment_mode") val paymentMode: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    val location: String = "",
    @ColumnInfo(name = "is_corporate") val isCorporate: Boolean = false,
)

@Entity(tableName = "price_defaults", indices = [Index("customer_type_id", "product_id")])
data class PriceDefaultEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "customer_type_id") val customerTypeId: String,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
)

@Entity(tableName = "price_overrides", indices = [Index("customer_id", "product_id")])
data class PriceOverrideEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "customer_id") val customerId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Long,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

/** Key/value store for sync state, such as the catalog cursor (Doc 2 s6.4). */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: Long,
)
