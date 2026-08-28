package com.multistore.core.data.repository

import androidx.paging.PagingData
import com.multistore.core.common.result.AppError
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.FilterCapability
import com.multistore.store.api.SearchFilters
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * Why a store's results are missing or incomplete.
 *
 * [retryIn] is present only when the breaker is open, and it is the difference between a useful sign
 * and a useless one: "apkmirror unavailable" leaves the user wondering whether it is worth insisting,
 * "apkmirror unavailable, retrying in 4 minutes" does not.
 *
 * It is a **duration**, measured when the search started, and not the reopening instant. That is not
 * an imprecision: that way the screen stays a pure function of its own state, and is therefore
 * drawable in a screenshot test without the golden depending on what time it was. The price is that
 * the number does not tick down by itself — and it must not: next to it there is "search again".
 */
data class StoreShortfall(
    val storeId: StoreId,
    val error: AppError?,
    /** `true` when the breaker is open: we did not even try, and that is right. */
    val circuitOpen: Boolean = false,
    /** `true` when the results are there but partial by construction. */
    val partial: Boolean = false,
    /** How long until the breaker reopens by itself. Only meaningful with [circuitOpen]. */
    val retryIn: Duration? = null,
    /**
     * The active filters this store cannot apply, and for which it **was not queried**.
     *
     * It is [com.multistore.store.api.FilterPlan]'s third rung, and the difference from the other
     * shortfalls is that here nothing went wrong: the store is perfectly fine, it is the question it
     * cannot handle. The screen says so in different words, because the remedy is different too —
     * remove the filter, not retry.
     *
     * Empty in every other case.
     */
    val unsupportedFilters: Set<FilterCapability> = emptySet(),
)

/**
 * An aggregated page of results.
 *
 * [apps] and not `results`: the rows are **groups**, not listings. The name changed when the content
 * did, deliberately — a list going from "every listing from every store" to "one app per row"
 * without changing name is the most efficient way of making its consumers count the same apps twice.
 *
 * [shortfalls] is the part that makes the aggregation honest: with nine stores queried together the
 * normal case is not "all fine" or "all broken", and a list that does not say which sources are
 * missing makes an incomplete result look complete.
 */
data class SearchPage(
    val apps: List<AggregatedApp>,
    val page: Int,
    val hasMore: Boolean,
    val shortfalls: List<StoreShortfall> = emptyList(),
)

/**
 * A search **in progress**: what is known now, and who is still being waited for.
 *
 * The result is a flow emitting partial results as each store answers. With 9 stores, waiting for
 * the slowest would be unacceptable, and the spread is already wide: F-Droid answers from the local
 * index in milliseconds, apkmirror declares `Crawl-delay: 3` and answers 429 to whoever ignores it.
 *
 * [pending] is not diagnostics: it is what lets the screen say "3 stores out of 5" and the viewer
 * understand the list has not finished filling up — which is also the only honest answer to the fact
 * that the ordering is recomputed on every arrival.
 */
data class SearchProgress(
    val page: SearchPage,
    val answered: Set<StoreId>,
    val pending: Set<StoreId>,
) {
    val complete: Boolean get() = pending.isEmpty()

    val queried: Int get() = answered.size + pending.size
}

interface SearchRepository {

    /**
     * Searches [storeIds], or every enabled store if the set is empty, emitting **every time a store
     * answers**.
     *
     * The last emission is the complete page. It never throws: a store that fails becomes a
     * [StoreShortfall], not an exception dragging the other eight down with it.
     */
    fun searchStreaming(
        query: String,
        storeIds: Set<StoreId> = emptySet(),
        page: Int = 0,
        filters: SearchFilters = SearchFilters.NONE,
    ): Flow<SearchProgress>

    /**
     * The same search, waited out to the end.
     *
     * It is [searchStreaming]'s last emission and not a second path: two implementations of the same
     * fusion would end up answering differently, and the difference would only show on the second
     * page.
     */
    suspend fun search(
        query: String,
        storeIds: Set<StoreId> = emptySet(),
        page: Int = 0,
        filters: SearchFilters = SearchFilters.NONE,
    ): SearchPage

    /** The recently updated apps according to the local index: it feeds the Home with no network. */
    suspend fun recentlyUpdated(storeId: StoreId, page: Int = 0): List<StoreListingSummary>

    /**
     * Browses a store's local index, in alphabetical order.
     *
     * [categoryId] `null` means **the whole catalogue**, and that is not a degenerate case: without
     * it, the only way of reaching the 4,269 already downloaded packages would be to know in advance
     * what one is looking for. The two variants live in a single method because the difference
     * between them is a `WHERE` clause, and separating them would give two pagination paths to keep
     * aligned.
     */
    suspend fun browse(storeId: StoreId, categoryId: String?, page: Int = 0): List<StoreListingSummary>

    /**
     * The same catalogue as [browse], as a paged flow.
     *
     * ### Why here and not in the ViewModel
     *
     * The `Pager` is built by the repository because the **page size is a property of the
     * repository** — it is the same reason `browse` returns a `CataloguePage` and not a list.
     * Building it in the ViewModel would mean every screen browsing the catalogue chooses its own,
     * and that changing [PAGE_SIZE] changes nothing.
     *
     * ### Why only the catalogue, and neither the search nor "My apps"
     *
     * The plan said "Paging 3 on the local catalogue, 'My apps' and the version history", and the
     * measurement of 26/08/2026 leaves **one** standing:
     *
     * | surface | rows | outcome |
     * |---|---|---|
     * | a store's local catalogue | **4,280** (f-droid) | Paging |
     * | a listing's version history | average **3**, maximum **26**, none above 50 | no |
     * | "My apps" | only what MultiStore installed — dozens | no |
     *
     * The history is not a long list: it is a list that fits in two screens. And "My apps" has one
     * more reason, stronger than the number — the screen **counts** how many rows have an update
     * (`Ready.updatable`), and a count over a paged list does not exist: either everything is kept,
     * or a second query is written saying a number the list does not show.
     *
     * The aggregated search stays out for a structural reason: `PagingSource` is *pull-based* and
     * assumes a stable source, while the fan-out over nine stores **reorders the list at every
     * answer that arrives**.
     */
    fun browsePaged(storeId: StoreId, categoryId: String?): Flow<PagingData<StoreListingSummary>>

    companion object {
        const val PAGE_SIZE: Int = 30
    }
}
