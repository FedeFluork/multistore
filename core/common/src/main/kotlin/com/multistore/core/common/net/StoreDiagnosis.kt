package com.multistore.core.common.net

import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import kotlin.time.Instant

/**
 * What is wrong with one store, in enough detail to decide what to do about it.
 *
 * ### The question the two words could not answer
 *
 * `health_events` has recorded faults since M0 and, since M5, can be exported. Inside the app it
 * came down to one word beside the store's name in Settings — `OPEN` or `DEGRADED` — and that word
 * cannot answer the only question a person actually has: **is this today, or has it been going on
 * for a week?** One is worth waiting out; the other is worth switching the store off.
 *
 * ### Why [failingSince] is anchored on the last success
 *
 * It is the earliest fault **after** the last success, not the oldest fault in the table. A store
 * that answered an hour ago and has broken since is not a store that has been broken all week, even
 * though last week's fault is still recorded — and the second reading is the one that would make
 * somebody turn off a store that works.
 *
 * ### And why `NOT_FOUND` is not among the faults
 *
 * "That app is not on this store" is a store answering correctly, and the circuit breaker already
 * treats it that way — it does not count towards opening. Letting it into this would make a store
 * look broken for having been asked about something it does not have.
 */
data class StoreDiagnosis(
    val storeId: StoreId,
    val state: StoreHealthState = StoreHealthState.CLOSED,
    /** When it last answered. `null` where it never has — including a store never queried. */
    val lastSuccessAt: Instant? = null,
    val lastFailure: StoreFault? = null,
    /** When the current run of faults began, or `null` if there is not one. */
    val failingSince: Instant? = null,
    /** Until when the breaker stays open, if it is. */
    val openUntil: Instant? = null,
    /**
     * The distinct selectors that failed to parse.
     *
     * Distinct and not counted, for the reason written on `StoreHealth`: one malformed page can
     * produce the same selector a hundred times without the parser being broken, while three
     * *different* selectors failing says the markup changed. On this screen it is the difference
     * between "wait" and "this store needs its parsers fixed".
     */
    val parseFailureSelectors: Set<String> = emptySet(),
) {
    /** `true` when there is something to explain. A healthy store's dialog would be an empty page. */
    val hasFaults: Boolean get() = lastFailure != null || state != StoreHealthState.CLOSED
}

/** One recorded fault: what kind, when, and — for a parse failure — which selector. */
data class StoreFault(
    val kind: FailureKind,
    val at: Instant,
    val selector: String? = null,
)
