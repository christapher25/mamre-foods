package com.mamre.billing.data.di

import android.content.Context
import androidx.room.Room
import com.mamre.billing.BuildConfig
import com.mamre.billing.data.api.AppVersionInterceptor
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.data.api.FakeApi
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

    /** FakeApi or the real server, chosen by the USE_FAKE_API build flag (Doc 2 s2). */
    @Provides @Singleton
    fun backendApi(json: Json): BackendApi {
        if (BuildConfig.USE_FAKE_API) return FakeApi()
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
