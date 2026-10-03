package com.mamre.billing.data

import com.mamre.billing.data.api.CatalogPull
import com.mamre.billing.data.api.CustomerDto
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.domain.model.label
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Change set D2: the customer location travels through the catalog DTO, the local table and the domain model. */
class CustomerLocationCatalogTest {
    private val customers = FakeCustomerDao()
    private var next = CatalogPull(cursor = 1)
    private val repo = CatalogRepository(
        FakeProductDao(), FakeCustomerTypeDao(), customers, FakePriceDefaultDao(), FakePriceOverrideDao(),
        FakeSyncStateDao(), FakeSettingDao(), FakeTransactionRunner(), CatalogRemote { next },
    )

    private fun dto(id: String, name: String, location: String) =
        CustomerDto(id, name, "t-shop", "", "", "credit", true, location)

    @Test fun theLocationIsStoredAndComesBackInTheLabel() = runTest {
        next = CatalogPull(cursor = 2, customers = listOf(dto("c1", "FreshMart", "Downtown"), dto("c2", "FreshMart", "Westside")))
        repo.refresh()
        assertEquals(listOf("FreshMart - Downtown", "FreshMart - Westside"), repo.activeCustomers().map { it.label }.sorted())
        assertEquals("Westside", repo.customer("c2")!!.location)
    }

    @Test fun aChangedLocationReplacesTheOldOneOnTheNextPull() = runTest {
        next = CatalogPull(cursor = 2, customers = listOf(dto("c1", "Taj Kitchen", "Frisco")))
        repo.refresh()
        next = CatalogPull(cursor = 3, customers = listOf(dto("c1", "Taj Kitchen", "Little Elm")))
        repo.refresh()
        assertEquals("Taj Kitchen - Little Elm", repo.customer("c1")!!.label)
    }

    @Test fun aServerThatDoesNotSendALocationYetGivesAnEmptyOne() {
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<CustomerDto>(
            """{"id":"c1","name":"Rao Family","type_id":"t","phone":"","address":"","payment_mode":"cash","is_active":true}""",
        )
        assertEquals("", parsed.location)
        assertEquals("Rao Family", com.mamre.billing.domain.model.customerLabel(parsed.name, parsed.location))
    }
}
