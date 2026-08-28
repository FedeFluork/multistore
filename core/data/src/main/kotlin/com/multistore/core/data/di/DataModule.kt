package com.multistore.core.data.di

import android.content.Context
import android.os.Build
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.repository.AppDetailRepositoryImpl
import com.multistore.core.data.repository.CrossStoreRepository
import com.multistore.core.data.repository.CrossStoreRepositoryImpl
import com.multistore.core.data.repository.DiagnosticsRepository
import com.multistore.core.data.repository.DiagnosticsRepositoryImpl
import com.multistore.core.data.repository.DownloadRepository
import com.multistore.core.data.repository.DownloadRepositoryImpl
import com.multistore.core.data.repository.InstallRepository
import com.multistore.core.data.repository.InstallRepositoryImpl
import com.multistore.core.data.repository.MaintenanceRepository
import com.multistore.core.data.repository.MaintenanceRepositoryImpl
import com.multistore.core.data.repository.InstalledAppsRepository
import com.multistore.core.data.repository.InstalledAppsRepositoryImpl
import com.multistore.core.data.repository.RemoteConfigRepository
import com.multistore.core.data.repository.RemoteConfigRepositoryImpl
import com.multistore.core.data.repository.RemoteIndexRepository
import com.multistore.core.data.repository.RemoteIndexRepositoryImpl
import com.multistore.core.data.repository.SelfUpdateRepository
import com.multistore.core.data.repository.SelfUpdateRepositoryImpl
import com.multistore.core.data.repository.SearchRepository
import com.multistore.core.data.repository.SearchRepositoryImpl
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.repository.SettingsRepositoryImpl
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.data.repository.StoreHealthRepositoryImpl
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.data.repository.StoreIndexRepositoryImpl
import com.multistore.core.data.repository.UpdateRepository
import com.multistore.core.data.repository.UpdateRepositoryImpl
import com.multistore.core.data.store.ChallengeTierLog
import com.multistore.core.data.store.RequestLogRecorder
import com.multistore.core.download.DownloadTask
import com.multistore.core.network.challenge.ChallengeTierRecorder
import com.multistore.core.network.http.RequestLog
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.OwnPackage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import com.multistore.store.api.StoreAdapter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindRemoteConfigRepository(impl: RemoteConfigRepositoryImpl): RemoteConfigRepository

    @Binds
    @Singleton
    abstract fun bindRemoteIndexRepository(impl: RemoteIndexRepositoryImpl): RemoteIndexRepository

    @Binds
    @Singleton
    abstract fun bindSelfUpdateRepository(impl: SelfUpdateRepositoryImpl): SelfUpdateRepository

    @Binds
    @Singleton
    abstract fun bindStoreHealthRepository(impl: StoreHealthRepositoryImpl): StoreHealthRepository

    @Binds
    @Singleton
    abstract fun bindStoreIndexRepository(impl: StoreIndexRepositoryImpl): StoreIndexRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindAppDetailRepository(impl: AppDetailRepositoryImpl): AppDetailRepository

    @Binds
    @Singleton
    abstract fun bindCrossStoreRepository(impl: CrossStoreRepositoryImpl): CrossStoreRepository

    @Binds
    @Singleton
    abstract fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository

    @Binds
    @Singleton
    abstract fun bindUpdateRepository(impl: UpdateRepositoryImpl): UpdateRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindInstallRepository(impl: InstallRepositoryImpl): InstallRepository

    @Binds
    abstract fun bindMaintenanceRepository(impl: MaintenanceRepositoryImpl): MaintenanceRepository

    @Binds
    abstract fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository

    /**
     * Whoever records the escalation ladder's rung in `health_events`.
     *
     * The binding lives here and not in `:app` because the implementation belongs to this module; the
     * consumer, `StoreHttpClients`, receives it from `:app`'s network module, which is the only one
     * that knows how to build the client factory.
     */
    @Binds
    @Singleton
    abstract fun bindChallengeTierRecorder(impl: ChallengeTierLog): ChallengeTierRecorder

    /** Who records the successful requests, when `diagnostics_log_enabled` is on. */
    @Binds
    @Singleton
    abstract fun bindRequestLog(impl: RequestLogRecorder): RequestLog

    /**
     * What the download worker asks for, served by the repository.
     *
     * `:core:download` cannot see this module — the dependency goes the other way — and the worker
     * lives there because that is where the notification, the channel and the permissions live. This
     * binding is the point where the two halves meet, and it sits in `:core:data` because that is the
     * side that knows both.
     */
    @Binds
    @Singleton
    abstract fun bindDownloadTask(impl: DownloadRepositoryImpl): DownloadTask

    /**
     * Declares the adapters' multibinding **even when it is empty**.
     *
     * Without it, a graph in which nobody has yet done `@IntoSet` would not compile: Hilt does not
     * know the set and `StoreRegistry` is left without a dependency. With `@Multibinds`, the set
     * always exists and the adapters populate it from `:app`. It is also what lets a `:core:data`
     * test run without wiring any store.
     */
    @Multibinds
    abstract fun storeAdapters(): Set<StoreAdapter>
}

@Module
@InstallIn(SingletonComponent::class)
internal object DeviceModule {

    /**
     * The device profile, read once.
     *
     * It is an injected value and not a `Build` read scattered through the code, because the
     * version-choice rule depends on `SDK_INT` and on the ABIs: by injecting it, that rule is tested
     * on the JVM for every combination instead of only for the current emulator's.
     */
    @Provides
    @Singleton
    fun provideDeviceProfile(@ApplicationContext context: Context): DeviceProfile = DeviceProfile(
        sdkInt = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        // `resources.displayMetrics` and not `DisplayMetrics.DENSITY_DEVICE_STABLE`: the second is
        // the factory density, the first the one in use — and on Android the user can change it from
        // the display settings. The resources the app will look for at runtime are those of the
        // density in use.
        densityDpi = context.resources.displayMetrics.densityDpi,
    )

    /**
     * Our own `packageName`, read from the `Context` and not from a constant.
     *
     * It changes with the variant — `com.multistore.debug`, `com.multistore.minified`,
     * `com.multistore` — so a hand-written constant would be right for one build in three.
     */
    @Provides
    @Singleton
    fun provideOwnPackage(@ApplicationContext context: Context): OwnPackage =
        OwnPackage(context.packageName)
}
