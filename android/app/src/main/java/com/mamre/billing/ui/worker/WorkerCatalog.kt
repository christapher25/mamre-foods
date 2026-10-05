package com.mamre.billing.ui.worker

import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.toCustomer
import com.mamre.billing.data.repo.toCustomerType
import com.mamre.billing.data.repo.toProduct
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.CustomerType
import com.mamre.billing.domain.model.inTypeOrder
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

/** Reads customers, products and prices from the database (Doc 2 s4.2): only active customers and products. */
class WorkerCatalog @Inject constructor(
    private val customers: CustomerRepository,
    private val prices: PriceRepository,
) {
    suspend fun load(): CatalogSnapshot {
        val book = prices.priceBook()
        return CatalogSnapshot(
            customers = customers.customers().filter { it.isActive }.map { it.toCustomer() },
            typeNames = book.customerTypes.associate { it.id to it.name },
            products = prices.products().filter { it.isActive }.map { it.toProduct() },
            book = book,
            types = orderedTypes(customers.types().filter { it.isActive }.map { it.toCustomerType() }),
        )
    }
}

/** Restaurant, Shop, Retail, Catering first (change set C1), any other type after them by name. */
fun orderedTypes(types: List<CustomerType>): List<CustomerType> = types.inTypeOrder { it.name }
