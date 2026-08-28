package com.multistore.core.data.repository

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.remoteconfig.FetchAttempt
import com.multistore.core.remoteconfig.IndexDocument
import com.multistore.core.remoteconfig.IndexEntry
import com.multistore.core.remoteconfig.IndexFetcher
import com.multistore.core.remoteconfig.RemoteConfigFetcher
import com.multistore.core.remoteconfig.RemoteIndexStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The two Home sections that do not come from the local catalogue.
 *
 * Empty when there is no index — first launch, unreachable CDN, switch off — and in that case the
 * Home does not draw them at all. It is the non-negotiable remote-config rule seen from the UI's
 * side: the document's absence is a normal state, not an error to show.
 */
data class HomeIndex(
    val popular: List<StoreListingSummary> = emptyList(),
    val recent: List<StoreListingSummary> = emptyList(),
    /** The stores the pipeline could not query, with the reason. */
    val unreachableStores: Map<StoreId, String?> = emptyMap(),
    val generatedAt: Instant? = null,
) {
    val isEmpty: Boolean get() = popular.isEmpty() && recent.isEmpty()
}

/**
 * What the Home can know and ask about the remote index.
 *
 * It sits next to [RemoteConfigRepository] and for the same reason: the switch lives in the
 * DataStore, the document in `:core:remoteconfig`, and somebody has to hold the two together. Here
 * there is a third piece — which stores the user has left on — which lives in Room.
 */
interface RemoteIndexRepository {

    /** The two sections, already filtered against what the user has left switched on. */
    val index: Flow<HomeIndex>

    /** Downloads if enough time has passed, **and** if the user has not forbidden it. */
    suspend fun refreshIfStale(): FetchAttempt?

    /** Asks now. It respects the switch all the same, like the parsers' button. */
    suspend fun refreshNow(): FetchAttempt?
}

@Singleton
internal class RemoteIndexRepositoryImpl @Inject constructor(
    private val store: RemoteIndexStore,
    @param:IndexFetcher private val fetcher: RemoteConfigFetcher,
    private val settings: SettingsRepository,
    private val stores: StoreHealthRepository,
) : RemoteIndexRepository {

    /**
     * The translated document, **minus the switched-off stores**.
     *
     * The filter is not cosmetic: the Home would otherwise offer an app from a store the user has
     * disabled, and touching it would open a listing the search would never show them. It is the same
     * `stores.enabled` column `SearchRepository` reads — a single source of truth, as the note in
     * `SettingsRegistry` explains about why that column does not live in the DataStore.
     */
    override val index: Flow<HomeIndex> =
        combine(store.document, stores.observeStores()) { document, catalogue ->
            val enabled = catalogue.filter { it.enabled }.map { it.storeId }.toSet()
            document?.toHome(enabled) ?: HomeIndex()
        }

    override suspend fun refreshIfStale(): FetchAttempt? =
        if (blocked()) null else fetcher.refreshIfStale()

    override suspend fun refreshNow(): FetchAttempt? =
        if (blocked()) null else fetcher.refresh()

    private suspend fun blocked(): Boolean = settings.remoteConfig.first().blockRemoteIndex
}

/**
 * From document to domain.
 *
 * An entry from a store this version of the app does not know is **discarded**, not made to fail:
 * the pipeline can publish a tenth store before the app can read it, and in that case the right thing
 * is to show the other nine.
 */
internal fun IndexDocument.toHome(enabledStores: Set<StoreId>): HomeIndex = HomeIndex(
    // `distinctBy` on the `(store, ref)` pair, and it is not generic caution: it is what makes the
    // key of the `LazyRow` drawing them true. Before using a domain identifier as a list's key,
    // verify it is unique *in that list* — and the only place that can guarantee it is whoever builds
    // the list. The pipeline should never publish two identical entries; if it did, the symptom would
    // be `IllegalArgumentException` and the app closing, not a duplicate row.
    popular = popular.mapNotNull { it.toSummary(enabledStores) }.distinctBy { it.storeId to it.ref },
    recent = recent.mapNotNull { it.toSummary(enabledStores) }.distinctBy { it.storeId to it.ref },
    unreachableStores = stores
        .filterNot { it.reachable }
        .mapNotNull { state -> StoreId.fromWireNameOrNull(state.store)?.let { it to state.detail } }
        .toMap(),
    generatedAt = generatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
)

private fun IndexEntry.toSummary(enabledStores: Set<StoreId>): StoreListingSummary? {
    val storeId = StoreId.fromWireNameOrNull(store) ?: return null
    if (storeId !in enabledStores) return null
    if (ref.isBlank() || title.isBlank()) return null
    return StoreListingSummary(
        storeId = storeId,
        ref = StoreAppRef(ref),
        title = title,
        packageName = packageName,
        developer = developer,
        iconUrl = iconUrl,
        latestVersionName = version,
        lastUpdated = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

/** The same translation, for whoever already has the document. Used by the tests and the worker. */
internal fun RemoteIndexStore.homeIndex(enabledStores: Set<StoreId>): Flow<HomeIndex> =
    document.map { it?.toHome(enabledStores) ?: HomeIndex() }
