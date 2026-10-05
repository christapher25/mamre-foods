package com.mamre.billing.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * AT-18 (Doc 2 I-15, s5.1): the migration harness. It opens the exported schema of version 1, writes rows and opens
 * the file through Room with every written migration. When version 2 is written, add its test beside this one
 * (create version 1 from the exported schema, fill it, run MIGRATION_1_2, check the data). The guards keep the
 * harness honest: every version has its exported schema and every step has a migration.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationHarnessTest {
    private val name = "migration-test.db"
    private val context: Context = RuntimeEnvironment.getApplication()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        context.getDatabasePath(name),
        AndroidSQLiteDriver(),
        MamreDatabase::class,
        { MamreDatabase_Impl() },
        emptyList(),
    )

    @Test fun aVersion1DatabaseKeepsItsDataWhenOpenedWithEveryWrittenMigration() {
        helper.createDatabase(1).apply {
            execSQL("INSERT INTO customer_types (id, name, salesman_can_edit_price, is_active) VALUES ('t1', 'Retail', 1, 1)")
            execSQL("INSERT INTO settings (`key`, value) VALUES ('device_code', 'W1')")
            close()
        }
        val db = Room.databaseBuilder(context, MamreDatabase::class.java, name)
            .addMigrations(*MamreDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                val types = db.customerTypeDao().getAll()
                assertEquals(listOf("Retail"), types.map { it.name })
                assertTrue(types.single().salesmanCanEditPrice)
                assertEquals("W1", db.settingDao().get(SettingKeys.DEVICE_CODE))
            }
        } finally {
            db.close()
        }
    }

    @Test fun everyVersionHasAnExportedSchemaAndEveryStepHasAMigration() {
        val root = listOf(File("schemas"), File("app/schemas")).first { it.isDirectory }
        val dir = File(root, MamreDatabase::class.java.name)
        val versions = dir.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals((1..versions.max()).toList(), versions)
        val steps = MamreDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        for (v in 1 until versions.max()) assertTrue("no migration from $v to ${v + 1}", (v to v + 1) in steps)
        assertEquals(versions.max() - 1, steps.size)
    }
}
