package com.mamre.billing.data.di

import android.content.Context
import androidx.room.Room
import com.mamre.billing.BuildConfig
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.UnavailableAdminApi
import com.mamre.billing.data.api.AppVersionInterceptor
import com.mamre.billing.data.api.BackendApi
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
import java.util.Optional
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

    /**
     * The real server client, or the demo stand-in when this is a debug build (USE_FAKE_API) and the debug source
     * set bound one. A release build has no demo classes at all, so [demo] is empty there (Doc 2 s2).
     */
    @Provides @Singleton
    fun backendApi(json: Json, @DemoImpl demo: Optional<BackendApi>): BackendApi {
        if (BuildConfig.USE_FAKE_API && demo.isPresent) return demo.get()
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
    fun sessionManager(api: BackendApi, store: TokenStore) = SessionManager(api, store, adminSignInAvailable = BuildConfig.USE_FAKE_API && BuildConfig.DEBUG)

    /** The worker's in-memory records until Room and the outbox arrive (P2); a debug build starts it with demo data. */
    @Provides @Singleton
    fun demoStore(@DemoImpl demo: Optional<DemoStore>): DemoStore = demo.orElseGet { DemoStore() }

    /**
     * The Admin API. There are no admin endpoints yet (QUESTIONS), so a release build gets the stub that always
     * answers "Admin sign-in is not available on the server yet"; a debug build uses the demo server stand-in.
     */
    @Provides @Singleton
    fun adminApi(@DemoImpl demo: Optional<AdminApi>): AdminApi =
        if (BuildConfig.USE_FAKE_API && demo.isPresent) demo.get() else UnavailableAdminApi()

    /** MockPrinter until the Symcode printer SDK arrives (Doc 2 s7, P3). */
    @Provides @Singleton
    fun receiptPrinter(): ReceiptPrinter = MockPrinter()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mamre.db")
            .fallbackToDestructiveMigration(true) // the catalog and its cursor are pulled again; nothing else is stored yet
            .build()

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
