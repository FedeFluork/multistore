package com.multistore.app.di

import android.content.Context
import com.multistore.core.common.coroutine.ApplicationScope
import com.multistore.core.common.di.StoreWorkDir
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.download.DownloadNetworkProfiles
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.StoreId
import com.multistore.core.network.challenge.ChallengeResolver
import com.multistore.core.network.challenge.ChallengeStrategySource
import com.multistore.core.network.challenge.ChallengeTierRecorder
import com.multistore.core.network.cookie.ClearanceCookieJar
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.RequestLog
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.api.StoreAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What `:core:network` and `:core:download` cannot know on their own.
 *
 * They are pure Kotlin modules, or nearly: they do not know `Context`, do not know where the cache is
 * and cannot see the adapters. Everything that depends on one of those three things ends up here.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * The HTTP cache in a subfolder of its own.
     *
     * `cacheDir` directly would be a mistake: OkHttp treats the whole content of the directory it is
     * given as **its own** and deletes whatever it does not recognise. With a bare `cacheDir` it would
     * take away the adapters' working files, which live right beside it (see [provideStoreWorkDir]).
     */
    @Provides
    @Singleton
    fun provideNetworkEnvironment(@ApplicationContext context: Context): NetworkEnvironment =
        NetworkEnvironment(cacheDirectory = File(context.cacheDir, HTTP_CACHE_DIRECTORY))

    /**
     * The client factory, with the escalation-tier registry attached.
     *
     * The `rateLimiterFactory` stays the default one: passing it explicitly only in order to name the
     * third parameter would be a way of making something look configurable when it is not.
     */
    @Provides
    @Singleton
    fun provideStoreHttpClients(
        environment: NetworkEnvironment,
        tierRecorder: ChallengeTierRecorder,
        requestLog: RequestLog,
        androidResolvers: Set<@JvmSuppressWildcards ChallengeResolver>,
        strategySource: ChallengeStrategySource,
        cookieJar: ClearanceCookieJar,
    ): StoreHttpClients = StoreHttpClients(
        environment = environment,
        tierRecorder = tierRecorder,
        requestLog = requestLog,
        androidResolvers = androidResolvers.toList(),
        strategySource = strategySource,
        cookieJar = cookieJar,
    )

    /**
     * How far to escalate, re-read on every request.
     *
     * An **eager** `StateFlow` and not a suspending read, and the reason is where the question gets
     * asked: inside the escalation ladder, between a 403 and the next attempt, where suspending to read
     * the DataStore would mean making the request wait for a value that almost never changes. Eager
     * because the first challenge can arrive before any screen has observed the settings.
     *
     * The initial value is the compiled default, which is also proto3's zero value: in the window
     * between startup and the DataStore's first emission, "what to do" and "what an empty DataStore
     * would say" coincide.
     */
    @Provides
    @Singleton
    fun provideChallengeStrategySource(
        settings: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): ChallengeStrategySource {
        val current = settings.network
            .map { it.challengeStrategy }
            .stateIn(scope, SharingStarted.Eagerly, ChallengeStrategy.DEFAULT)
        return ChallengeStrategySource { current.value }
    }

    /**
     * The adapters' working folder: `cacheDir`, not `filesDir`.
     *
     * `entry.jar` and the index diffs end up here, that is files that can be re-downloaded. Staged APKs
     * live in `filesDir` instead, because the system can empty the cache at any moment — including the
     * one between verifying an APK and committing it.
     */
    @Provides
    @Singleton
    @StoreWorkDir
    fun provideStoreWorkDir(@ApplicationContext context: Context): File = context.cacheDir

    /**
     * The User-Agent the download engine uses with each store.
     *
     * Derived from the adapters and not from a hand-written table: the UA is already a mandatory field
     * of `StoreCapabilities` — the contract test fails if an adapter does not declare it — and copying
     * it into a second place would only give us a way to let the two diverge. On apkmirror OkHttp's
     * default is a guaranteed 403, so the value arriving here is not cosmetic.
     *
     * **On the rate limit.** This profile carries a cautious one and not the store's real one, and that
     * is not an oversight: `StoreHttpClients.forStore` memoises the client on the **first** request for
     * that store, so whoever builds it first wins — and that is always the adapter, because
     * `StoreHealthRepository.registerKnownStores()` at startup walks `StoreRegistry` and therefore
     * instantiates every adapter before a download can begin. If that call ever disappeared from
     * startup, a store's real rate limit would stop applying to downloads: that is why it is written
     * here.
     */
    @Provides
    @Singleton
    fun provideDownloadNetworkProfiles(
        adapters: Set<@JvmSuppressWildcards StoreAdapter>,
    ): DownloadNetworkProfiles {
        val byStore: Map<StoreId, StoreNetworkProfile> = adapters.associate { adapter ->
            adapter.id to StoreNetworkProfile(userAgent = adapter.capabilities.userAgent)
        }
        return DownloadNetworkProfiles { storeId ->
            byStore[storeId] ?: error(
                "No adapter wired for $storeId: a download cannot start for a store the app does " +
                    "not know. An @IntoSet is missing in StoreModule.",
            )
        }
    }

    private const val HTTP_CACHE_DIRECTORY = "http"
}
