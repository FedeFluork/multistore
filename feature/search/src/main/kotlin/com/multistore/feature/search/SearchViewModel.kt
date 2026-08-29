package com.multistore.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreShortfall
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.SearchAppsUseCase
import com.multistore.core.domain.usecase.SearchOptionsUseCase
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.ContentKind
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreId
import com.multistore.store.api.SearchFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

sealed interface SearchUiState {

    val query: String

    /** No search in progress: empty field. */
    data class Idle(override val query: String = "") : SearchUiState

    /**
     * A search is running and **nothing** has arrived yet.
     *
     * It is the only state in which the screen shows just a spinner: as soon as the first store answers
     * it moves to [Results], even if the others have yet to speak.
     */
    data class Searching(
        override val query: String,
        val answered: Int = 0,
        val queried: Int = 0,
    ) : SearchUiState

    data class Results(
        override val query: String,
        val apps: List<AggregatedApp>,
        val shortfalls: List<StoreShortfall>,
        val hasMore: Boolean,
        val loadingMore: Boolean = false,
        /** How many stores have already answered and how many were queried in total. */
        val answered: Int = 0,
        val queried: Int = 0,
    ) : SearchUiState {
        /**
         * `true` while some store still has to answer.
         *
         * Not a refinement: aggregation reorders the list on every arrival, and saying so is the only
         * honest way of explaining to whoever is watching why a row moved.
         */
        val stillArriving: Boolean get() = answered < queried

        /**
         * `true` if some results come from the fallback search of an index store.
         *
         * For F-Droid that is at most 10 results with neither `packageName` nor version: they cover the
         * window between first launch and the end of the first sync. Saying so is not pedantry — the
         * alternative is passing ten truncated results off as the whole catalogue, and the user concludes
         * the app they are after does not exist.
         */
        val partialByBootstrap: Boolean get() = apps.any { it.hasBootstrapListing }
    }

    data class NoResults(
        override val query: String,
        val shortfalls: List<StoreShortfall>,
    ) : SearchUiState
}

/**
 * The **current** search's filters, and the stores to choose among.
 *
 * None of this is saved. [sort] and [contentKind] start from Settings and from then on belong to the
 * search being made: writing them back would turn a choice for a single query into the new default
 * behaviour, which is exactly what a filter panel must not do.
 *
 * [excludedStores] is not saved for one more reason: which stores to query already lives in the
 * `stores.enabled` column, and a second copy in the DataStore would be a value that diverges. Here the
 * choice is "for this search only", and it resets with it.
 */
data class SearchFilterState(
    val contentKind: ContentKind? = null,
    val minRating: Float? = null,
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** The stores the user removed **from this search**, among the enabled ones. */
    val excludedStores: Set<StoreId> = emptySet(),
    /** The enabled stores, that is the ones to choose among. */
    val available: List<StoreEntry> = emptyList(),
) {
    /**
     * How many filters are active, for the badge on the button.
     *
     * The sort order does not count as a filter: it removes nothing, and flagging it as active would
     * suggest there are results hidden somewhere.
     */
    val activeCount: Int
        get() = listOf(
            contentKind != null,
            minRating != null,
            excludedStores.isNotEmpty(),
        ).count { it }

    /** The stores to query. Empty = all enabled ones, which is also what the search means. */
    val storeIds: Set<StoreId>
        get() = if (excludedStores.isEmpty()) {
            emptySet()
        } else {
            available.map { it.storeId }.filterNotTo(LinkedHashSet()) { it in excludedStores }
        }

    /** The filters in the shape the repository understands. */
    fun toSearchFilters(): SearchFilters = SearchFilters(
        contentKind = contentKind,
        minRating = minRating,
        sort = sort,
    )
}

