package com.mamre.billing.data.startup

import com.mamre.billing.data.local.MamreDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Work a build adds to the start of the app, after the database is open with its reference data (the debug build adds its sample data). */
fun interface StartupTask {
    suspend fun run()
}

/** What the first screen waits on: true once the database is open (Doc 2 s5.2). */
interface AppReadiness {
    val ready: StateFlow<Boolean>
}

/**
 * Start-up (Doc 2 s5.2). Nothing here blocks the main thread: [start] only launches. The database is opened on a background
 * thread; the first time ever, that CREATES it, and the reference data is written inside the same transaction as the schema
 * ([com.mamre.billing.data.local.ReferenceSeedCallback]), so there is no half-seeded database. The first screen waits on
 * [ready]. Whatever a build adds runs after that, in the background, and reports through [extrasDone]; a release build has no
 * extra task, so it is done at once.
 */
@Singleton
class AppStartup @Inject constructor(
    private val database: MamreDatabase,
    private val tasks: @JvmSuppressWildcards Set<StartupTask>,
) : AppReadiness {
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _extrasDone = MutableStateFlow(tasks.isEmpty())

    /** True when every extra task has finished (at once when a build has none). A failed task never reports done. */
    val extrasDone: StateFlow<Boolean> = _extrasDone.asStateFlow()

    /** Opens the database off the main thread (creating it with its reference data on first run) and marks the app ready. */
    suspend fun openDatabase() {
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
        _ready.value = true
    }

    /**
     * Runs the extra tasks one after the other in [scope], after [openDatabase]. An exception is not swallowed: it goes to the
     * scope's handler (the app crashes) so a refused sample event is found at once, not hidden behind half-filled data.
     */
    fun startExtras(scope: CoroutineScope) {
        if (tasks.isEmpty()) return
        scope.launch {
            tasks.forEach { it.run() }
            _extrasDone.value = true
        }
    }

    /** The whole start: open the database, then the extras. Returns at once. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            openDatabase()
            tasks.forEach { it.run() }
            _extrasDone.value = true
        }
    }
}
