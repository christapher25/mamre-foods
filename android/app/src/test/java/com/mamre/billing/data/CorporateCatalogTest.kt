package com.mamre.billing.data

import com.mamre.billing.data.api.CatalogPull
import com.mamre.billing.data.api.CustomerDto
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set D4: the corporate flag travels through the catalog DTO, the local table and the domain model. */
class CorporateCatalogTest {
    private var next = CatalogPull(cursor = 1)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), FakeCustomerDao(), FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeTransactionRunner(), CatalogRemote { next },
    )

    private fun dto(id: String, corporate: Boolean) =
        CustomerDto(id, "FreshMart", "t-shop", "", "", "credit", true, "Downtown", corporate)

    @Test fun theFlagIsStoredAndReplacedOnTheNextPull() = runTest {
        next = CatalogPull(cursor = 2, customers = listOf(dto("c1", true), dto("c2", false)))
        repo.refresh()
        assertTrue(repo.customer("c1")!!.isCorporate)
        assertFalse(repo.customer("c2")!!.isCorporate)
        next = CatalogPull(cursor = 3, customers = listOf(dto("c1", false)))
        repo.refresh()
        assertFalse(repo.customer("c1")!!.isCorporate)
    }

    @Test fun aServerThatDoesNotSendTheFlagYetMeansNotCorporate() {
        val json = Json { ignoreUnknownKeys = true }
        val absent = json.decodeFromString<CustomerDto>(
            """{"id":"c1","name":"Rao Family","type_id":"t","phone":"","address":"","payment_mode":"cash","is_active":true}""",
        )
        assertFalse(absent.isCorporate)
        val present = json.decodeFromString<CustomerDto>(
            """{"id":"c1","name":"FreshMart","type_id":"t","phone":"","address":"","payment_mode":"credit","is_active":true,"location":"Downtown","is_corporate":true}""",
        )
        assertTrue(present.isCorporate)
        assertEquals("Downtown", present.location)
    }
}
