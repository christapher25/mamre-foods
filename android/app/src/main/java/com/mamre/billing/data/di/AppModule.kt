package com.mamre.billing.data.di

import android.content.Context
import com.mamre.billing.BuildConfig
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.LocalAdminApi
import com.mamre.billing.data.export.ExportBuilder
import com.mamre.billing.data.export.ExportWriter
import com.mamre.billing.data.local.MamreDatabase
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
import com.mamre.billing.data.system.AndroidMonotonicClock
import com.mamre.billing.domain.security.MonotonicClock
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.data.repo.UsersRepository
import com.mamre.billing.domain.usecase.AccountStatus
import com.mamre.billing.domain.usecase.AddSalesman
import com.mamre.billing.domain.usecase.ChangeOwnPassword
import com.mamre.billing.domain.usecase.CreateAdmin
import com.mamre.billing.domain.usecase.Login
import com.mamre.billing.domain.usecase.ResetPassword
import com.mamre.billing.domain.usecase.SetActive
import com.mamre.billing.domain.usecase.MakeBill
import com.mamre.billing.domain.usecase.RecordPayment
import com.mamre.billing.domain.usecase.RecordReturn
import com.mamre.billing.print.AndroidPrintDriver
import com.mamre.billing.print.BluetoothEscPosDriver
import com.mamre.billing.print.BluetoothPermissionGate
import com.mamre.billing.print.PrinterChain
import com.mamre.billing.print.SunmiServiceDriver
import com.mamre.billing.print.SettingsPrinterMethodStore
import com.mamre.billing.print.ReceiptPrinter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mamre.billing.domain.usecase.RecordExport
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
    fun monotonicClock(@ApplicationContext context: Context): MonotonicClock = AndroidMonotonicClock(context)

    /** What this build shows: SHOW_ANALYTICS is false in release (Doc 2 s9, A-28). */
    @Provides @Singleton
    fun features(): Features = Features(showAnalytics = BuildConfig.SHOW_ANALYTICS)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): MamreDatabase =
        MamreDatabase.builder(context).build()

    @Provides
    fun exportBuilder(db: MamreDatabase, clock: Clock): ExportBuilder = ExportBuilder(db, clock)

    @Provides
    fun exportWriter(@ApplicationContext context: Context): ExportWriter = ExportWriter(context.cacheDir)

    @Provides @Singleton
    fun unitOfWork(db: MamreDatabase): UnitOfWork = RoomUnitOfWork(db)

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

    // ------------------------------------------------------------------ the export (Doc 1 s11.1, Doc 2 s5.3)

    @Provides
    fun recordExport(u: UnitOfWork, settings: SettingsRepository, clock: Clock) = RecordExport(u, settings, clock)

    // ------------------------------------------------------------------ accounts and login (B8)

    @Provides @Singleton
    fun usersRepository(db: MamreDatabase) = UsersRepository(db.userDao())

    @Provides
    fun accountStatus(users: UsersRepository) = AccountStatus(users)

    @Provides
    fun createAdmin(u: UnitOfWork, users: UsersRepository, changeLog: ChangeLogRepository, clock: Clock) = CreateAdmin(u, users, changeLog, clock)

    @Provides
    fun addSalesman(u: UnitOfWork, users: UsersRepository, changeLog: ChangeLogRepository, clock: Clock) = AddSalesman(u, users, changeLog, clock)

    @Provides
    fun login(u: UnitOfWork, users: UsersRepository, settings: SettingsRepository, clock: Clock, mono: MonotonicClock) =
        Login(u, users, settings, clock, mono)

    @Provides
    fun resetPassword(u: UnitOfWork, users: UsersRepository, changeLog: ChangeLogRepository, clock: Clock) = ResetPassword(u, users, changeLog, clock)

    @Provides
    fun setActive(u: UnitOfWork, users: UsersRepository, changeLog: ChangeLogRepository, clock: Clock) = SetActive(u, users, changeLog, clock)

    @Provides
    fun changeOwnPassword(u: UnitOfWork, users: UsersRepository, changeLog: ChangeLogRepository, clock: Clock, mono: MonotonicClock) =
        ChangeOwnPassword(u, users, changeLog, clock, mono)

    // ------------------------------------------------------------------ the Admin area

    @Provides @Singleton
    fun adminApi(db: MamreDatabase, clock: Clock): AdminApi = LocalAdminApi.create(db, clock)

    @Provides @Singleton
    fun bluetoothPermissionGate(@ApplicationContext context: Context) = BluetoothPermissionGate.forContext(context)

    /** The real chain in every build (Doc 4 s5): Bluetooth ESC/POS, then the Android print dialog. */
    @Provides @Singleton
    fun receiptPrinter(@ApplicationContext context: Context, gate: BluetoothPermissionGate, settings: SettingsRepository): ReceiptPrinter =
        PrinterChain(listOf(SunmiServiceDriver(context), BluetoothEscPosDriver(context, gate), AndroidPrintDriver(context)), SettingsPrinterMethodStore(settings))
}
