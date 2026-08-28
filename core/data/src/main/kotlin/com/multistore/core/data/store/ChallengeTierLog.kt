package com.multistore.core.data.store

import com.multistore.core.common.coroutine.ApplicationScope
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.network.challenge.ChallengeTierRecorder
import com.multistore.core.model.StoreId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The escalation ladder's rung, written into `health_events`.
 *
 * The data existed from the start and had nowhere to go. Here is `:core:data`'s end of the channel:
 * at the other end is `ChallengeTierRecorder`, which lives in `:core:network` because it is the only
 * module both `:store:common` and this one can see.
 *
 * ### The `Provider`, which is not laziness
 *
 * `StoreHealthRepository` depends on `StoreRegistry`, which depends on the adapters, which depend on
 * `StoreHttpClients`, which depends on this recorder: it is a **cycle**, and Dagger refuses it at
 * compile time. The `Provider` breaks it, and it is honest to do so here: the repository is needed
 * only at the moment a request has gone through, i.e. long after the graph has been built.
 *
 * ### Why it does not suspend
 *
 * The caller is `PageFetcher`, inside a network request's path. Making that path suspend for a
 * diagnostics write would mean slowing a search down to record how it went: the write starts on the
 * application scope and nobody waits for it. If it is lost — process killed in the meantime — a line
 * of diagnostics is lost, which is exactly the level of importance it has.
 */
@Singleton
class ChallengeTierLog @Inject constructor(
    private val health: Provider<StoreHealthRepository>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : ChallengeTierRecorder {

    override fun record(storeId: StoreId, tier: Int) {
        scope.launch { health.get().recordEvent(storeId, kind = EVENT_KIND, tier = tier) }
    }

    private companion object {
        /** The `kind` under which the event appears in diagnostics. */
        const val EVENT_KIND = "challenge_tier"
    }
}
