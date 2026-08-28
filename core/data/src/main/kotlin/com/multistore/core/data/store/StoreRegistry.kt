package com.multistore.core.data.store

import com.multistore.core.model.StoreId
import com.multistore.store.api.IndexedStoreAdapter
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreAdapter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The adapters the application has really wired, indexed by [StoreId].
 *
 * It is the only point where the `Set` produced by Hilt's multibinding becomes something queryable.
 * The architectural constraint stays intact: no concrete store's name appears in here, and
 * `checkDependencyRules` verifies that `:core:*` does not depend on a `:store:<name>`. The adapters
 * are registered by `:app` with `@IntoSet`.
 *
 * The duplicate check is not zeal: two `@IntoSet`s declaring the same [StoreId] would give a `Set` of
 * two elements in which the second is invisible — a search querying a phantom store, with no error
 * anywhere.
 */
@Singleton
class StoreRegistry @Inject constructor(
    adapters: Set<@JvmSuppressWildcards StoreAdapter>,
) {

    private val byId: Map<StoreId, StoreAdapter> = adapters
        .groupBy { it.id }
        .mapValues { (id, sharing) ->
            require(sharing.size == 1) {
                "More than one adapter declares $id: ${sharing.map { it::class.java.simpleName }}. " +
                    "Exactly one @IntoSet per store."
            }
            sharing.single()
        }

    /** In [StoreId] order, so that two runs give the same results. */
    val all: List<StoreAdapter> = byId.entries.sortedBy { it.key.ordinal }.map { it.value }

    fun adapter(storeId: StoreId): StoreAdapter? = byId[storeId]

    /**
     * The adapter as an indexed store, if it is one.
     *
     * The two conditions have to be checked together: implementing [IndexedStoreAdapter] without
     * declaring [SearchSource.LOCAL_INDEX] would mean having an index and searching over the network
     * anyway, and declaring it without implementing it would mean a search answering nobody. The
     * contract test checks the consistency; here the branch is chosen.
     */
    fun indexed(storeId: StoreId): IndexedStoreAdapter? = byId[storeId]
        ?.takeIf { it.capabilities.searchSource == SearchSource.LOCAL_INDEX }
        as? IndexedStoreAdapter

    val indexedStores: List<IndexedStoreAdapter> = all.mapNotNull { indexed(it.id) }
}
