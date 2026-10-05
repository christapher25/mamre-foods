package com.mamre.billing.data.repo

import com.mamre.billing.data.local.ExpenseCategoryEntity
import com.mamre.billing.data.local.ExpenseEntity
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.MaterialEntity
import com.mamre.billing.data.local.MaterialPurchaseEntity
import com.mamre.billing.data.local.OpeningStockEntity
import com.mamre.billing.data.local.ProductEntity
import com.mamre.billing.data.local.ProductionDamageEntity
import com.mamre.billing.data.local.RecipeItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Materials, recipes, purchases, production damage and opening stock (Doc 2 s4.2). Purchases and damage are add only
 * (I-14); a mistake in a purchase is a linked reversing row with a reason.
 */
class StockRepository(private val db: MamreDatabase) {
    suspend fun products(): List<ProductEntity> = db.productDao().getAll()

    suspend fun product(id: String): ProductEntity? = db.productDao().get(id)

    suspend fun setStandardPacketSize(productId: String, size: Int) = db.productDao().setStandardPacketSize(productId, size)

    suspend fun setYieldPerKg(productId: String, yieldPerKg: Int) = db.productDao().setYieldPerKg(productId, yieldPerKg)

    suspend fun materials(): List<MaterialEntity> = db.materialDao().getAll()

    suspend fun recipe(): List<RecipeItemEntity> = db.recipeDao().getAll()

    suspend fun setRecipeQuantity(productId: String, materialId: String, qtyMilli: Long?) =
        db.recipeDao().setQuantity(productId, materialId, qtyMilli)

    suspend fun purchases(): List<MaterialPurchaseEntity> = db.materialPurchaseDao().getAll()

    suspend fun purchase(id: String): MaterialPurchaseEntity? = db.materialPurchaseDao().get(id)

    suspend fun reversalOfPurchase(id: String): MaterialPurchaseEntity? = db.materialPurchaseDao().reversalOf(id)

    suspend fun addPurchase(row: MaterialPurchaseEntity) = db.materialPurchaseDao().insert(row)

    suspend fun damage(): List<ProductionDamageEntity> = db.productionDamageDao().getAll()

    suspend fun addDamage(row: ProductionDamageEntity) = db.productionDamageDao().insert(row)

    suspend fun openingStock(): List<OpeningStockEntity> = db.openingStockDao().getAll()

    suspend fun addOpeningStock(row: OpeningStockEntity) = db.openingStockDao().insert(row)

    fun observePurchases(): Flow<List<MaterialPurchaseEntity>> = db.materialPurchaseDao().observeAll()

    fun observeDamage(): Flow<List<ProductionDamageEntity>> = db.productionDamageDao().observeAll()
}

/** Expense categories and expenses (Doc 2 s4.2). Expenses are add only, with reversing rows (I-14). */
class ExpenseRepository(private val db: MamreDatabase) {
    suspend fun categories(): List<ExpenseCategoryEntity> = db.expenseCategoryDao().getAll()

    suspend fun expenses(): List<ExpenseEntity> = db.expenseDao().getAll()

    suspend fun expense(id: String): ExpenseEntity? = db.expenseDao().get(id)

    suspend fun reversalOf(id: String): ExpenseEntity? = db.expenseDao().reversalOf(id)

    suspend fun add(row: ExpenseEntity) = db.expenseDao().insert(row)

    fun observeExpenses(): Flow<List<ExpenseEntity>> = db.expenseDao().observeAll()
}
