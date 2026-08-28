package com.multistore.core.network.http

import com.multistore.core.common.net.RateLimiter
import com.multistore.core.model.StoreId
import com.multistore.core.network.challenge.ChallengeEscalator
import com.multistore.core.network.challenge.ChallengeTierRecorder
import java.io.IOException
import kotlin.time.TimeSource
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * **One** store's client: the same connection pool and cache as the others, but with that
 * store's User-Agent and rate limit.
 *
 * Every request passes through the rate limiter, and an adapter never builds an OkHttp client by
 * hand. This class is what an adapter receives injected: it does not expose OkHttp's builder, so
 * there is no convenient way around the limit.
 *
 * **A known limitation, declared rather than hidden.** The permit is taken once per logical call,
 * not per hop: if OkHttp follows three redirects, the rate limiter counted one. The choice is
 * deliberate — an interceptor waiting inside the chain would have to block a dispatcher thread,
 * and with nine stores in parallel it would exhaust them. Containing hops is left to
 * `Dispatcher.maxRequestsPerHost`. For stores with long chains (an1 does 3-4 hops) the effective
 * limit is tuned accordingly.
 */
class StoreHttpClient internal constructor(
    val storeId: StoreId,
    /**
     * The User-Agent this client speaks with, exposed rather than merely applied.
     *
     * Rung 3 needs it, and it is not cosmetic: a `cf_clearance` is bound to the User-Agent that
     * obtained it. If the WebView presented itself while OkHttp presents the store's UA, the
     * cookie would come back **valid and useless** — and the symptom would be a rung 3 that
     * appears to work (the WebView passes) with a retry that keeps getting 403: the hardest
     * diagnosis of all.
     *
     * `UserAgentInterceptor` adds it inside the chain, so it is not readable from the `Request` a
     * resolver receives: either it is exposed here, or it is copied somewhere.
     */
    val userAgent: String,
    /**
     * `internal` and not `private`, for one reason only: `ImageClientTest` has to compare **this**
     * client with the image one — same connection pool, different dispatcher, no HTTP cache.
     * Those are three configuration properties, so the only way to test them is to look at them:
     * an indirect test would only say the requests go through, which they would anyway.
     */
    internal val client: OkHttpClient,
    private val rateLimiter: RateLimiter,
    private val tierRecorder: ChallengeTierRecorder = ChallengeTierRecorder.NONE,
    /**
     * The escalation ladder, already assembled with the rungs this build actually has.
     *
     * It arrives here rather than through each adapter's constructor for the same reason as
     * [ChallengeTierRecorder]: everyone who makes requests already receives the client factory
     * injected, so rung 3 could be added **without touching any of the adapters**. Passing it by
     * constructor would have meant modifying every store module for a capability none of them
     * implements.
     */
    val escalator: ChallengeEscalator = ChallengeEscalator.networkOnly(),
    /**
     * The successful-request log, off by default.
     *
     * It receives **every** request that reaches the headers, including one answering 403: "it
     * arrived and said no" and "it never left" are two different diagnoses, and telling them
     * apart is half the reason this log exists. What does not arrive here is the network
     * exception, which ends up where it always did — in `health_events` as a failure.
     */
    private val requestLog: RequestLog = RequestLog.NONE,
    /**
     * The clock a request is measured with.
     *
     * Injected for the same reason `Clock` and dispatchers are injected here: without it the
     * measurement is not testable. A test using `runTest`'s virtual time advances the scheduler's
     * waits **without** real time passing, so `TimeSource.Monotonic` would answer "zero
     * milliseconds" to everything — and the test checking that the rate limiter's wait is not
     * counted would stay green even while counting it.
     */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /**
     * Records that getting through required climbing a rung.
     *
     * Only above zero: rung 0 is the ordinary request, i.e. nearly all of them, and a diagnostic
     * row for each would fill `health_events` with the news that nothing happened. The
     * interesting fact is the opposite — when, and for whom, climbing was needed.
     */
    fun recordTier(tier: Int) {
        if (tier > 0) tierRecorder.record(storeId, tier)
    }

    /**
     * Runs the request honouring the rate limit.
     *
     * The caller closes the response: that is OkHttp's rule, and it is not hidden behind an
     * in-memory copy, because a 57 MB index also travels over this network and has to be consumed
     * as a stream.
     */
    @Throws(IOException::class)
    suspend fun execute(request: Request): Response {
        rateLimiter.acquire()
        // The stopwatch starts **after** the rate limiter, and that is not a detail: apkmirror
        // declares `Crawl-delay: 3` and we wait those three seconds on purpose. Counting them as
        // the store's response time would turn our own politeness into a diagnosis of someone
        // else's slowness.
        val startedAt = timeSource.markNow()
        val response = client.newCall(request).await()
        requestLog.record(
            storeId = storeId,
            method = request.method,
            url = request.url.toString(),
            code = response.code,
            elapsed = startedAt.elapsedNow(),
        )
        return response
    }

    /** Like [execute], but for files that must not enter the HTTP cache. */
    @Throws(IOException::class)
    suspend fun executeUncached(request: Request): Response =
        execute(request.newBuilder().cacheControl(NO_STORE).build())

    /**
     * Derives a client with the same pool but different settings.
     *
     * Used by `ChallengeResolver` for rung 1, which retries forcing HTTP/1.1.
     */
    internal fun derive(configure: OkHttpClient.Builder.() -> Unit): StoreHttpClient =
        StoreHttpClient(
            storeId = storeId,
            userAgent = userAgent,
            client = client.newBuilder().apply(configure).build(),
            rateLimiter = rateLimiter,
            tierRecorder = tierRecorder,
            escalator = escalator,
            requestLog = requestLog,
            timeSource = timeSource,
        )

    private companion object {
        /**
         * No caching for large files.
         *
         * The HTTP cache is ~50 MB and the F-Droid index is 57 uncompressed: letting it in would
         * evict everything else and still fail to keep it. The index's freshness is decided by
         * `entry.json`, not by headers.
         */
        val NO_STORE: CacheControl = CacheControl.Builder().noStore().build()
    }
}
