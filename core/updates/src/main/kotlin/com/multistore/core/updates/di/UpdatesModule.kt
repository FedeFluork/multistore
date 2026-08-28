package com.multistore.core.updates.di

import com.multistore.core.updates.UpdateScheduler
import com.multistore.core.updates.WorkManagerUpdateScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdatesModule {

    @Binds
    @Singleton
    abstract fun bindUpdateScheduler(impl: WorkManagerUpdateScheduler): UpdateScheduler
}
