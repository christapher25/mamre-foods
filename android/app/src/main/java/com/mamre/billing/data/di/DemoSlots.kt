package com.mamre.billing.data.di

import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.data.demo.DemoStore
import dagger.Module
import dagger.BindsOptionalOf
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/**
 * Marks an implementation that only a DEBUG build provides (the demo server stand-in and its seeded data).
 * The release build has no module that binds anything with this qualifier, so the slots below stay empty and
 * the release code cannot reference a demo class: those classes are not in its source set at all.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DemoImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class DemoSlots {
    @BindsOptionalOf @DemoImpl abstract fun demoBackend(): BackendApi

    @BindsOptionalOf @DemoImpl abstract fun demoAdmin(): AdminApi

    @BindsOptionalOf @DemoImpl abstract fun demoStore(): DemoStore
}
