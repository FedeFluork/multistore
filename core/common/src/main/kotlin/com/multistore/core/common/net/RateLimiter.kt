package com.multistore.core.common.net

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A per-host token bucket, with injectable clock and sleep function.
 *
 * Every request passes through the rate limiter. This is the engine; the interceptor that applies
 * it lives in `:core:network`. It sits in `:core:common` because it is pure logic and must be
 * tested without real time passing: [sleep] exists only for that.
 *
 * The bucket refills continuously rather than in steps: with `permitsPerSecond = 1` and
 * `burst = 3` three requests can go out at once and then one per second, which is exactly the
 * traffic shape a site expects from a polite client.
 */
class RateLimiter(
    private val permitsPerSecond: Double,
    private val burst: Int,
    private val clock: Clock = Clock.System,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive" }
        require(burst >= 1) { "burst must be at least 1" }
    }

    private val mutex = Mutex()
    private var tokens: Double = burst.toDouble()
    private var lastRefillNanos: Long = clock.now().let { it.epochSeconds * NANOS_PER_SECOND + it.nanosecondsOfSecond }

    /** Suspends until a permit is available, then consumes it. */
    suspend fun acquire() {
        val wait = mutex.withLock { refillAndReserve() }
        if (wait > Duration.ZERO) sleep(wait)
    }

    /** Consumes a permit if one is immediately available; never waits. */
    suspend fun tryAcquire(): Boolean = mutex.withLock {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    /** How many permits are available now. Exposed for tests and diagnostics. */
    suspend fun availablePermits(): Double = mutex.withLock {
        refill()
        tokens
    }

    private fun refillAndReserve(): Duration {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            Duration.ZERO
        } else {
            // Debt: the permit is reserved and the time needed to earn it is waited out, so two
            // concurrent callers queue up instead of waking together.
            val missing = 1.0 - tokens
            tokens -= 1.0
            (missing / permitsPerSecond).seconds
        }
    }

    private fun refill() {
        val now = clock.now().let { it.epochSeconds * NANOS_PER_SECOND + it.nanosecondsOfSecond }
        val elapsedNanos = now - lastRefillNanos
        if (elapsedNanos <= 0) return
        lastRefillNanos = now
        val gained = elapsedNanos.toDouble() / NANOS_PER_SECOND * permitsPerSecond
        tokens = minOf(burst.toDouble(), tokens + gained)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
