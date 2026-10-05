package com.mamre.billing

import android.app.Application
import com.mamre.billing.data.startup.AppStartup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class MamreApp : Application() {
    @Inject lateinit var startup: AppStartup

    /** Lives as long as the process: the background start-up work of a build (the debug sample data). */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // The reference data must exist before the first screen reads it (Doc 2 s5.2); later starts only check. Nothing else
        // may block here: Android kills an app whose Application.onCreate takes too long.
        runBlocking { startup.runReference() }
        startup.startExtras(startupScope)
    }
}
