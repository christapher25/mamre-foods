package com.mamre.billing.data.di

import android.content.Context
import androidx.room.Room
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.LocalAdminApi
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.ReferenceSeed
import com.mamre.billing.data.local.RoomUnitOfWork
import com.mamre.billing.data.local.UnitOfWork
import com.mamre.billing.data.repo.BooksRepository
import com.mamre.billing.data.repo.ExpenseRepository
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.StockRepository
import com.mamre.billing.domain.usecase.MakeBill
import com.mamre.billing.domain.usecase.RecordPayment
import com.mamre.billing.domain.usecase.RecordReturn
import com.mamre.billing.print.MockPrinter
import com.mamre.billing.print.ReceiptPrinter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * The database, the repositories and the use cases (Doc 2 s3, s5.1). One Room database file, mamre.db, with its written
 * migrations and NO destructive fallback (Doc 2 I-15): a schema the app cannot open is an error, never a wipe.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): MamreDatabase =
        Room.databaseBuilder(context, MamreDatabase::class.java, MamreDatabase.FILE_NAME)
            .addMigrations(*MamreDatabase.MIGRATIONS)
            .build()

    @Provides @Singleton
    fun unitOfWork(db: MamreDatabase): UnitOfWork = RoomUnitOfWork(db)

    @Provides @Singleton
    fun referenceSeed(db: MamreDatabase, unitOfWork: UnitOfWork) = ReferenceSeed(db, unitOfWork)

    // ------------------------------------------------------------------ repositories

    @Provides @Singleton
    fun settingsRepository(db: MamreDatabase) = SettingsRepository(db.settingDao())

    @Provides @Singleton
    fun customerRepository(db: MamreDatabase) = CustomerRepository(db)

    @Provides @Singleton
    fun priceRepository(db: MamreDatabase) = PriceRepository(db)

    @Provides @Singleton
    fun salesRepository(db: MamreDatabase) = SalesRepository(db)

    @Provides @Singleton
    fun stockRepository(db: MamreDatabase) = StockRepository(db)

    @Provides @Singleton
    fun expenseRepository(db: MamreDatabase) = ExpenseRepository(db)

    @Provides @Singleton
    fun changeLogRepository(db: MamreDatabase) = ChangeLogRepository(db)

    @Provides @Singleton
    fun booksRepository(db: MamreDatabase) = BooksRepository(db)

    // ------------------------------------------------------------------ the Sales area use cases

    @Provides
    fun makeBill(
        u: UnitOfWork,
        sales: SalesRepository,
        customers: CustomerRepository,
        prices: PriceRepository,
        settings: SettingsRepository,
        clock: Clock,
    ) = MakeBill(u, sales, customers, prices, settings, clock)

    @Provides
    fun recordPayment(u: UnitOfWork, sales: SalesRepository, customers: CustomerRepository, settings: SettingsRepository, clock: Clock) =
        RecordPayment(u, sales, customers, settings, clock)

    @Provides
    fun recordReturn(u: UnitOfWork, sales: SalesRepository, customers: CustomerRepository, prices: PriceRepository, clock: Clock) =
        RecordReturn(u, sales, customers, prices, clock)

    // ------------------------------------------------------------------ the Admin area

    @Provides @Singleton
    fun adminApi(db: MamreDatabase, clock: Clock): AdminApi = LocalAdminApi.create(db, clock)

    /** MockPrinter until the handheld's printer library arrives (Doc 2 s7, P-1). */
    @Provides @Singleton
    fun receiptPrinter(): ReceiptPrinter = MockPrinter()
}
