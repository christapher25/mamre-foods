package com.mamre.billing.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// DAOs of the version 1 database (Doc 2 s4.1, s5.1, I-4, I-9, I-14).
//
// The DAOs of the protected tables (see ProtectedTables) have INSERT and reads only. The one change a protected row
// ever gets is a bill going active to void (InvoiceDao.markVoid, which refuses a bill that is already void); a mistake
// in a purchase or an expense is a new linked reversing row. DaoScanTest reads this file and fails on an @Update,
// @Delete or @Upsert, an insert that replaces, a raw query, or an UPDATE, DELETE or REPLACE statement on a protected
// table, apart from markVoid.

@Dao
interface CustomerTypeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<CustomerTypeEntity>)

    @Query("SELECT * FROM customer_types ORDER BY name") suspend fun getAll(): List<CustomerTypeEntity>

    @Query("SELECT * FROM customer_types WHERE id = :id") suspend fun get(id: String): CustomerTypeEntity?

    @Query("UPDATE customer_types SET salesman_can_edit_price = :allowed WHERE id = :id")
    suspend fun setSalesmanCanEditPrice(id: String, allowed: Boolean): Int

    @Query("SELECT COUNT(*) FROM customer_types") suspend fun count(): Int
}

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<ProductEntity>)

    @Query("SELECT * FROM products ORDER BY name") suspend fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id") suspend fun get(id: String): ProductEntity?

    @Query("UPDATE products SET standard_packet_size = :size WHERE id = :id")
    suspend fun setStandardPacketSize(id: String, size: Int): Int

    @Query("UPDATE products SET yield_per_kg = :yieldPerKg WHERE id = :id")
    suspend fun setYieldPerKg(id: String, yieldPerKg: Int): Int

    @Query("SELECT COUNT(*) FROM products") suspend fun count(): Int
}

@Dao
interface CustomerDao {
    @Insert suspend fun insert(row: CustomerEntity)

    @Update suspend fun update(row: CustomerEntity): Int

    @Query("SELECT * FROM customers ORDER BY name_key, location_key") suspend fun getAll(): List<CustomerEntity>

    @Query("SELECT * FROM customers ORDER BY name_key, location_key") fun observeAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id") suspend fun get(id: String): CustomerEntity?

    @Query("SELECT COUNT(*) FROM customers") suspend fun count(): Int
}

@Dao
interface PriceDefaultDao {
    /** A price change is a new row; history is kept (Doc 1 s4.3). */
    @Insert suspend fun insert(row: PriceDefaultEntity)

    @Query("SELECT * FROM price_defaults ORDER BY effective_from") suspend fun getAll(): List<PriceDefaultEntity>

    @Query("SELECT * FROM price_defaults ORDER BY effective_from") fun observeAll(): Flow<List<PriceDefaultEntity>>

    @Query("SELECT COUNT(*) FROM price_defaults") suspend fun count(): Int
}

@Dao
interface PriceOverrideDao {
    @Insert suspend fun insert(row: PriceOverrideEntity)

    @Query("UPDATE price_overrides SET is_active = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean): Int

    @Query("SELECT * FROM price_overrides ORDER BY effective_from") suspend fun getAll(): List<PriceOverrideEntity>

    @Query("SELECT * FROM price_overrides ORDER BY effective_from") fun observeAll(): Flow<List<PriceOverrideEntity>>

    @Query("SELECT * FROM price_overrides WHERE customer_id = :customerId ORDER BY effective_from")
    suspend fun forCustomer(customerId: String): List<PriceOverrideEntity>
}

@Dao
interface SettingDao {
    @Upsert suspend fun put(row: SettingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<SettingEntity>)

    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun get(key: String): String?

    @Query("SELECT * FROM settings") suspend fun getAll(): List<SettingEntity>

    @Query("SELECT * FROM settings") fun observeAll(): Flow<List<SettingEntity>>
}

@Dao
interface InvoiceDao {
    @Insert suspend fun insert(row: InvoiceEntity)

    /** The only change a bill ever gets: active to void. Returns 0 when the bill is missing or already void. */
    @Query("UPDATE invoices SET status = 'void', void_reason = :reason, voided_at = :at WHERE id = :id AND status = 'active'")
    suspend fun markVoid(id: String, reason: String, at: Long): Int

    @Query("SELECT * FROM invoices ORDER BY issued_at, number") suspend fun getAll(): List<InvoiceEntity>

    @Query("SELECT * FROM invoices ORDER BY issued_at, number") fun observeAll(): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE id = :id") suspend fun get(id: String): InvoiceEntity?

    @Query("SELECT * FROM invoices WHERE customer_id = :customerId ORDER BY issued_at, number")
    suspend fun forCustomer(customerId: String): List<InvoiceEntity>

    @Query("SELECT COUNT(*) FROM invoices") suspend fun count(): Int
}

@Dao
interface InvoiceItemDao {
    @Insert suspend fun insertAll(rows: List<InvoiceItemEntity>)

