package com.mamre.billing.data.demo

import androidx.room.Room
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.ReferenceSeed
import com.mamre.billing.data.local.RoomUnitOfWork
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.usecase.AddCustomer
import com.mamre.billing.domain.usecase.RuleException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The debug sample data is all or nothing. The seed marker is written at the very end, so a run that stops halfway (a rule
 * refuses an event, or the process is killed) must leave no half-filled database behind: the next start would replay the
 * whole history on top of it and crash on the first price that already exists.
 */
@RunWith(RobolectricTestRunner::class)
class DemoSeederAtomicTest {
    private lateinit var db: MamreDatabase

    @Before fun open() {
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), MamreDatabase::class.java, "atomic-seed-test.db")
            .allowMainThreadQueries().addMigrations(*MamreDatabase.MIGRATIONS).build()
    }

    @After fun close() {
        db.close()
    }

    @Test fun aSeedThatFailsHalfwayLeavesNothingBehindAndTheMarkerUnset() = runBlocking {
        val u = RoomUnitOfWork(db)
        ReferenceSeed(db, u).run()
        // A customer that clashes with the first demo customer (same name and location): the seeder writes the business
        // details and the prices first, then its AddCustomer is refused. Everything written before must be rolled back.
        AddCustomer(u, CustomerRepository(db), ChangeLogRepository(db))(
            CustomerForm("Spice Garden", ReferenceIds.TYPE_RESTAURANT, "", "", PaymentMode.CREDIT, "", true, location = "Irving"),
        )
        val pricesBefore = db.priceDefaultDao().count()
        val logBefore = db.changeLogDao().getAll().size
        assertEquals(0, pricesBefore)

        assertThrows(RuleException::class.java) { runBlocking { DemoSeeder(db).seed(LocalDate.of(2026, 10, 2), ZoneOffset.UTC) } }

        assertEquals("no price written", pricesBefore, db.priceDefaultDao().count())
        assertEquals("no change log entry written", logBefore, db.changeLogDao().getAll().size)
        assertEquals("the business details are still the first-run defaults", ReferenceSeed.DEFAULT_BUSINESS_NAME, db.settingDao().get(SettingKeys.BUSINESS_NAME))
        assertNull("the seed marker is not set", db.settingDao().get(DemoSeeder.SEEDED_KEY))
    }

    @Test fun aCompleteSeedSetsTheMarkerAndStartingAgainDoesNothing() = runBlocking {
        val u = RoomUnitOfWork(db)
        ReferenceSeed(db, u).run()
        DemoSeeder(db).seed(LocalDate.of(2026, 10, 2), ZoneOffset.UTC)
        assertEquals("1", db.settingDao().get(DemoSeeder.SEEDED_KEY))
        val prices = db.priceDefaultDao().count()
        val log = db.changeLogDao().getAll().size
        DemoSeeder(db).run() // the start-up task: the marker is there, so it returns at once
        assertEquals(prices, db.priceDefaultDao().count())
        assertEquals(log, db.changeLogDao().getAll().size)
    }
}
