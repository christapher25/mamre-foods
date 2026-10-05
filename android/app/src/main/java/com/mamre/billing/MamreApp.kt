package com.mamre.billing

import android.app.Application
import com.mamre.billing.data.startup.AppStartup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class MamreApp : Application() {
    @Inject lateinit var startup: AppStartup

    /** Lives as long as the process: the start-up work of a build runs here, never on the main thread. */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Nothing may block here (Android kills an app whose Application.onCreate takes too long): the database is opened, and
        // created with its reference data on first run, in the background; the first screen waits for it (AppReadiness).
        startup.start(startupScope)
    }
}
