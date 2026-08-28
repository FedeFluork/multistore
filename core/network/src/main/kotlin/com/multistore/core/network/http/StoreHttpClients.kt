package com.multistore.core.network.http

import com.multistore.core.common.net.RateLimiter
import com.multistore.core.model.StoreId
import com.multistore.core.network.challenge.ChallengeEscalator
import com.multistore.core.network.challenge.ChallengeResolver
import com.multistore.core.network.challenge.ChallengeStrategySource
import com.multistore.core.network.challenge.ChallengeTierRecorder
import com.multistore.core.network.cookie.ClearanceCookieJar
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * How the network behaves towards a single store.
 *
 * The defaults are deliberately cautious (1 request per second, burst 3); the real per-store
 * parameters arrive from `parsers.json`. A store that tolerates more will say so; one that
 * tolerates less — apkmirror asks for `Crawl-delay: 3` — is slowed down here, not in the adapter.
 */
data class StoreNetworkProfile(
    val userAgent: String,
    val permitsPerSecond: Double = 1.0,
    val burst: Int = 3,
    val connectTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 30.seconds,
    val callTimeout: Duration = Duration.ZERO,
    val followRedirects: Boolean = true,
    /**
     * How long to cache a page the store declares **nothing** about.
     *
     * `ZERO` — the default — means "respect what the site says". See [CacheHeaderInterceptor] for
     * the measurement behind the one store where it is not zero, and for the line between filling
     * a silence and contradicting a `no-store`.
     */
    val pageCacheTtl: Duration = Duration.ZERO,
)

/** Where `:core:network` gets what it cannot know on its own. */
data class NetworkEnvironment(
    /** Where the HTTP cache lives. Decided by whoever knows Android, not by this module. */
    val cacheDirectory: File,
    val cacheSizeBytes: Long = DEFAULT_CACHE_BYTES,
    val debugLogging: Boolean = false,
) {
    companion object {
        /** ~50 MB. */
        const val DEFAULT_CACHE_BYTES: Long = 50L * 1024 * 1024
    }
}

/**
 * The per-store client factory.
 *
 * One shared base `OkHttpClient`: connection pool, disk cache and dispatcher are common to every
 * store. Per-store clients are derived with `newBuilder()`, which **reuses** those resources —
 * building nine independent ones would mean nine pools, nine thread pools, and one store's cache
 * evicting another's.
 */