    @Query("SELECT * FROM invoice_items WHERE invoice_id = :invoiceId ORDER BY rowid")
    suspend fun forInvoice(invoiceId: String): List<InvoiceItemEntity>

    @Query("SELECT * FROM invoice_items ORDER BY rowid") suspend fun getAll(): List<InvoiceItemEntity>

    @Query("SELECT * FROM invoice_items ORDER BY rowid") fun observeAll(): Flow<List<InvoiceItemEntity>>
}

@Dao
interface PaymentDao {
    @Insert suspend fun insert(row: PaymentEntity)

    @Query("SELECT * FROM payments ORDER BY paid_at, receipt_number") suspend fun getAll(): List<PaymentEntity>

    @Query("SELECT * FROM payments ORDER BY paid_at, receipt_number") fun observeAll(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE id = :id") suspend fun get(id: String): PaymentEntity?

    @Query("SELECT * FROM payments WHERE customer_id = :customerId ORDER BY paid_at, receipt_number")
    suspend fun forCustomer(customerId: String): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE invoice_id = :invoiceId ORDER BY paid_at, receipt_number")
    suspend fun forInvoice(invoiceId: String): List<PaymentEntity>
}

@Dao
interface ReturnDao {
    @Insert suspend fun insert(row: ReturnEntity)

    @Query("SELECT * FROM return_records ORDER BY occurred_at, id") suspend fun getAll(): List<ReturnEntity>

    @Query("SELECT * FROM return_records ORDER BY occurred_at, id") fun observeAll(): Flow<List<ReturnEntity>>

    @Query("SELECT * FROM return_records WHERE id = :id") suspend fun get(id: String): ReturnEntity?
}

@Dao
interface MaterialDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<MaterialEntity>)

    @Query("SELECT * FROM materials ORDER BY rowid") suspend fun getAll(): List<MaterialEntity>

    @Query("SELECT COUNT(*) FROM materials") suspend fun count(): Int
}

@Dao
interface MaterialPurchaseDao {
    @Insert suspend fun insert(row: MaterialPurchaseEntity)

    @Query("SELECT * FROM material_purchases ORDER BY purchased_on, rowid") suspend fun getAll(): List<MaterialPurchaseEntity>

    @Query("SELECT * FROM material_purchases ORDER BY purchased_on, rowid")
    fun observeAll(): Flow<List<MaterialPurchaseEntity>>

    @Query("SELECT * FROM material_purchases WHERE id = :id") suspend fun get(id: String): MaterialPurchaseEntity?

    @Query("SELECT * FROM material_purchases WHERE reverses_id = :id") suspend fun reversalOf(id: String): MaterialPurchaseEntity?
}

@Dao
interface OpeningStockDao {
    @Insert suspend fun insert(row: OpeningStockEntity)

    @Query("SELECT * FROM opening_stock") suspend fun getAll(): List<OpeningStockEntity>
}

@Dao
interface RecipeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<RecipeItemEntity>)

    @Query("UPDATE recipe_items SET qty_milli_per_kg_wheat = :qty WHERE product_id = :productId AND material_id = :materialId")
    suspend fun setQuantity(productId: String, materialId: String, qty: Long?): Int

    @Query("SELECT * FROM recipe_items ORDER BY rowid") suspend fun getAll(): List<RecipeItemEntity>

    @Query("SELECT COUNT(*) FROM recipe_items") suspend fun count(): Int
}

@Dao
interface ProductionDamageDao {
    @Insert suspend fun insert(row: ProductionDamageEntity)

    @Query("SELECT * FROM production_damage ORDER BY damaged_on, rowid") suspend fun getAll(): List<ProductionDamageEntity>

    @Query("SELECT * FROM production_damage ORDER BY damaged_on, rowid") fun observeAll(): Flow<List<ProductionDamageEntity>>
}

@Dao
interface ExpenseCategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<ExpenseCategoryEntity>)

    @Query("SELECT * FROM expense_categories ORDER BY rowid") suspend fun getAll(): List<ExpenseCategoryEntity>

    @Query("SELECT COUNT(*) FROM expense_categories") suspend fun count(): Int
}

@Dao
interface ExpenseDao {
    @Insert suspend fun insert(row: ExpenseEntity)

    @Query("SELECT * FROM expenses ORDER BY expense_date, rowid") suspend fun getAll(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses ORDER BY expense_date, rowid") fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun get(id: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE reverses_id = :id") suspend fun reversalOf(id: String): ExpenseEntity?
}

@Dao
interface ChangeLogDao {
    @Insert suspend fun insert(row: ChangeLogEntity)

    @Query("SELECT * FROM change_log ORDER BY at, rowid") suspend fun getAll(): List<ChangeLogEntity>

    @Query("SELECT * FROM change_log ORDER BY at, rowid") fun observeAll(): Flow<List<ChangeLogEntity>>
}
