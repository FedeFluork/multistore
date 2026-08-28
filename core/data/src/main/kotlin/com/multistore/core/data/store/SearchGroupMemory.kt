package com.multistore.core.data.store

import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the **last search** discovered, kept in memory for the detail screen.
 *
 * ### Why it exists
 *
 * The detail screen wants to say "available on 3 stores". The three ways of knowing that are: reading
 * it from Room — which however only knows the listings already opened once —, asking the stores when
 * the listing is opened, or remembering what the search has just seen.
 *
 * **The second is ruled out**, and not out of generic caution: no mass crawling, no speculative
 * prefetch. Querying four third-party sites every time a listing is opened is exactly a speculative
 * prefetch — the user asked for *that* app on *that* store, not for a census.
 *
 * The third instead costs nothing: the search **has already made** those requests, and they were
 * requests the user asked for by typing a query. Remembering their result adds no traffic, it adds
 * memory.
 *
 * ### What it is not
 *
 * It is not a cache with a duration: **it holds only the last search** and replaces it with the next.
 * The limit is not an implementation choice but the right semantics — "what you are looking at now" —
 * and it is also the simplest way of keeping it bounded without an eviction policy. On a process
 * restart it is lost, and then the listing offers the "search other stores" button, which is the same
 * thing but asked for.
 *
 * The key is **the single listing**, not the aggregated app: whoever opens the detail has a `storeId`
 * and a ref to hand, and cannot know the group's key before Room tells them.
 */
@Singleton
class SearchGroupMemory @Inject constructor() {

    private val groups = MutableStateFlow<Map<Key, AggregatedApp>>(emptyMap())

    /** Replaces what is remembered with the outcome of the search just completed. */
    fun remember(apps: List<AggregatedApp>) {
        // Only the groups with more than one store: a group of one says nothing the listing does not
        // already know, and keeping it would double the occupancy for zero information.
        groups.value = buildMap {
            for (app in apps.filter { it.storeCount > 1 }) {
                for (listing in app.listings) put(Key(listing.storeId, listing.ref.value), app)
            }
        }
    }

    fun observe(storeId: StoreId, ref: StoreAppRef): Flow<AggregatedApp?> =
        groups.asStateFlow().map { it[Key(storeId, ref.value)] }.distinctUntilChanged()

    /** The value now, for whoever is not observing but is about to make a network request. */
    fun snapshot(storeId: StoreId, ref: StoreAppRef): AggregatedApp? =
        groups.value[Key(storeId, ref.value)]

    private data class Key(val storeId: StoreId, val ref: String)
}
