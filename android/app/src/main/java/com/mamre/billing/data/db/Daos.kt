package com.mamre.billing.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

// DAO interfaces. Upserts make applying a catalog response idempotent (Doc 2 s6.4).
// There is no delete: inactive rows are kept (DECISIONS) and PriceDefault is never removed.

@Dao
interface ProductDao {
    @Upsert suspend fun upsertAll(rows: List<ProductEntity>)

    @Query("SELECT * FROM products ORDER BY name")
    suspend fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE is_active = 1 ORDER BY name")
    suspend fun getActive(): List<ProductEntity>
}

@Dao
interface CustomerTypeDao {
    @Upsert suspend fun upsertAll(rows: List<CustomerTypeEntity>)

    @Query("SELECT * FROM customer_types ORDER BY name")
    suspend fun getAll(): List<CustomerTypeEntity>

    @Query("SELECT * FROM customer_types WHERE is_active = 1 ORDER BY name")
    suspend fun getActive(): List<CustomerTypeEntity>
}

@Dao
interface CustomerDao {
    @Upsert suspend fun upsertAll(rows: List<CustomerEntity>)

    @Query("SELECT * FROM customers ORDER BY name")
    suspend fun getAll(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE is_active = 1 ORDER BY name")
    suspend fun getActive(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getById(id: String): CustomerEntity?
}

@Dao
interface PriceDefaultDao {
    @Upsert suspend fun upsertAll(rows: List<PriceDefaultEntity>)

    @Query("SELECT * FROM price_defaults")
    suspend fun getAll(): List<PriceDefaultEntity>
}

@Dao
interface PriceOverrideDao {
    @Upsert suspend fun upsertAll(rows: List<PriceOverrideEntity>)

    /** Includes inactive rows; resolvePrice ignores them. */
    @Query("SELECT * FROM price_overrides")
    suspend fun getAll(): List<PriceOverrideEntity>
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): Long?

    @Upsert suspend fun put(row: SyncStateEntity)
}
