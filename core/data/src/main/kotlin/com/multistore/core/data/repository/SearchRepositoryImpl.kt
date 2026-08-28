package com.multistore.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.identity.AppAggregator
import com.multistore.core.common.identity.StoreResults
import com.multistore.core.common.result.AppError
import com.multistore.core.common.text.TextNormalizer
import com.multistore.core.data.mapper.toAppError
import com.multistore.core.data.mapper.toSummary
import com.multistore.core.data.store.EnabledStores
import com.multistore.core.data.store.SearchGroupMemory
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.IndexDao
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.FilterCapability
import com.multistore.store.api.FilterPlan
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The aggregated search, and the **two** branches crossing it.
 *
 * The branching is not a single one, and it is worth writing down: search and detail both fork on
 * the same capability, and each does it in its own way. Here is the first of the two.
 *
 *  - **`REMOTE`** — every search is an HTTP request, so it goes through the circuit breaker: if the
 *    store is open we do not even try, and its absence is declared as a [StoreShortfall].
 *  - **`LOCAL_INDEX`** — the search queries Room. It does not touch the network, so it
 *    **deliberately bypasses the breaker**: an open breaker means "that store is not answering", and
 *    here there is nobody to ask. Preventing the local search because the site is down would be the
 *    exact opposite of what the local index exists for.
 *
 * The third case is the easiest to forget: a local-index store whose index **has not yet been
 * downloaded**. There we do not return an empty list — which the user would read as "this app does
 * not exist" — but use the adapter's fallback search, marking the results [ResultOrigin.BOOTSTRAP]
 * because they are few and incomplete by construction.
 *
 * ### Streaming, and the two things it makes necessary
 *
 * The stores do not answer together and do not even try to: F-Droid reads Room, apkmirror waits out
 * its three-second `Crawl-delay`. Waiting for the slowest to show the first result would be, with
 * nine stores, a search that looks broken.
 *
 * Two consequences follow that are not implementation details:
 *
 *  1. **the timeout is per store** ([SearchSettings.storeTimeout]), not per search. An adapter that
 *     does not return becomes a shortfall next to the others' results, not a shared wait. The value
 *     is decided by the user, with the compiled default of
 *     [SearchSettings.DEFAULT_STORE_TIMEOUT] when they have chosen nothing;
 *  2. **every emission is a complete state**, recomputed from scratch. An app's group can therefore
 *     gain a store — and with it a position — while the list is already on screen. It is
 *     [AppAggregator]'s declared price: it moves, it does not disappear.
 */
