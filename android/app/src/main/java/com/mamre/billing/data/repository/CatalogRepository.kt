package com.mamre.billing.data.repository

import com.mamre.billing.data.api.CatalogPull
import com.mamre.billing.data.db.CustomerDao
import com.mamre.billing.data.db.CustomerEntity
import com.mamre.billing.data.db.CustomerTypeDao
import com.mamre.billing.data.db.CustomerTypeEntity
import com.mamre.billing.data.db.PriceDefaultDao
import com.mamre.billing.data.db.PriceDefaultEntity
import com.mamre.billing.data.db.PriceOverrideDao
import com.mamre.billing.data.db.PriceOverrideEntity
import com.mamre.billing.data.db.ProductDao
import com.mamre.billing.data.db.ProductEntity
import com.mamre.billing.data.db.SyncStateDao
import com.mamre.billing.data.db.SyncStateEntity
import com.mamre.billing.data.db.TransactionRunner
import com.mamre.billing.data.db.toDomain
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.Product
import com.mamre.billing.domain.pricing.PriceBook
import java.time.LocalDate

/** Fetches one catalog page for a cursor (GET /sync/catalog, with token handling behind it). */
fun interface CatalogRemote {
    suspend fun pull(cursor: Long): CatalogPull
}

/**
 * Local catalog (Doc 2 s6.4). Applies a /sync/catalog response and keeps the cursor.
 *
 * Inactive rows (is_active=false) are KEPT, not deleted: later invoices and reprints still
 * need a deactivated customer's name (DECISIONS). Pickers use the active* reads.
 * PriceDefault has no is_active and is never removed. Applying is an upsert, so repeating
 * the same response is safe (Doc 2 I-11 spirit). The server's cursor is always stored, even
 * when it is lower than the stored one (server wins, Doc 2 s6.5).
 */
class CatalogRepository(
    private val products: ProductDao,
    private val customerTypes: CustomerTypeDao,
    private val customers: CustomerDao,
    private val priceDefaults: PriceDefaultDao,
    private val priceOverrides: PriceOverrideDao,
    private val syncState: SyncStateDao,
    private val transactions: TransactionRunner,
    private val remote: CatalogRemote,
) {
    suspend fun catalogCursor(): Long = syncState.get(CATALOG_CURSOR_KEY) ?: 0L

    /** Pull from the stored cursor and apply. Network errors propagate; nothing is written then. */
    suspend fun refresh() {
        applyCatalog(remote.pull(catalogCursor()))
    }

    suspend fun applyCatalog(pull: CatalogPull) {
        // Map first so a bad row fails before anything is written.
        val productRows = pull.products.map { ProductEntity(it.id, it.code, it.name, it.unitsPerPacket, it.isActive) }
        val typeRows = pull.customerTypes.map { CustomerTypeEntity(it.id, it.name, it.isActive) }
        val customerRows = pull.customers.map {
            check(it.paymentMode == "cash" || it.paymentMode == "credit") {
                "Unknown payment_mode: ${it.paymentMode}"
            }
            CustomerEntity(it.id, it.name, it.typeId, it.phone, it.address, it.paymentMode, it.isActive)
        }
        val defaultRows = pull.priceDefaults.map {
            PriceDefaultEntity(
                it.id, it.productId, it.customerTypeId, it.unitPriceCents, LocalDate.parse(it.effectiveFrom),
            )
        }
        val overrideRows = pull.priceOverrides.map {
            PriceOverrideEntity(
                it.id, it.customerId, it.productId, it.unitPriceCents,
                LocalDate.parse(it.effectiveFrom), it.isActive,
            )
        }
        transactions.run {
            products.upsertAll(productRows)
            customerTypes.upsertAll(typeRows)
            customers.upsertAll(customerRows)
            priceDefaults.upsertAll(defaultRows)
            priceOverrides.upsertAll(overrideRows)
            syncState.put(SyncStateEntity(CATALOG_CURSOR_KEY, pull.cursor))
        }
    }

    suspend fun activeProducts(): List<Product> = products.getActive().map { it.toDomain() }

    suspend fun activeCustomerTypes(): List<CustomerType> = customerTypes.getActive().map { it.toDomain() }

    suspend fun activeCustomers(): List<Customer> = customers.getActive().map { it.toDomain() }

    /** Finds a customer even when deactivated, for invoices and reprints. */
    suspend fun customer(id: String): Customer? = customers.getById(id)?.toDomain()

    /** Everything resolvePrice needs, including inactive rows it must ignore or still price. */
    suspend fun priceBook(): PriceBook = PriceBook(
        customerTypes = customerTypes.getAll().map { it.toDomain() },
        defaults = priceDefaults.getAll().map { it.toDomain() },
        overrides = priceOverrides.getAll().map { it.toDomain() },
    )

    private companion object {
        const val CATALOG_CURSOR_KEY = "catalog_cursor"
    }
}
