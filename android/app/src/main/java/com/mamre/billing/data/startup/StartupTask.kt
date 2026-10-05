package com.mamre.billing.data.startup

import com.mamre.billing.data.local.ReferenceSeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Work a build adds to the start of the app, after the reference data exists (the debug build adds its sample data). */
fun interface StartupTask {
    suspend fun run()
}

/**
 * What runs at the start (Doc 2 s5.2). Only [runReference], the first-run reference data, is awaited before the first screen
 * (it is a handful of rows and later starts only check). Whatever a build adds runs in the BACKGROUND through [startExtras]
 * and reports through [extrasDone]: replaying the debug sample data on the main thread made Android kill the app for
 * failing to complete startup. A release build has no extra task, so it is ready at once.
 */
@Singleton
class AppStartup @Inject constructor(
    private val seed: ReferenceSeed,
    private val tasks: @JvmSuppressWildcards Set<StartupTask>,
) {
    private val _extrasDone = MutableStateFlow(tasks.isEmpty())

    /** True when every extra task has finished (at once when a build has none). A failed task never reports done. */
    val extrasDone: StateFlow<Boolean> = _extrasDone.asStateFlow()

    /** Creates the reference data if it is missing, and returns when it exists. */
    suspend fun runReference() {
        seed.run()
    }

    /**
     * Runs the extra tasks one after the other in [scope], after [runReference]. An exception is not swallowed: it goes to
     * the scope's handler (the app crashes) so a refused sample event is found at once, not hidden behind half-filled data.
     */
    fun startExtras(scope: CoroutineScope) {
        if (tasks.isEmpty()) return
        scope.launch {
            tasks.forEach { it.run() }
            _extrasDone.value = true
        }
    }
}
