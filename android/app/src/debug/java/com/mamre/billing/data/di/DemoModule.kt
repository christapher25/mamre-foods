package com.mamre.billing.data.di

import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.FakeAdminApi
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.DemoSeed
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.demo.SharedPriceTable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import javax.inject.Singleton

/**
 * DEBUG ONLY (src/debug): binds the demo server stand-ins into the slots that AppModule chooses from when the
 * USE_FAKE_API flag is on. A release build does not compile this file, so it cannot reach the fakes.
 */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    /** DEMO DATA: the one catalog linking the Admin stand-in and the worker FakeApi (DECISIONS 2026-10-03). */
    @Provides @Singleton
    fun sharedCatalog(): SharedPriceTable = SharedPriceTable.seeded(LocalDate.now())

    @Provides @Singleton @DemoImpl
    fun demoBackend(catalog: SharedPriceTable): BackendApi = FakeApi(catalog)

    @Provides @Singleton @DemoImpl
    fun demoAdmin(catalog: SharedPriceTable): AdminApi = FakeAdminApi(prices = catalog)

    @Provides @Singleton @DemoImpl
    fun demoStore(): DemoStore = DemoStore(seed = DemoSeed.state())
}
