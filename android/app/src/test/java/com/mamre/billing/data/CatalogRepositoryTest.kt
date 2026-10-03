package com.mamre.billing.data

import com.mamre.billing.data.api.CatalogPull
import com.mamre.billing.data.api.CustomerDto
import com.mamre.billing.data.api.CustomerTypeDto
import com.mamre.billing.data.api.PriceDefaultDto
import com.mamre.billing.data.api.PriceOverrideDto
import com.mamre.billing.data.api.ProductDto
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.pricing.PriceResult
import com.mamre.billing.domain.pricing.PriceSource
import com.mamre.billing.domain.pricing.resolvePrice
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Doc 2 s6.4 (pull, cursor, is_active=false) with the DECISIONS on keeping inactive rows. */
class CatalogRepositoryTest {
    private val products = FakeProductDao()
    private val types = FakeCustomerTypeDao()
    private val customers = FakeCustomerDao()
    private val defaults = FakePriceDefaultDao()
    private val overrides = FakePriceOverrideDao()
    private val state = FakeSyncStateDao()
    private val requestedCursors = mutableListOf<Long>()
    private var nextPull: CatalogPull? = null
    private var failure: Throwable? = null

    private val remote = CatalogRemote { cursor ->
        requestedCursors += cursor
        failure?.let { throw it }
        nextPull!!
    }

    private val repo = CatalogRepository(
        products, types, customers, defaults, overrides, state, FakeSettingDao(), FakeTransactionRunner(), remote,
    )

    private fun product(id: String = "p1", active: Boolean = true) =
        ProductDto(id, "T-$id", "Product $id", 12, active)

    private fun type(id: String, name: String, active: Boolean = true) = CustomerTypeDto(id, name, active)

    private fun customer(id: String = "c1", active: Boolean = true, mode: String = "credit") =
        CustomerDto(id, "Customer $id", "t-rest", "555", "1 Main St", mode, active)

    private fun default(id: String, cents: Long, from: String, type: String = "t-rest") =
        PriceDefaultDto(id, "p1", type, cents, from)

    private fun override(id: String, cents: Long, active: Boolean = true) =
        PriceOverrideDto(id, "c1", "p1", cents, "2026-01-01", active)

    private fun pull(
        cursor: Long,
        products: List<ProductDto> = emptyList(),
        types: List<CustomerTypeDto> = emptyList(),
        customers: List<CustomerDto> = emptyList(),
        defaults: List<PriceDefaultDto> = emptyList(),
        overrides: List<PriceOverrideDto> = emptyList(),
    ) = CatalogPull(cursor, products, types, customers, defaults, overrides)

    private fun snapshot() = listOf(
        products.rows.toMap(), customers.rows.toMap(), defaults.rows.toMap(), overrides.rows.toMap(),
    )

    @Test fun startsAtCursorZero() = runTest {
        assertEquals(0L, repo.catalogCursor())
    }

    @Test fun storesRowsAndCursor() = runTest {
        repo.applyCatalog(
            pull(
                7,
                products = listOf(product()),
                types = listOf(type("t-rest", "Restaurant")),
                customers = listOf(customer()),
                defaults = listOf(default("d1", 250, "2026-10-01")),
                overrides = listOf(override("o1", 200)),
            ),
        )
        assertEquals(7L, repo.catalogCursor())
        assertEquals(listOf("p1"), repo.activeProducts().map { it.id })
        assertEquals(listOf("t-rest"), repo.activeCustomerTypes().map { it.id })
        assertEquals(PaymentMode.CREDIT, repo.activeCustomers().single().paymentMode)
        val book = repo.priceBook()
        assertEquals(250L, book.defaults.single().unitPriceCents)
        assertEquals(LocalDate.of(2026, 10, 1), book.defaults.single().effectiveFrom)
        assertEquals(200L, book.overrides.single().unitPriceCents)
    }

    @Test fun serverCursorLowerThanStoredIsStored() = runTest {
        repo.applyCatalog(pull(50))
        repo.applyCatalog(pull(12))
        assertEquals(12L, repo.catalogCursor())
    }

    @Test fun cursorIsStoredWhenNothingChanged() = runTest {
        repo.applyCatalog(pull(5))
        repo.applyCatalog(pull(5))
        assertEquals(5L, repo.catalogCursor())
    }

