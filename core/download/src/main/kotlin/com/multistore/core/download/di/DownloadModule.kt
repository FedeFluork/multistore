package com.multistore.core.download.di

import com.multistore.core.download.DownloadEngine
import com.multistore.core.download.DownloadScheduler
import com.multistore.core.download.WorkManagerDownloadScheduler
import com.multistore.core.download.OkHttpDownloadEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The download engine's implementation.
 *
 * The binding lives here and not in `:app` because both types live here: the only thing `:app` really
 * has to provide is [com.multistore.core.download.DownloadNetworkProfiles], which requires knowing
 * the adapters and therefore cannot live in this module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    @Singleton
    abstract fun bindDownloadEngine(impl: OkHttpDownloadEngine): DownloadEngine

    @Binds
    @Singleton
    abstract fun bindDownloadScheduler(impl: WorkManagerDownloadScheduler): DownloadScheduler
}
