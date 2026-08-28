package com.multistore.core.common.net

import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How a call to a store failed, reduced to what decides the reaction. */
enum class FailureKind {
    /** Network, timeout, 5xx. Counts in the window; does not trip on its own. */
    TRANSIENT,

    /** 429 or equivalent: trips immediately, honouring `Retry-After`. */
    RATE_LIMITED,

    /** Challenge, captcha, 403: trips immediately. Insisting makes it worse. */
    BLOCKED,

    /** The markup is not what was expected. Does not trip: degrades after several distinct hits. */
    PARSE,

    /** The app is not there. Not a store failure. */
    NOT_FOUND,
}

/**
 * A store's health, in a form that survives the death of the process.
 *
 * A plain record, because the breaker's state lives in Room: state made of in-memory timers
 * would reset on every restart — precisely when it is needed most, since an app restarting after
 * being killed is an app about to redo every request at once.
 */
data class StoreHealth(
    val storeId: StoreId,
    val state: StoreHealthState = StoreHealthState.CLOSED,
    /** Until when the breaker stays open. `null` if it is not open. */
    val openUntil: Instant? = null,
    /** How many times in a row it has reopened: the backoff exponent. */
    val consecutiveOpenCycles: Int = 0,
    val windowStart: Instant? = null,
    val windowCalls: Int = 0,
    val windowFailures: Int = 0,
    /**
     * The selectors that failed to parse, distinct.
     *
     * Distinct and not counted: a malformed page can turn up a hundred times for the same
     * selector without the parser being broken. Three *different* selectors failing says
     * something else, namely that the markup changed.
     */
    val parseFailureSelectors: Set<String> = emptySet(),
    val lastSuccessAt: Instant? = null,
) {
    /** `true` if the store answers but its results must be flagged as partial. */
    val isDegraded: Boolean get() = state == StoreHealthState.DEGRADED
}

/**
 * The circuit breaker's state machine, as pure functions.
 *
 * No coroutines, no internal clock, no hidden state: a [StoreHealth] goes in and a [StoreHealth]
 * comes out. The caller persists it. It is the only way to test "what happens after 61 minutes"
 * without waiting 61.
 */
object CircuitBreakerPolicy {

    /** Failures within the window beyond which it trips. */
    const val FAILURE_THRESHOLD: Int = 5

    /** Minimum calls for the failure rate to be meaningful. */
    const val MIN_CALLS_FOR_RATE: Int = 10

    const val FAILURE_RATE_THRESHOLD: Double = 0.5

    /** Distinct failed selectors beyond which the store counts as degraded. */
    const val PARSE_FAILURES_FOR_DEGRADED: Int = 3

    val WINDOW: Duration = 60.seconds
    val INITIAL_OPEN: Duration = 5.minutes
    val MAX_OPEN: Duration = 60.minutes

    /** `true` if a call may be attempted now. In `HALF_OPEN` a single probe gets through. */
    fun canAttempt(health: StoreHealth, now: Instant): Boolean = when (health.state) {
        StoreHealthState.CLOSED, StoreHealthState.DEGRADED -> true
        StoreHealthState.HALF_OPEN -> true
        StoreHealthState.OPEN -> health.openUntil == null || now >= health.openUntil
    }

    /** Expires the opening: `OPEN` past its deadline becomes `HALF_OPEN`. */
    fun refreshed(health: StoreHealth, now: Instant): StoreHealth =
        if (health.state == StoreHealthState.OPEN && health.openUntil != null && now >= health.openUntil) {
            health.copy(state = StoreHealthState.HALF_OPEN, openUntil = null)
        } else {
            health
        }

    fun onSuccess(health: StoreHealth, now: Instant): StoreHealth = health.copy(
        state = StoreHealthState.CLOSED,
        openUntil = null,
        consecutiveOpenCycles = 0,
        windowStart = now,
        windowCalls = 0,
        windowFailures = 0,
        parseFailureSelectors = emptySet(),
        lastSuccessAt = now,
    )

    /**
     * Records a failure and returns the new state.
     *
     * [retryAfter] comes from the store (the `Retry-After` header) and takes precedence over the
     * computed backoff: if the server says how long to wait, waiting less is rude and waiting
     * more is waste.
     */
    fun onFailure(
        health: StoreHealth,
        kind: FailureKind,
        now: Instant,
        retryAfter: Duration? = null,
        selector: String? = null,
    ): StoreHealth {
        if (kind == FailureKind.NOT_FOUND) return health

        if (kind == FailureKind.PARSE) {
            val selectors = health.parseFailureSelectors + (selector ?: UNKNOWN_SELECTOR)
            val degraded = selectors.size >= PARSE_FAILURES_FOR_DEGRADED
            return health.copy(
                state = if (degraded && health.state == StoreHealthState.CLOSED) {
                    StoreHealthState.DEGRADED
                } else {
                    health.state
                },
                parseFailureSelectors = selectors,
            )
        }

        if (kind == FailureKind.BLOCKED || kind == FailureKind.RATE_LIMITED) {
            return opened(health, now, retryAfter)
        }

        // TRANSIENT: counts within the sliding window.
        val windowExpired = health.windowStart == null || now - health.windowStart >= WINDOW
        val start = if (windowExpired) now else health.windowStart
        val calls = if (windowExpired) 1 else health.windowCalls + 1
        val failures = if (windowExpired) 1 else health.windowFailures + 1
        val tripped = failures >= FAILURE_THRESHOLD ||
            (calls >= MIN_CALLS_FOR_RATE && failures.toDouble() / calls > FAILURE_RATE_THRESHOLD)
        val counted = health.copy(windowStart = start, windowCalls = calls, windowFailures = failures)
        return if (tripped) opened(counted, now, retryAfter) else counted
    }

    /** Records a call for rate purposes only, without closing the breaker. */
    fun onCall(health: StoreHealth, now: Instant): StoreHealth {
        val windowExpired = health.windowStart == null || now - health.windowStart >= WINDOW
        return if (windowExpired) {
            health.copy(windowStart = now, windowCalls = 1, windowFailures = 0)
        } else {
            health.copy(windowCalls = health.windowCalls + 1)
        }
    }

    private fun opened(health: StoreHealth, now: Instant, retryAfter: Duration?): StoreHealth {
        val cycles = health.consecutiveOpenCycles + 1
        val backoff = retryAfter ?: exponentialBackoff(cycles)
        return health.copy(
            state = StoreHealthState.OPEN,
            openUntil = now + backoff,
            consecutiveOpenCycles = cycles,
            windowStart = null,
            windowCalls = 0,
            windowFailures = 0,
        )
    }

    /** 5 min, 10, 20, 40, then the 60-minute ceiling. */
    fun exponentialBackoff(cycle: Int): Duration {
        if (cycle <= 1) return INITIAL_OPEN
        val shift = (cycle - 1).coerceAtMost(MAX_SHIFT)
        val scaled = INITIAL_OPEN * (1L shl shift).toDouble()
        return if (scaled > MAX_OPEN) MAX_OPEN else scaled
    }

    private const val MAX_SHIFT = 8
    private const val UNKNOWN_SELECTOR = "?"
}
