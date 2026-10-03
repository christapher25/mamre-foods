package com.mamre.billing.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction

@Database(
    entities = [
        ProductEntity::class,
        CustomerTypeEntity::class,
        CustomerEntity::class,
        PriceDefaultEntity::class,
        PriceOverrideEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun customerTypeDao(): CustomerTypeDao
    abstract fun customerDao(): CustomerDao
    abstract fun priceDefaultDao(): PriceDefaultDao
    abstract fun priceOverrideDao(): PriceOverrideDao
    abstract fun syncStateDao(): SyncStateDao
}

/** Runs several DAO calls as one unit. Fakes in tests just run the block. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTransactionRunner(private val db: AppDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
