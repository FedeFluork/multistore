package com.multistore.core.common.di

import com.multistore.core.common.coroutine.ApplicationScope
import com.multistore.core.common.coroutine.DefaultDispatcher
import com.multistore.core.common.coroutine.DispatcherProvider
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.coroutine.MainDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The two things never taken from the ambient environment at the point of use: **time and
 * dispatchers**.
 *
 * A repository that calls `Dispatchers.IO` or `Clock.System` directly is not deterministically
 * testable, because the test has no way to substitute them. Providing them here, once, is what
 * makes that rule verifiable rather than merely recommended.
 *
 * It lives in `:core:common` rather than `:app` because it is pure Kotlin, so JVM modules can
 * depend on it without dragging in Android.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreCommonModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    fun provideDispatchers(
        @IoDispatcher io: CoroutineDispatcher,
        @DefaultDispatcher default: CoroutineDispatcher,
        @MainDispatcher main: CoroutineDispatcher,
    ): DispatcherProvider = DispatcherProvider(io = io, default = default, main = main)

    /**
     * The process-scoped coroutine scope.
     *
     * `SupervisorJob` on purpose: a background job that fails — a sync gone wrong — must not take
     * all the others down with it.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System
}
