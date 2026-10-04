package com.mamre.billing.data.repo

import com.mamre.billing.data.local.ChangeLogEntity
import com.mamre.billing.data.local.CustomerEntity
import com.mamre.billing.data.local.CustomerTypeEntity
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.PriceDefaultEntity
import com.mamre.billing.data.local.PriceOverrideEntity
import com.mamre.billing.data.local.ProductEntity
import com.mamre.billing.domain.pricing.PriceBook
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Customers and customer types (Doc 2 s4.2). A customer is never deleted: it is made inactive (Doc 1 A-13). */
class CustomerRepository(private val db: MamreDatabase) {
    suspend fun types(): List<CustomerTypeEntity> = db.customerTypeDao().getAll()

    suspend fun type(id: String): CustomerTypeEntity? = db.customerTypeDao().get(id)

    suspend fun setSalesmanCanEditPrice(typeId: String, allowed: Boolean) = db.customerTypeDao().setSalesmanCanEditPrice(typeId, allowed)

    suspend fun customers(): List<CustomerEntity> = db.customerDao().getAll()

    fun observeCustomers(): Flow<List<CustomerEntity>> = db.customerDao().observeAll()

    suspend fun customer(id: String): CustomerEntity? = db.customerDao().get(id)

    suspend fun insert(row: CustomerEntity) = db.customerDao().insert(row)

    suspend fun update(row: CustomerEntity) = db.customerDao().update(row)
}

/** Default prices, override prices and the product list (Doc 2 s4.2). Prices are only added; history is kept. */
class PriceRepository(private val db: MamreDatabase) {
    suspend fun products(): List<ProductEntity> = db.productDao().getAll()

    suspend fun defaults(): List<PriceDefaultEntity> = db.priceDefaultDao().getAll()

    suspend fun overrides(): List<PriceOverrideEntity> = db.priceOverrideDao().getAll()

    suspend fun overridesOf(customerId: String): List<PriceOverrideEntity> = db.priceOverrideDao().forCustomer(customerId)

    suspend fun addDefault(row: PriceDefaultEntity) = db.priceDefaultDao().insert(row)

    suspend fun addOverride(row: PriceOverrideEntity) = db.priceOverrideDao().insert(row)

    /** The rows are kept and switched off: price history is never deleted (Doc 2 s4.2). */
    suspend fun deactivateOverrides(ids: List<String>) = ids.forEach { db.priceOverrideDao().setActive(it, false) }

    /** Everything resolvePrice needs, including inactive rows it must ignore. */
    suspend fun priceBook(): PriceBook = PriceBook(
        customerTypes = db.customerTypeDao().getAll().map { it.toCustomerType() },
        defaults = db.priceDefaultDao().getAll().map { it.toPriceDefault() },
        overrides = db.priceOverrideDao().getAll().map { it.toPriceOverride() },
    )

    fun observeDefaults(): Flow<List<PriceDefaultEntity>> = db.priceDefaultDao().observeAll()

    fun observeOverrides(): Flow<List<PriceOverrideEntity>> = db.priceOverrideDao().observeAll()
}

/** The change log (Doc 2 s4.2): who changed what, before and after. Append only. */
class ChangeLogRepository(private val db: MamreDatabase) {
    suspend fun add(at: Long, what: String, before: String, after: String) =
        db.changeLogDao().insert(ChangeLogEntity(UUID.randomUUID().toString(), at, what, before, after))

    suspend fun all(): List<ChangeLogEntity> = db.changeLogDao().getAll()

    fun observeAll(): Flow<List<ChangeLogEntity>> = db.changeLogDao().observeAll()
}