@Singleton
internal class SearchRepositoryImpl @Inject constructor(
    private val registry: StoreRegistry,
    private val enabledStores: EnabledStores,
    private val settings: SettingsRepository,
    private val memory: SearchGroupMemory,
    private val catalogDao: CatalogDao,
    private val indexDao: IndexDao,
    private val health: StoreHealthRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SearchRepository {

    override fun searchStreaming(
        query: String,
        storeIds: Set<StoreId>,
        page: Int,
        filters: SearchFilters,
    ): Flow<SearchProgress> = flow {
        val normalized = TextNormalizer.normalizeQuery(query)
        // A single settings read per search, and none below this point: changing a setting halfway
        // through the fan-out would give a page with five stores filtered and two not, or five with
        // one timeout and two with another.
        val searchSettings = settings.search.first()
        val effective = effectiveFilters(filters, searchSettings)
        val candidates = if (normalized.isBlank()) emptyList() else targets(storeIds)
        // Whoever cannot apply an active filter is not queried: see `FilterPlan`. The evaluation has
        // to be made **here**, before the fan-out, because it is also what decides how many requests
        // go out to third-party sites.
        val plans = candidates.map { adapter -> plan(adapter, effective) }
        val targets = plans.filter { it.unsupported.isEmpty() }
        val excluded = plans.filter { it.unsupported.isNotEmpty() }
            .map { StoreShortfall(it.adapter.id, error = null, unsupportedFilters = it.unsupported) }
        if (targets.isEmpty()) {
            emit(
                SearchProgress(
                    page = SearchPage(emptyList(), page, hasMore = false, shortfalls = excluded),
                    answered = emptySet(),
                    pending = emptySet(),
                ),
            )
            return@flow
        }

        // One store, one flow emitting once. `merge` runs them all together and delivers the answers
        // **in order of arrival**; `runningFold` accumulates them.
        //
        // There is neither a lock nor a hand-written channel, and that is not brevity: the fold is
        // the only point touching the state, so two stores answering in the same millisecond cannot
        // produce an emission forgetting one of them. The fold's initial value is also the first
        // emission, the one arriving **before** any request and letting the screen write "0 stores
        // out of 5" instead of staying blank until the fastest answers.
        val answers = targets.map { target ->
            flow {
                val outcome = withTimeoutOrNull(searchSettings.storeTimeout) {
                    searchOne(target, normalized, query, page, effective)
                } ?: timedOut(target.adapter)
                emit(target.adapter.id to outcome)
            }
        }
        val initial = Fanout(emptyMap(), targets.mapTo(LinkedHashSet()) { it.adapter.id }, excluded)
        emitAll(
            merge(*answers.toTypedArray())
                .runningFold(initial) { state, (storeId, outcome) -> state.with(storeId, outcome) }
                .map { state -> snapshot(state, page, effective.sort) }
                // Only the first page, and only because it is the only one the detail screen can
                // later find again: the subsequent pages are an addition to a list living in the
                // ViewModel, and replacing the memory with them would delete the groups the user is
                // actually looking at.
                .onEach { progress -> if (page == 0) memory.remember(progress.page.apps) },
        )
    }.flowOn(io)

    override suspend fun search(
        query: String,
        storeIds: Set<StoreId>,
        page: Int,
        filters: SearchFilters,
    ): SearchPage = searchStreaming(query, storeIds, page, filters).last().page

    override suspend fun recentlyUpdated(storeId: StoreId, page: Int): List<StoreListingSummary> =
        withContext(io) {
            catalogDao
                .recentlyUpdated(storeId, SearchRepository.PAGE_SIZE, page * SearchRepository.PAGE_SIZE)
                .map { it.toSummary() }
        }

    override suspend fun browse(
        storeId: StoreId,
        categoryId: String?,
        page: Int,
    ): List<StoreListingSummary> = withContext(io) {
        val limit = SearchRepository.PAGE_SIZE
        val offset = page * SearchRepository.PAGE_SIZE
        val rows = when (categoryId) {
            null -> catalogDao.listings(storeId, limit, offset)
            else -> catalogDao.byCategory(storeId, categoryId, limit, offset)
        }
        rows.map { it.toSummary() }
    }

    override fun browsePaged(
        storeId: StoreId,
        categoryId: String?,
    ): Flow<PagingData<StoreListingSummary>> = Pager(
        config = PagingConfig(
            pageSize = SearchRepository.PAGE_SIZE,
            // `enablePlaceholders = false`: placeholders would mean grey rows in the middle of a
            // list, and to know their number Room does a `COUNT(*)` over 4,280 rows on every
            // invalidation. A catalogue being scrolled does not need to know how long it is.
            enablePlaceholders = false,
            // Twice the page, which is Paging's default written out: the first read fills more than
            // one screen, so the first scroll does not wait.
            initialLoadSize = SearchRepository.PAGE_SIZE * 2,
        ),
        // A **factory**, not a `PagingSource`: when Room invalidates it — the index resynced while
        // the user was browsing — Paging asks this lambda for a new one. Passing an already
        // constructed one would give a list that empties and never comes back.
        pagingSourceFactory = {
            when (categoryId) {
                null -> catalogDao.listingsPaged(storeId)
                else -> catalogDao.byCategoryPaged(storeId, categoryId)
            }
        },
    ).flow.map { data -> data.map { it.toSummary() } }

    /**
     * The call's filters, **plus** those the user decides in Settings.
     *
     * ### Why here and not in the caller
     *
     * "Show NSFW content" would have been simpler to read in the search's ViewModel and pass in
     * [SearchFilters]. It would also have been the way to lose it: every new caller — the Home, a
     * widget, a test written in six months — would have to remember to read it, and forgetting
     * produces no error at all, only content the user asked not to see. It is the same reason this
     * project's guardrails exist: a rule no test can violate is worth more than a written rule.
     *
     * The setting **overrides** the field instead of combining with it, and the first draft got
     * exactly this wrong: with `filters.includeNsfw && allowed`, a user switching it on would go on
     * seeing nothing, because every normal call passes `SearchFilters.NONE` — i.e. `false`.
     * [SearchFilters]'s field exists for whoever talks directly to an adapter (the contract test, the
     * canary); from here down the user decides.
     *
     * It receives the current value instead of re-reading it: whoever searches has already read it
     * once, for themselves and for the timeout, and a second read could give a different value from
     * the first.
     */
    private fun effectiveFilters(
        filters: SearchFilters,
        searchSettings: SearchSettings,
    ): SearchFilters = filters.copy(includeNsfw = searchSettings.showNsfwContent)

    /**
     * Who has answered and who has not, in a single value: it is the fold's accumulator.
     *
     * [excluded] lives here and **not** in [outcomes] because those stores were not queried: counting
     * them among those that answered would make the screen write "3 stores out of 9" an instant after
     * the start, for three stores nothing was asked of. Their declarations do appear from the
     * **first** emission, though, which is the moment the user wonders where they have gone.
     */
    private data class Fanout(
        val outcomes: Map<StoreId, StoreOutcome>,
        val pending: Set<StoreId>,
        val excluded: List<StoreShortfall>,
    ) {
        fun with(storeId: StoreId, outcome: StoreOutcome) = copy(
            outcomes = outcomes + (storeId to outcome),
            pending = pending - storeId,
        )
    }

    /**
     * The search's state **now**: what has arrived, merged, plus who is missing.
     *
     * The order in which the stores enter [AppAggregator] is [StoreId]'s ordinal and not the order of
     * arrival: otherwise the same search would give different lists depending on who answered first,
     * and two identical runs would not be comparable.
     */
    private fun snapshot(state: Fanout, page: Int, sort: SearchSort): SearchProgress {
        val outcomes = state.outcomes
        val perStore = outcomes.entries
            .sortedBy { it.key.ordinal }
            .map { (storeId, outcome) -> StoreResults(storeId, outcome.origin, outcome.items) }
        return SearchProgress(
            page = SearchPage(
                apps = sorted(AppAggregator.aggregate(perStore), sort),
                page = page,
                hasMore = outcomes.values.any { it.hasMore },
                shortfalls = state.excluded + outcomes.values.mapNotNull { it.shortfall },
            ),
            answered = outcomes.keys.toSet(),
            pending = state.pending.toSet(),
        )
    }

    /**
     * The ordering, which applies to the **aggregate** and not to the store.
     *
     * A per-store order, merged, is not an order: the nine lists arrive each ordered on its own and
     * the result would depend on who answered first. Here instead what is shown is ordered, and it is
     * ordered again on every arrival — which is already streaming's declared cost.
     *
     * The criterion is always a field of `displaySummary`, i.e. **the number the user sees**.
     * Ordering by a value different from the one shown would give a list that looks wrong precisely
     * to whoever is reading it carefully.
     *
     * Whoever has no rating goes to the bottom, not to zero: `null` means "this store does not
     * publish ratings", and treating it as "zero stars" would say something about those apps that
     * nobody said. At the tail they stay in relevance order.
     */
    private fun sorted(apps: List<AggregatedApp>, sort: SearchSort): List<AggregatedApp> =
        when (sort) {
            SearchSort.NAME -> apps.sortedBy { TextNormalizer.normalizeTitle(it.displaySummary.title) }
            SearchSort.RATING -> apps.sortedByDescending { it.displaySummary.rating ?: Float.NEGATIVE_INFINITY }
            // Relevance is the order AppAggregator already produces. The enum's other three values
            // are not offered by the search — see `SearchSort.SELECTABLE` — and falling through here
            // instead of ordering at random is what makes them harmless if one day they arrived from
            // a configuration saved by a future version.
            else -> apps
        }

    /** The stores to query: those requested, or those the user has left switched on. */
    private suspend fun targets(storeIds: Set<StoreId>): List<StoreAdapter> =
        if (storeIds.isEmpty()) enabledStores.adapters() else storeIds.mapNotNull(registry::adapter)

    /**
     * A store with [FilterPlan]'s verdict already computed.
     *
     * [servedByIndex] is the one thing this class knows that `FilterPlan` cannot: whether a
     * local-index store's index has already been downloaded. It costs a `COUNT` and decides two
     * different things — who answers the search, and who can apply the filters — so it is read
     * **once** and carried along. Reading it twice would not just be waste: between the two reads a
     * sync can finish, and the search would decide to filter index-side while the fallback answers.
     */
    private data class Target(
        val adapter: StoreAdapter,
        val servedByIndex: Boolean,
        val unsupported: Set<FilterCapability>,
    )

    private suspend fun plan(adapter: StoreAdapter, filters: SearchFilters): Target {
        val indexed = adapter.capabilities.searchSource == SearchSource.LOCAL_INDEX
        val servedByIndex = indexed && indexDao.entryCount(adapter.id) > 0
        return Target(
            adapter = adapter,
            servedByIndex = servedByIndex,
            unsupported = FilterPlan.unsupported(filters, adapter.capabilities, servedByIndex),
        )
    }

    private data class StoreOutcome(
        val items: List<StoreListingSummary> = emptyList(),
        val origin: ResultOrigin = ResultOrigin.REMOTE,
        val hasMore: Boolean = false,
        val shortfall: StoreShortfall? = null,
    )

    /**
     * A store that did not answer within the chosen timeout.
     *
     * It counts as a **transient** failure for the breaker, and it must: an adapter that hangs every
     * time is exactly what the breaker has to stop querying. It does not go through `recordFailure`
     * with a `StoreError`, though, because a timeout of ours is not an answer from the store — the
     * window is moved by `AppError.Network`, which is what the user sees.
     */
    private fun timedOut(adapter: StoreAdapter) = StoreOutcome(
        shortfall = StoreShortfall(adapter.id, AppError.Network(null)),
    )

    /**
     * The icon **we already know**, for the rows the store does not publish one for.
     *
     * ### The case, measured
     *
     * Eight adapters out of nine put an icon in the search results. The ninth is apkmody, and not out
     * of oversight: its cards' image is a 360x180 **cover**, eighteen times out of twenty the site's
     * placeholder and twice a YouTube frame — putting it in `iconUrl` would be worse than leaving it
     * empty. That store publishes the real icon only on the listing.
     *
     * The result, seen on the device on 27/08/2026 searching "spotify": eight rows with an icon and
     * one — "Spotify X", APKMODY — with the placeholder, **even after opening that listing**. Opening
     * it writes `store_listings`, with the icon inside: we knew it, and we were not using it.
     *
     * ### Why here, and why it costs a query
     *
     * One `SELECT` per store that answered, not one per row, and only for the stores leaving at least
     * one icon empty — so on eight stores out of nine this function does not touch the database. It is
     * the same idea as `InstalledAppsRepository.forListing`, which answers a missing `packageName`
     * with what the APK told us when we installed it: what the app has already seen is not asked of a
     * third-party site again.
     *
     * What it does **not** do: a request. If the catalogue knows nothing about that listing, the row
     * stays with the placeholder — which is the only honest alternative, because the real icon would
     * cost opening that app's page on a third-party site, i.e. the speculative prefetch this project
     * forbids.
     *
     * One limit to know: the icon lives on `apps` and not on `store_listings`, so it belongs to the
     * **aggregated app**. See the note on `CatalogDao.iconsOf`.
     */
    private suspend fun withKnownIcons(
        storeId: StoreId,
        rows: List<StoreListingSummary>,
    ): List<StoreListingSummary> {
        val missing = rows.filter { it.iconUrl == null }
        if (missing.isEmpty()) return rows
        val known = catalogDao.iconsOf(storeId, missing.map { it.ref.value })
            .mapNotNull { row -> row.iconUrl?.let { row.storeAppRef to it } }
            .toMap()
        if (known.isEmpty()) return rows
        return rows.map { row -> row.iconUrl?.let { row } ?: row.copy(iconUrl = known[row.ref.value]) }
    }

    private suspend fun searchOne(
        target: Target,
        normalizedQuery: String,
        rawQuery: String,
        page: Int,
        filters: SearchFilters,
    ): StoreOutcome {
        if (target.servedByIndex) return localSearch(target.adapter.id, normalizedQuery, page, filters)
        val indexed = target.adapter.capabilities.searchSource == SearchSource.LOCAL_INDEX
        return remoteSearch(
            target = target,
            query = rawQuery,
            page = page,
            filters = filters,
            origin = if (indexed) ResultOrigin.BOOTSTRAP else ResultOrigin.REMOTE,
        )
    }

    /**
     * The search over the local index, **filtered by the database**.
     *
     * This function used to receive the filters and not use them: F-Droid declared seven
     * `FilterCapability` and the capability said "the local index applies them", which was the place
     * they were not applied. The predicates now live in the query, which is also the only right
     * place — `LIMIT` runs after `WHERE`, so filtering the returned rows would give pages of random
     * size and a `hasMore` that does not add up.
     */
    private suspend fun localSearch(
        storeId: StoreId,
        query: String,
        page: Int,
        filters: SearchFilters,
    ): StoreOutcome {
        val offset = page * SearchRepository.PAGE_SIZE
        val kind = filters.contentKind
        val minRating = filters.minRating
        val rows = catalogDao.search(
            storeId = storeId,
            query = query,
            limit = SearchRepository.PAGE_SIZE,
            offset = offset,
            kind = kind,
            minRating = minRating,
            orderByName = filters.sort == SearchSort.NAME,
        )
        val total = catalogDao.searchCount(storeId, query, kind, minRating)
        return StoreOutcome(
            items = rows.map { it.toSummary() },
            origin = ResultOrigin.LOCAL_INDEX,
            hasMore = offset + rows.size < total,
        )
    }

    private suspend fun remoteSearch(
        target: Target,
        query: String,
        page: Int,
        filters: SearchFilters,
        origin: ResultOrigin,
    ): StoreOutcome {
        val adapter = target.adapter
        if (!health.canAttempt(adapter.id)) {
            return StoreOutcome(
                origin = origin,
                shortfall = StoreShortfall(
                    storeId = adapter.id,
                    error = null,
                    circuitOpen = true,
                    // Without this the screen can only say "unavailable". With it, it says how long
                    // until it retries by itself, which is the only thing the user can use to decide
                    // whether to wait.
                    retryIn = health.health(adapter.id).openUntil?.let { it - clock.now() }
                        ?.takeIf { it.isPositive() },
                ),
            )
        }
        return when (val result = adapter.search(query, filters, page)) {
            is StoreResult.Success -> {
                health.recordSuccess(adapter.id)
                StoreOutcome(
                    // `FilterPlan`'s second rung: what the store cannot filter but which can be
                    // decided by looking at the row. Whoever gets here does so because the field is on
                    // every row — the contract test checks it against the real fixtures.
                    items = withKnownIcons(
                        adapter.id,
                        FilterPlan.applyClientSide(
                            rows = result.value.items,
                            filters = filters,
                            capabilities = adapter.capabilities,
                            servedByIndex = target.servedByIndex,
                        ),
                    ),
                    origin = origin,
                    hasMore = result.value.hasMore,
                    // A fallback that returns results is still a fallback: ten entries with no
                    // version are not the catalogue, and whoever draws the screen must be able to say
                    // so.
                    shortfall = if (origin == ResultOrigin.BOOTSTRAP) {
                        StoreShortfall(adapter.id, error = null, partial = true)
                    } else {
                        null
                    },
                )
            }

            is StoreResult.Failure -> {
                health.recordFailure(adapter.id, result.error)
                StoreOutcome(
                    origin = origin,
                    shortfall = StoreShortfall(adapter.id, result.error.toAppError()),
                )
            }

            StoreResult.Unsupported -> StoreOutcome(
                origin = origin,
                shortfall = StoreShortfall(adapter.id, error = null, partial = true),
            )
        }
    }

}
