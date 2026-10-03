package com.mamre.billing.data.di

import android.content.Context
import androidx.room.Room
import com.mamre.billing.BuildConfig
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.FakeAdminApi
import com.mamre.billing.data.api.AppVersionInterceptor
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.data.api.FakeApi
import com.mamre.billing.data.demo.SharedPriceTable
import com.mamre.billing.data.api.MamreService
import com.mamre.billing.data.api.RetrofitBackendApi
import com.mamre.billing.data.auth.EncryptedTokenStore
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.auth.TokenStore
import com.mamre.billing.data.db.AppDatabase
import com.mamre.billing.data.db.RoomTransactionRunner
import com.mamre.billing.data.db.TransactionRunner
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.repository.CatalogRemote
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.print.MockPrinter
import com.mamre.billing.print.ReceiptPrinter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** DEMO DATA: the one price table linking the Admin stand-in and the worker's FakeApi (DECISIONS 2026-10-03). */
    @Provides @Singleton
    fun sharedPrices(): SharedPriceTable = SharedPriceTable.seeded(java.time.LocalDate.now())

    /** FakeApi or the real server, chosen by the USE_FAKE_API build flag (Doc 2 s2). */
    @Provides @Singleton
    fun backendApi(json: Json, prices: SharedPriceTable): BackendApi {
        if (BuildConfig.USE_FAKE_API) return FakeApi(prices)
        val client = OkHttpClient.Builder()
            .addInterceptor(AppVersionInterceptor(BuildConfig.VERSION_NAME))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return RetrofitBackendApi(retrofit.create(MamreService::class.java), json)
    }

    @Provides @Singleton
    fun tokenStore(@ApplicationContext context: Context): TokenStore = EncryptedTokenStore(context)

    @Provides @Singleton
    fun sessionManager(api: BackendApi, store: TokenStore) = SessionManager(api, store, adminSignInAvailable = BuildConfig.USE_FAKE_API)

    /** In-memory DEMO DATA for the worker screens until Room and the outbox arrive (P2). */
    @Provides @Singleton
    fun demoStore() = DemoStore()

    /**
     * The Admin's server stand-in with DEMO DATA, its own data set (owner decision). There are no admin
     * endpoints yet (QUESTIONS), so a real build has nothing to offer and admin sign-in is refused there.
     */
    @Provides @Singleton
    fun adminApi(prices: SharedPriceTable): AdminApi {
        if (BuildConfig.USE_FAKE_API) return FakeAdminApi(prices = prices)
        error("The server has no admin API yet")
    }

    /** MockPrinter until the Symcode printer SDK arrives (Doc 2 s7, P3). */
    @Provides @Singleton
    fun receiptPrinter(): ReceiptPrinter = MockPrinter()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mamre.db").build()

    @Provides @Singleton
    fun transactionRunner(db: AppDatabase): TransactionRunner = RoomTransactionRunner(db)

    @Provides @Singleton
    fun catalogRepository(
        db: AppDatabase,
        transactions: TransactionRunner,
        api: BackendApi,
        session: SessionManager,
    ) = CatalogRepository(
        products = db.productDao(),
        customerTypes = db.customerTypeDao(),
        customers = db.customerDao(),
        priceDefaults = db.priceDefaultDao(),
        priceOverrides = db.priceOverrideDao(),
        syncState = db.syncStateDao(),
        transactions = transactions,
        remote = CatalogRemote { cursor -> session.authorized { api.catalog(it, cursor) } },
    )
}