class StoreHttpClients(
    environment: NetworkEnvironment,
    private val rateLimiterFactory: (StoreNetworkProfile) -> RateLimiter = { profile ->
        RateLimiter(permitsPerSecond = profile.permitsPerSecond, burst = profile.burst)
    },
    /**
     * Whoever records, in diagnostics, the ladder rung a request got through on.
     *
     * Here and not in each adapter's constructor because this is the point where it **costs
     * nothing**: everyone who makes requests already receives the client factory injected, and
     * the per-store client already knows its own [StoreId].
     */
    private val tierRecorder: ChallengeTierRecorder = ChallengeTierRecorder.NONE,
    /**
     * The successful-request log, off by default.
     *
     * Same insertion point as [tierRecorder], for the same reason: it is where it reaches every
     * store without touching any adapter.
     */
    private val requestLog: RequestLog = RequestLog.NONE,
    /** See the note on `StoreHttpClient.timeSource`: it is there only to make the measurement testable. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /**
     * The rungs only Android can offer, if this build has any.
     *
     * Empty in every JVM test and in every module that cannot see Android: the ladder shortens
     * itself to the two network rungs. On device `:app` adds `:core:challenge`'s rung 3, and no
     * adapter changes by a line.
     */
    androidResolvers: List<ChallengeResolver> = emptyList(),
    /** How far the user allows climbing. Re-read on every request, not at startup. */
    strategySource: ChallengeStrategySource = ChallengeStrategySource.DEFAULT,
    /**
     * The cookie jar, shared by every store.
     *
     * Exposed rather than built in here because rung 3 has to be able to **put** something in it:
     * it is the only way a WebView can hand OkHttp what it obtained by executing the challenge.
     */
    val cookieJar: ClearanceCookieJar = ClearanceCookieJar(),
) {

    /**
     * One ladder for every store.
     *
     * The resolvers are stateless except rung 3, whose state is precisely what needs sharing: the
     * counter that stops two parallel searches from opening two WebViews for the same challenge.
     * A ladder per store would lose it.
     */
    private val escalator: ChallengeEscalator =
        ChallengeEscalator.withAndroidRungs(androidResolvers, strategySource)

    private val base: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(environment.cacheDirectory, environment.cacheSizeBytes))
        // OkHttp's default is `CookieJar.NO_COOKIES`, and with that rung 3 would have nowhere
        // to deliver the `cf_clearance` it just obtained. Keeping cookies is also what a browser
        // does, which is the criterion this project decides by.
        .cookieJar(cookieJar)
        .dispatcher(
            Dispatcher().apply {
                // With nine stores queried at once the bottleneck is not bandwidth but
                // politeness: two connections per host is already more than a browser opens
                // towards a small site.
                maxRequests = MAX_REQUESTS
                maxRequestsPerHost = MAX_REQUESTS_PER_HOST
            },
        )
        .retryOnConnectionFailure(true)
        .build()

    private val clients = ConcurrentHashMap<StoreId, StoreHttpClient>()

    fun forStore(storeId: StoreId, profile: StoreNetworkProfile): StoreHttpClient =
        clients.computeIfAbsent(storeId) { build(storeId, profile) }

    private fun build(storeId: StoreId, profile: StoreNetworkProfile): StoreHttpClient {
        val client = base.newBuilder()
            .addInterceptor(UserAgentInterceptor(profile.userAgent))
            .apply {
                // A **network** interceptor and not an application one: OkHttp's cache stores
                // what the network chain sees, so an override placed higher up would arrive
                // after the decision not to store has already been taken.
                if (profile.pageCacheTtl > Duration.ZERO) {
                    addNetworkInterceptor(CacheHeaderInterceptor(profile.pageCacheTtl))
                }
            }
            .connectTimeout(profile.connectTimeout.toJavaDuration())
            .readTimeout(profile.readTimeout.toJavaDuration())
            .callTimeout(profile.callTimeout.toJavaDuration())
            .followRedirects(profile.followRedirects)
            .followSslRedirects(profile.followRedirects)
            .build()
        return StoreHttpClient(
            storeId = storeId,
            userAgent = profile.userAgent,
            client = client,
            rateLimiter = rateLimiterFactory(profile),
            tierRecorder = tierRecorder,
            escalator = escalator,
            requestLog = requestLog,
            timeSource = timeSource,
        )
    }

    /**
     * The client used to download **images**: icons and screenshots.
     *
     * ### What it shares, and what it does not
     *
     * Coil used to use its own `OkHttpClient()`, i.e. a second connection pool towards hosts this
     * one already had a pool for. It therefore derives from [base], which gives it the pool and
     * the cookie jar. The three differences are all deliberate:
     *
     *  - **no HTTP cache** (`cache(null)`). Coil has its own disk cache, with its own eviction
     *    and key: keeping ours as well would store the same bytes **twice**, evicting store pages
     *    to make room for icons already saved elsewhere;
     *  - **a dispatcher of its own**, with a higher per-host ceiling. [base]'s is at two requests
     *    per host on purpose — politeness towards sites being scraped — but a list of twenty
     *    icons is not scraping, it is what a browser does loading a page's subresources. With the
     *    shared dispatcher the icons would queue **behind** an apkmirror request already waiting
     *    out three seconds of `Crawl-delay`;
     *  - **no rate limiter**. Nothing needs removing: the limiter lives in [StoreHttpClient] and
     *    is not an interceptor of [base], so a derived client does not have it. It is said anyway,
     *    because it is a choice and not an oversight: applying one permit per second to icons
     *    would mean a list that fills one row per second.
     *
     * ### The User-Agent, and what the measurement actually says
     *
     * Icons do not carry the store's UA — true, and measured on 26/08/2026 with **OkHttp**, not
     * with `curl`: none of the six hosts the nine stores' icons come from (`f-droid.org`,
     * `downloadr2.apkmirror.com`, `cdn.topmongo.com`, `img.utdstc.com`, `pdalife.com`,
     * `play-lh.googleusercontent.com`) requires one. All six answer **200 with no User-Agent at
     * all**, byte-for-byte identically to a Chrome mobile UA. The UA is set anyway, because any
     * app does. The reasons this client exists are the shared pool and the cache ceiling.
     */
    fun imageClient(userAgent: String): OkHttpClient = base.newBuilder()
        .cache(null)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .dispatcher(
            Dispatcher().apply {
                maxRequests = MAX_REQUESTS
                maxRequestsPerHost = MAX_IMAGE_REQUESTS_PER_HOST
            },
        )
        .build()

    /**
     * How much the HTTP cache occupies on disk right now.
     *
     * `Cache.size()` reads the journal and can touch the disk: it is called from an IO dispatcher
     * (`MaintenanceRepository` does), never from the main thread.
     */
    fun httpCacheBytes(): Long = base.cache?.let { runCatching { it.size() }.getOrDefault(0L) } ?: 0L

    /**
     * Throws away the stored pages.
     *
     * It does not touch the **ceiling**, which stays whatever was chosen at construction:
     * `okhttp3.Cache` fixes its maximum size in the constructor, which is why that ceiling is not
     * a setting.
     */
    fun clearHttpCache() {
        runCatching { base.cache?.evictAll() }
    }

    /** Closes cache and pool. Only to be called at process shutdown or in tests. */
    fun shutdown() {
        base.dispatcher.executorService.shutdown()
        base.connectionPool.evictAll()
        runCatching { base.cache?.close() }
    }

    internal companion object {
        const val MAX_REQUESTS = 24
        const val MAX_REQUESTS_PER_HOST = 2

        /**
         * How many images per host at once.
         *
         * Four and not two: a list of icons is a page subresource, not a crawl, and no browser
         * loads them two at a time. Four is also below Chrome's typical six, which remains the
         * yardstick this project uses for what counts as polite.
         */
        const val MAX_IMAGE_REQUESTS_PER_HOST = 4
    }
}
