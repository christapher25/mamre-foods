package com.mamre.billing.data.di

import com.mamre.billing.data.startup.AppReadiness
import com.mamre.billing.data.startup.AppStartup
import com.mamre.billing.data.startup.StartupTask
import com.mamre.billing.ui.DataLabel
import com.mamre.billing.ui.EntryGate
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Places a build may fill and the release build leaves empty: extra startup tasks, a screen in front of the app and a
 * label for sample data. The release build binds none of them, so it starts at the Sales Home on reference data only.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OptionalSlots {
    @Multibinds abstract fun startupTasks(): Set<StartupTask>

    @BindsOptionalOf abstract fun entryGate(): EntryGate

    @BindsOptionalOf abstract fun dataLabel(): DataLabel

    @Binds abstract fun readiness(startup: AppStartup): AppReadiness
}
