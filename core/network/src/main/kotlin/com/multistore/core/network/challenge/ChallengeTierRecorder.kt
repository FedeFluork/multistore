package com.multistore.core.network.challenge

import com.multistore.core.model.StoreId

/**
 * Where the ladder rung a request actually got through on ends up.
 *
 * The rung reached is recorded in `health_events` for diagnosis. That data has always existed —
 * [ChallengeOutcome.Passed.tier] — but had no channel: `health_events` lives in Room, and an
 * adapter is stateless and cannot see `:core:data`.
 *
 * This interface is that channel, shaped so that it **forces no adapter to change**: it is
 * implemented by `:core:data`, received by `StoreHttpClients` — which every adapter already gets
 * injected — and called by `PageFetcher`, through the store's client, which already knows its own
 * [StoreId].
 *
 * **Only rungs above zero are recorded.** Rung 0 is the ordinary request: the case for nearly
 * every request of nearly every store, and writing a row for it would fill the diagnostics table
 * with the news that nothing happened. What matters to whoever reads the diagnostics is the
 * opposite: **when** and **for which store** climbing was needed.
 */
fun interface ChallengeTierRecorder {

    /** [tier] is the rung the request got through on: always `> 0` by the time it reaches here. */
    fun record(storeId: StoreId, tier: Int)

    companion object {
        /** The default: nobody is listening. Used by tests that do not look at diagnostics. */
        val NONE: ChallengeTierRecorder = ChallengeTierRecorder { _, _ -> }
    }
}
