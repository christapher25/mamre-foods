package com.mamre.billing.data.di

import com.mamre.billing.data.demo.DemoSeeder
import com.mamre.billing.data.startup.StartupTask
import com.mamre.billing.ui.DataLabel
import com.mamre.billing.ui.EntryGate
import com.mamre.billing.ui.login.DemoLoginGate
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * DEBUG ONLY (src/debug): fills the places the release build leaves empty (OptionalSlots): the demo login in front of the
 * app, the sample-data label and the startup task that puts the sample data into the real database. A release build does
 * not compile this file, so none of it can reach a release package.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DemoBindings {
    @Binds abstract fun entryGate(gate: DemoLoginGate): EntryGate

    @Binds @IntoSet abstract fun seeder(seeder: DemoSeeder): StartupTask
}

@Module
@InstallIn(SingletonComponent::class)
object DemoProvides {
    @Provides @Singleton
    fun dataLabel(): DataLabel = object : DataLabel {
        override val text: String = "DEMO DATA"
    }
}
