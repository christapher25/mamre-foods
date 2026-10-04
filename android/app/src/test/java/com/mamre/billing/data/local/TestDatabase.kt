package com.mamre.billing.data.local

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment

/** The real Room database on Robolectric's SQLite, for the unit tests (DECISIONS 2026-10-04). Run with RobolectricTestRunner. */
object TestDatabase {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** A throw-away database in memory. */
    fun inMemory(): MamreDatabase =
        Room.inMemoryDatabaseBuilder(context, MamreDatabase::class.java).allowMainThreadQueries().build()

    /**
     * A database in a file: close it and call this again with the same name to see what was really stored.
     * The migration list is the production one, and there is no destructive fallback.
     */
    fun file(name: String): MamreDatabase =
        Room.databaseBuilder(context, MamreDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*MamreDatabase.MIGRATIONS).build()

    fun delete(name: String) {
        context.deleteDatabase(name)
    }
}
