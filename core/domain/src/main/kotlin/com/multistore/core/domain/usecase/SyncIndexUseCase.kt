package com.multistore.core.domain.usecase

import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.IndexSyncProgress
import com.multistore.core.data.repository.IndexSyncReport
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.domain.NetworkConditions
import com.multistore.core.model.StoreId
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * A sync request's outcome.
 *
 * [NeedsMeteredConsent] is not an error and it matters that it does not become one: it is the chosen
 * behaviour — "automatic on a non-metered network, with explicit confirmation if metered" — and the
 * UI has to react to it with a question, not with a red banner. Modelling it as `Failure` would have
 * worked and would have been wrong in a way hard to correct afterwards, because every caller would
 * have learned to treat it as a fault.
 */
sealed interface SyncRequestOutcome {

    data class Completed(val report: IndexSyncReport) : SyncRequestOutcome

    /** The user's consent is needed: the network is metered and the setting does not allow it. */
    data class NeedsMeteredConsent(val storeId: StoreId, val estimatedBytes: Long?) : SyncRequestOutcome

    data class Failed(val error: com.multistore.core.common.result.AppError) : SyncRequestOutcome
}

/**
 * Syncs a store's index, respecting the user's choice about metered networks.
 *
 * @see SyncRequestOutcome.NeedsMeteredConsent
 */
class SyncIndexUseCase @Inject constructor(
    private val index: StoreIndexRepository,
    private val settings: SettingsRepository,
    private val network: NetworkConditions,
) {

    /**
     * @param userConsented the user has already said yes for **this** sync. It counts once and is not
     * stored: consenting to download 18 MB now is not consenting to do it every week, and turning a
     * one-off consent into a permanent setting is the classic way of making the user pay for something
     * they did not ask for.
     */
    suspend operator fun invoke(
        storeId: StoreId,
        force: Boolean = false,
        userConsented: Boolean = false,
        onProgress: (IndexSyncProgress) -> Unit = {},
    ): SyncRequestOutcome {
        if (!userConsented && !settings.network.first().meteredNetworkAllowed && network.isMetered()) {
            return SyncRequestOutcome.NeedsMeteredConsent(
                storeId = storeId,
                // How much it will really cost is known only to the store, and to know it one would
                // have to open the index — i.e. start downloading. What can be said beforehand is how
                // much it cost last time, which is the honest estimate available.
                estimatedBytes = null,
            )
        }
        return when (val outcome = index.sync(storeId, force = force, onProgress = onProgress)) {
            is Outcome.Success -> SyncRequestOutcome.Completed(outcome.value)
            is Outcome.Failure -> SyncRequestOutcome.Failed(outcome.error)
        }
    }
}
