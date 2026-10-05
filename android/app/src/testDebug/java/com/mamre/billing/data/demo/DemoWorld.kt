package com.mamre.billing.data.demo

import android.content.Context
import androidx.room.Room
import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.MutableClock
import com.mamre.billing.data.local.ReferenceSeed
import com.mamre.billing.data.local.RoomUnitOfWork
import com.mamre.billing.data.local.World
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.robolectric.RuntimeEnvironment

/**
 * The debug sample data on the REAL Room database, for the unit tests that used to run on the fake Admin server: the
 * seven months of demo history, replayed through the use cases by DemoSeeder at a fixed day. The history is built once per
 * test JVM into a template file and copied for every test, so each test gets its own fresh copy and the suite stays fast.
 * Run with RobolectricTestRunner.
 */
object DemoWorld {
    /** The day the sample data is built for, and the day the clock stands on. */
    val today: LocalDate = LocalDate.of(2026, 10, 2)
    const val CLOCK_START = "2026-10-02T14:00:00Z"

    private const val TEMPLATE_NAME = "demo-template.db"
    private const val TEST_NAME = "demo-test.db"
    @Volatile private var template: File? = null

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun open(name: String): MamreDatabase =
        Room.databaseBuilder(context, MamreDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*MamreDatabase.MIGRATIONS).build()

    /** Builds the history once (seconds) and keeps the file in the JVM's temp folder. */
    private suspend fun buildTemplate(): File {
        template?.takeIf { it.exists() }?.let { return it }
        val db = open(TEMPLATE_NAME)
        ReferenceSeed(db, RoomUnitOfWork(db)).run()
        DemoSeeder(db).seed(today, ZoneOffset.UTC, until = LocalDateTime.of(today, LocalTime.of(14, 0))) // the clock stands at CLOCK_START
        db.close() // closing checkpoints the write-ahead log into the one file
        val built = context.getDatabasePath(TEMPLATE_NAME)
        val kept = File.createTempFile("mamre-demo-template", ".db").apply { deleteOnExit() }
        built.copyTo(kept, overwrite = true)
        template = kept
        return kept
    }

    /** A new world on its own copy of the sample data, with the clock at [CLOCK_START]. */
    suspend fun open(): World {
        val file = buildTemplate()
        val target = context.getDatabasePath(TEST_NAME)
        target.parentFile?.mkdirs()
        file.copyTo(target, overwrite = true)
        val world = World(open(TEST_NAME), MutableClock(CLOCK_START))
        world.logBaseline = world.books.changeLog().size
        return world
    }
}
