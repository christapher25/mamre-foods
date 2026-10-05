package com.mamre.billing.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import java.time.LocalDate

/**
 * The one database of version 1 (Doc 2 s5.1), file mamre.db. The schema is exported to android/app/schemas.
 *
 * Until the first signed release (gate L6) version 1 may be edited in place: re-export the schema and clear the
 * app data of the test devices. From the first signed release version 1 is FROZEN and every change is a written,
 * tested migration added to [MIGRATIONS] (Doc 2 I-15). There is no destructive migration, ever.
 */
@Database(
    entities = [
        CustomerTypeEntity::class,
        ProductEntity::class,
        CustomerEntity::class,
        PriceDefaultEntity::class,
        PriceOverrideEntity::class,
        SettingEntity::class,
        InvoiceEntity::class,
        InvoiceItemEntity::class,
        PaymentEntity::class,
        ReturnEntity::class,
        MaterialEntity::class,
        MaterialPurchaseEntity::class,
        OpeningStockEntity::class,
        RecipeItemEntity::class,
        ProductionDamageEntity::class,
        ExpenseCategoryEntity::class,
        ExpenseEntity::class,
        ChangeLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(LocalDateConverters::class)
abstract class MamreDatabase : RoomDatabase() {
    abstract fun customerTypeDao(): CustomerTypeDao
    abstract fun productDao(): ProductDao
    abstract fun customerDao(): CustomerDao
    abstract fun priceDefaultDao(): PriceDefaultDao
    abstract fun priceOverrideDao(): PriceOverrideDao
    abstract fun settingDao(): SettingDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun invoiceItemDao(): InvoiceItemDao
    abstract fun paymentDao(): PaymentDao
    abstract fun returnDao(): ReturnDao
    abstract fun materialDao(): MaterialDao
    abstract fun materialPurchaseDao(): MaterialPurchaseDao
    abstract fun openingStockDao(): OpeningStockDao
    abstract fun recipeDao(): RecipeDao
    abstract fun productionDamageDao(): ProductionDamageDao
    abstract fun expenseCategoryDao(): ExpenseCategoryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun changeLogDao(): ChangeLogDao

    companion object {
        const val FILE_NAME = "mamre.db"

        /** Every written migration, oldest first. Empty while version 1 is the only schema. */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}

/** LocalDate is stored as an ISO-8601 text such as 2026-10-10. */
class LocalDateConverters {
    @TypeConverter fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter fun toLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

/** Runs several DAO calls as one unit: one use case, one transaction (Doc 2 s5.1). Fakes just run the block. */
interface UnitOfWork {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomUnitOfWork(private val db: MamreDatabase) : UnitOfWork {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}

/** The tables whose rows are never edited or deleted (Doc 2 I-9, I-14). DaoScanTest enforces it on the DAO sources. */
object ProtectedTables {
    val names: List<String> = listOf(
        "invoices", "invoice_items", "payments", "return_records",
        "material_purchases", "expenses", "production_damage", "change_log", "opening_stock",
    )
}