/**
 * The search, across every enabled store, **as they answer**.
 *
 * There is no error state, deliberately: with nine sources queried together the normal case is not "all
 * fine" or "all broken". A store that fails becomes a [StoreShortfall] next to the other eight's
 * results, not a red screen in their place.
 *
 * The same goes for waiting: waiting for the slowest before showing the first result would mean, with
 * one store carrying a three-second `Crawl-delay`, a search that looks broken. The flow emits on every
 * answer and the screen fills up; the number of stores still missing stays written until they all
 * arrive.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchApps: SearchAppsUseCase,
    private val options: SearchOptionsUseCase,
    private val registry: StoreRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _filters = MutableStateFlow(SearchFilterState())
    val filters: StateFlow<SearchFilterState> = _filters.asStateFlow()

    private val _query = MutableStateFlow("")

    /**
     * The text **in the field**, and the only state here with a single writer.
     *
     * [SearchUiState.query] answers a different question — *which query are these results for?* — and
     * it is written by two asynchronous producers that cannot know what has been typed since they
     * started. Binding the editor to it is what made the field revert its text and the caret jump
     * back: the `String` overload of `BasicTextField` keeps the selection in a private state and
     * recombines it as `copy(text = value)`, so a `value` that did not come from the last keystroke
     * gets the old offset coerced into the shorter string. The same distinction is written down for
     * the Settings field, in `SettingsScreen`: this is the search screen catching up with it.
     */
    val queryText: StateFlow<String> = _query.asStateFlow()

    private var searchJob: Job? = null
    private var page = 0

    /**
     * The last text handed to [runSearch]. Read only by [alreadyAnswered], and only together with
     * whether that search is still running: on its own it is not a safe answer, which is what the
     * doc on [alreadyAnswered] is about.
     */
    private var searchedQuery: String? = null

    init {
        // The two defaults arrive once, when the screen opens. Observing them would mean that changing
        // the setting in another tab rewrites the filters of a search in progress — that is, a list that
        // reorders itself while being read.
        viewModelScope.launch {
            val defaults = options.defaults()
            _filters.value = _filters.value.copy(
                sort = defaults.defaultSort,
                contentKind = defaults.defaultContentKind,
            )
        }
        options.enabledStores()
            // Only the list to choose among: the exclusions stay the user's. A store turned off elsewhere
            // disappears from the list and its exclusion becomes inert, which is what should happen —
            // there is nothing to exclude from a search that no longer queries it.
            .onEach { stores -> _filters.value = _filters.value.copy(available = stores) }
            .launchIn(viewModelScope)
    }

    // --- Filters -----------------------------------------------------------------------

    fun setContentKind(kind: ContentKind?) = updateFilters { copy(contentKind = kind) }

    fun setMinRating(minRating: Float?) = updateFilters { copy(minRating = minRating) }

    fun setSort(sort: SearchSort) = updateFilters { copy(sort = sort) }

    fun toggleStore(storeId: StoreId, included: Boolean) = updateFilters {
        copy(excludedStores = if (included) excludedStores - storeId else excludedStores + storeId)
    }

    /** Back to the values the screen started with. Does not touch Settings. */
    fun resetFilters() = viewModelScope.launch {
        val defaults = options.defaults()
        _filters.value = _filters.value.copy(
            contentKind = defaults.defaultContentKind,
            minRating = null,
            sort = defaults.defaultSort,
            excludedStores = emptySet(),
        )
        runSearch(_query.value)
    }

    /**
     * Changes the filters and **redoes the search immediately**, with no debounce.
     *
     * The debounce is for whoever is typing: seven letters are seven searches, and on the eight remote
     * stores those are real requests. A filter, on the other hand, is touched once, deliberately, and
     * waiting three hundred milliseconds after a tap reads as a sluggish interface.
     */
    private fun updateFilters(change: SearchFilterState.() -> SearchFilterState) {
        val updated = _filters.value.change()
        if (updated == _filters.value) return
        _filters.value = updated
        if (_query.value.isNotBlank()) runSearch(_query.value)
    }

    @OptIn(FlowPreview::class)
    private val debounced = _query
        // Without debounce, "firefox" is seven searches. On a local-index store they would only cost
        // CPU; on the other eight they are seven HTTP requests each, and the rate limiter would make
        // whoever types fast pay for them.
        .debounce(DEBOUNCE_MILLIS)
        .filter { !alreadyAnswered(it) }
        .onEach(::runSearch)
        .launchIn(viewModelScope)

    fun onQueryChange(value: String) {
        _query.value = value
        // The spinner goes up now and not in 300 ms — but only when there is nothing to keep. Results
        // already on screen stay: they belong to the previous word, and replacing them with a spinner
        // on every letter tore the `LazyColumn` down and rebuilt it once per keystroke, on the main
        // thread, which is the other half of what "the field freezes" was describing.
        if (value.isBlank()) {
            searchJob?.cancel()
            publish(SearchUiState.Idle(value))
        } else if (_uiState.value !is SearchUiState.Results) {
            publish(SearchUiState.Searching(value))
        }
    }

    /**
     * Searches at once, for the keyboard's search key.
     *
     * That key used to only hide the keyboard, so the gesture someone reaches for when the results
     * look stale did nothing at all.
     */
    fun searchNow() {
        val text = _query.value
        if (text.isNotBlank()) runSearch(text)
    }

    fun retry() = runSearch(_query.value)

    fun loadMore() {
        val current = _uiState.value as? SearchUiState.Results ?: return
        if (!current.hasMore || current.loadingMore) return
        if (searchJob?.isActive == true) return

        searchJob = viewModelScope.launch {
            publish(current.copy(loadingMore = true))
            val active = _filters.value
            val next = searchApps(
                query = current.query,
                storeIds = active.storeIds,
                page = page + 1,
                filters = active.toSearchFilters(),
            )
            page += 1
            publish(
                current.copy(
                    apps = current.apps + next.apps,
                    shortfalls = next.shortfalls,
                    hasMore = next.hasMore,
                    loadingMore = false,
                ),
            )
        }
    }

    /** The name a store presents itself under. The adapter declares it: it is not interface text. */
    fun storeDisplayName(storeId: StoreId): String =
        registry.adapter(storeId)?.metadata?.displayName ?: storeId.wireName

    private fun runSearch(text: String) {
        searchJob?.cancel()
        searchedQuery = text
        page = 0
        if (text.isBlank()) {
            publish(SearchUiState.Idle(text))
            return
        }
        searchJob = viewModelScope.launch {
            publish(SearchUiState.Searching(text))
            val active = _filters.value
            searchApps.stream(
                query = text,
                storeIds = active.storeIds,
                filters = active.toSearchFilters(),
            ).collect { progress ->
                val apps = progress.page.apps
                publish(
                    when {
                    // Until there is nothing to show **and** somebody is still missing, the screen stays
                    // on the spinner: a "no results" screen that fills up a second later is worse than a
                    // wait.
                        apps.isEmpty() && !progress.complete -> SearchUiState.Searching(
                            query = text,
                            answered = progress.answered.size,
                            queried = progress.queried,
                        )

                        apps.isEmpty() -> SearchUiState.NoResults(text, progress.page.shortfalls)

                        else -> SearchUiState.Results(
                            query = text,
                            apps = apps,
                            shortfalls = progress.page.shortfalls,
                            hasMore = progress.page.hasMore,
                            answered = progress.answered.size,
                            queried = progress.queried,
                        )
                    },
                )
            }
        }
    }

    /**
     * Publishes a state **only if it is still about the text in the field**.
     *
     * One gate instead of a cancellation on every keystroke, and the difference is not tidiness. A
     * search fans out to nine stores and emits once per arrival, so an abandoned query keeps writing
     * for seconds; `searchJob?.cancel()` is reached only by the debounce, 300 ms after the last
     * keystroke, and even then it does not help by itself — cancellation is cooperative and there is no
     * suspension point between the check and the assignment, so a continuation already resumed with a
     * buffered element runs the collect body once more after `cancel()` has returned.
     *
     * Comparing the **value** closes that window instead of narrowing it: whatever the state was
     * computed from, it is dropped unless it is about what is being typed now. Both late writers —
     * this collect loop, and [loadMore]'s write after an await of up to eight seconds — pass through
     * here.
     */
    private fun publish(state: SearchUiState) {
        if (state.query != _query.value) return
        _uiState.value = state
    }

    /**
     * `true` when a search for this exact text is **already running or already answered on screen**.
     *
     * It replaces the `distinctUntilChanged()` that used to sit after the `debounce`, and the
     * difference is which question gets asked. That operator remembered what it had *emitted*, which
     * is neither what was searched — a filter change calls [runSearch] outside this chain, so the same
     * nine-store fan-out fired again 300 ms later — nor what is on screen: clearing the field and
     * retyping the same word inside one window was dropped as a duplicate, and since [onQueryChange]
     * had already raised the spinner, the screen stayed on it **for ever**.
     *
     * Asking about the running job and the settled state instead makes that outcome unreachable: the
     * only states that suppress a search are the two that already answer the question, and a spinner
     * is not one of them. `stillArriving` is part of it because a list some stores have yet to join is
     * not an answer yet.
     */
    private fun alreadyAnswered(text: String): Boolean {
        if (searchedQuery == text && searchJob?.isActive == true) return true
        return when (val state = _uiState.value) {
            is SearchUiState.Results -> state.query == text && !state.stillArriving
            is SearchUiState.NoResults -> state.query == text
            is SearchUiState.Idle, is SearchUiState.Searching -> false
        }
    }

    override fun onCleared() {
        debounced.cancel()
        super.onCleared()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
    }
}