    @Test fun inactiveRowsAreKeptButHiddenFromPickers() = runTest {
        repo.applyCatalog(
            pull(
                1,
                products = listOf(product("p1"), product("p2", active = false)),
                types = listOf(type("t-rest", "Restaurant"), type("t-old", "Old", active = false)),
                customers = listOf(customer("c1"), customer("c2", active = false)),
            ),
        )
        assertEquals(listOf("p1"), repo.activeProducts().map { it.id })
        assertEquals(listOf("t-rest"), repo.activeCustomerTypes().map { it.id })
        assertEquals(listOf("c1"), repo.activeCustomers().map { it.id })
        // Rows stay in the local database (later invoices and reprints need the name).
        assertEquals(2, products.rows.size)
        assertEquals(2, types.rows.size)
        assertEquals(2, customers.rows.size)
    }

    @Test fun deactivatedCustomerIsStillFoundByIdForReprints() = runTest {
        repo.applyCatalog(pull(1, customers = listOf(customer("c2", active = false))))
        val c = repo.customer("c2")
        assertNotNull(c)
        assertEquals("Customer c2", c!!.name)
        assertFalse(c.isActive)
        assertNull(repo.customer("missing"))
    }

    @Test fun laterPullDeactivatesWithoutDeleting() = runTest {
        repo.applyCatalog(pull(1, customers = listOf(customer("c1"))))
        repo.applyCatalog(pull(2, customers = listOf(customer("c1", active = false))))
        assertTrue(repo.activeCustomers().isEmpty())
        assertEquals(1, customers.rows.size)
        assertFalse(customers.rows.getValue("c1").isActive)
    }

    @Test fun inactiveOverrideIsKeptAndIgnoredByResolvePrice() = runTest {
        repo.applyCatalog(
            pull(
                1,
                products = listOf(product()),
                types = listOf(type("t-rest", "Restaurant")),
                customers = listOf(customer()),
                defaults = listOf(default("d1", 250, "2026-01-01")),
                overrides = listOf(override("o1", 200, active = false)),
            ),
        )
        assertEquals(1, overrides.rows.size)
        val result = resolvePrice(
            repo.customer("c1"), repo.activeProducts().single(), LocalDate.of(2026, 10, 10), repo.priceBook(),
        )
        assertEquals(PriceResult.Found(250, PriceSource.TYPE_DEFAULT), result)
    }

    @Test fun priceDefaultsAreNeverRemoved() = runTest {
        repo.applyCatalog(pull(1, defaults = listOf(default("d1", 250, "2026-01-01"))))
        repo.applyCatalog(pull(2, defaults = listOf(default("d2", 275, "2026-10-01"))))
        repo.applyCatalog(pull(3))
        assertEquals(setOf("d1", "d2"), defaults.rows.keys)
    }

    @Test fun applyingTheSamePullTwiceChangesNothing() = runTest {
        val p = pull(
            4,
            products = listOf(product()),
            customers = listOf(customer()),
            defaults = listOf(default("d1", 250, "2026-01-01")),
            overrides = listOf(override("o1", 200)),
        )
        repo.applyCatalog(p)
        val before = snapshot()
        repo.applyCatalog(p)
        assertEquals(before, snapshot())
        assertEquals(4L, repo.catalogCursor())
    }

    @Test fun changedRowIsUpdatedInPlace() = runTest {
        repo.applyCatalog(pull(1, products = listOf(product())))
        repo.applyCatalog(pull(2, products = listOf(product().copy(name = "Renamed"))))
        assertEquals("Renamed", repo.activeProducts().single().name)
    }

    @Test fun badRowWritesNothingAndKeepsOldCursor() = runTest {
        repo.applyCatalog(pull(3, products = listOf(product())))
        try {
            repo.applyCatalog(
                pull(9, products = listOf(product("p9")), customers = listOf(customer(mode = "barter"))),
            )
            fail("expected an unknown payment_mode to be refused")
        } catch (expected: IllegalStateException) {
            // Mapping happens before any write.
        }
        assertEquals(3L, repo.catalogCursor())
        assertEquals(setOf("p1"), products.rows.keys)
    }

    @Test fun refreshSendsStoredCursorAndAppliesResult() = runTest {
        nextPull = pull(10, products = listOf(product()))
        repo.refresh()
        nextPull = pull(11)
        repo.refresh()
        assertEquals(listOf(0L, 10L), requestedCursors)
        assertEquals(11L, repo.catalogCursor())
    }

    @Test fun refreshFailureLeavesStateUntouched() = runTest {
        nextPull = pull(10, products = listOf(product()))
        repo.refresh()
        failure = IOException("offline")
        try {
            repo.refresh()
            fail("expected the network error to propagate")
        } catch (expected: IOException) {
            // Offline: the local catalog keeps working.
        }
        assertEquals(10L, repo.catalogCursor())
        assertEquals(1, products.rows.size)
    }
}
