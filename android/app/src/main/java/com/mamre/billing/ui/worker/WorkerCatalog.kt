package com.mamre.billing.ui.worker

import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.Product
import com.mamre.billing.domain.pricing.PriceBook
import com.mamre.billing.domain.pricing.PriceResult
import com.mamre.billing.domain.pricing.resolvePrice
import java.time.LocalDate
import javax.inject.Inject

/** What the worker screens need from the local catalog, read once when a flow opens. */
data class CatalogSnapshot(
    val customers: List<Customer>,
    val typeNames: Map<String, String>,
    val products: List<Product>,
    val book: PriceBook,
    val types: List<CustomerType> = emptyList(),
) {
    fun typeName(customer: Customer?): String =
        if (customer == null) WALK_IN_TYPE_LABEL else typeNames[customer.typeId].orEmpty()

    /** The price the product sells for to this customer (null customer is a walk-in), never editable. */
    fun priceFor(customer: Customer?, product: Product, date: LocalDate): PriceResult =
        resolvePrice(customer, product, date, book)
}

const val WALK_IN_TYPE_LABEL = "Retail"
const val WALK_IN_NAME = "Walk-in"

/** Reads the catalog the sync keeps in Room (Doc 2 s6.4): only active customers and products. */
class WorkerCatalog @Inject constructor(private val catalog: CatalogRepository) {
    suspend fun load(): CatalogSnapshot {
        val book = catalog.priceBook()
        return CatalogSnapshot(
            customers = catalog.activeCustomers(),
            typeNames = book.customerTypes.associate { it.id to it.name },
            products = catalog.activeProducts(),
            book = book,
            types = orderedTypes(catalog.activeCustomerTypes()),
        )
    }
}

private val TILE_ORDER = listOf("Restaurant", "Shop", "Retail", "Catering")

/** Restaurant, Shop, Retail, Catering first (change set C1), any other type after them by name. */
fun orderedTypes(types: List<CustomerType>): List<CustomerType> =
    types.sortedWith(compareBy({ TILE_ORDER.indexOf(it.name).let { i -> if (i < 0) TILE_ORDER.size else i } }, { it.name.lowercase() }))
