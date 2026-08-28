package com.multistore.feature.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.net.StoreHealth
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.SearchPage
import com.multistore.core.data.repository.SearchProgress
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreShortfall
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.SearchAppsUseCase
import com.multistore.core.domain.usecase.SearchOptionsUseCase
import com.multistore.core.model.AggregatedApp
import com.multistore.core.model.AggregatedListing
import com.multistore.core.model.ContentKind
import com.multistore.core.model.ResultOrigin
import com.multistore.core.model.SearchSettings
import com.multistore.core.model.SearchSort
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.testing.FakeSearchRepository
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.FakeStoreAdapter
import com.multistore.core.testing.FakeStoreHealthRepository
import com.multistore.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Search seen from its ViewModel.
 *
 * The test that matters is the first, and it concerns something no layer below can prove: the
 * **debounce**. On F-Droid one extra search costs a few milliseconds of CPU and would never be noticed;
 * on the other eight stores it costs an HTTP request each, and typing "firefox" without a debounce is
 * seven requests apiece — the quickest way to get rate-limited by a site that did nothing wrong.
 *
 * The dispatcher is [StandardTestDispatcher] and not the immediate default: here time is the subject of
 * the test, and a dispatcher that runs everything at once would make it invisible.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val search = FakeSearchRepository()
    private val registry = StoreRegistry(setOf(FakeStoreAdapter()))
    private val settings = FakeSettingsRepository()
    private val health = FakeStoreHealthRepository(
        listOf(
            storeEntry(StoreId.FDROID, "F-Droid"),
            storeEntry(StoreId.APKMIRROR, "APKMirror"),
        ),
    )

    private fun viewModel() = SearchViewModel(
        searchApps = SearchAppsUseCase(search),
        options = SearchOptionsUseCase(settings, health),
        registry = registry,
    )

    private fun storeEntry(storeId: StoreId, name: String) = StoreEntry(
        storeId = storeId,
        displayName = name,
        host = "${storeId.wireName}.example",
        enabled = true,
        health = StoreHealth(storeId),
    )

    @Test
    fun `typing fast produces a single search, with the last word`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        // Three distinct values, each within the previous one's 300 ms window: that is real typing,
        // and without the debounce it would be three searches across nine stores.
        viewModel.onQueryChange("fire")
        advanceTimeBy(100)
        viewModel.onQueryChange("firefo")
        advanceTimeBy(100)
        viewModel.onQueryChange("firefox")
        advanceUntilIdle()

        assertThat(search.searches).containsExactly("firefox" to 0)
    }

    @Test
    fun `the field responds immediately, the request does not`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onQueryChange("tor")
        runCurrent()

        // The visible state changes now — otherwise the field would look unresponsive for three hundred
        // milliseconds — while the network has not been touched yet.
        assertThat(viewModel.uiState.value).isEqualTo(SearchUiState.Searching("tor"))
        assertThat(search.searches).isEmpty()
    }

    @Test
    fun `clearing the field returns to Idle without searching the empty string`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onQueryChange("tor")
        advanceUntilIdle()

        viewModel.onQueryChange("")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value).isInstanceOf(SearchUiState.Idle::class.java)
        // A search on an empty field across nine stores would return everything: it is the case where not
        // asking is the only sensible answer.
        assertThat(search.searches).containsExactly("tor" to 0)
    }

    @Test
    fun `no results does not erase the sources that are missing`() = runTest(dispatcher) {
        val shortfalls = listOf(
            StoreShortfall(storeId = StoreId.APKMIRROR, error = null, circuitOpen = true),
            StoreShortfall(storeId = StoreId.MODYOLO, error = AppError.Network(null)),
        )
        search.onSearch = { _, _ -> SearchPage(apps = emptyList(), page = 0, hasMore = false, shortfalls = shortfalls) }
        val viewModel = viewModel()

        viewModel.onQueryChange("qualcosa")
        advanceUntilIdle()

        // "No results" and "no results, but two sources out of nine did not answer" are two different
        // sentences, and the second is the only honest one when it is the true one.
        val state = viewModel.uiState.value as SearchUiState.NoResults
        assertThat(state.shortfalls).hasSize(2)
    }

    @Test
    fun `load more appends instead of replacing, and asks for the next page`() = runTest(dispatcher) {
        search.onSearch = { _, page ->
            SearchPage(
                apps = listOf(app("app$page")),
                page = page,
                hasMore = page == 0,
            )
        }
        val viewModel = viewModel()
        viewModel.onQueryChange("app")
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(search.searches).containsExactly("app" to 0, "app" to 1).inOrder()
        val state = viewModel.uiState.value as SearchUiState.Results
        assertThat(state.apps.map { it.primary.ref.value }).containsExactly("app0", "app1").inOrder()
        assertThat(state.hasMore).isFalse()
    }

    @Test
    fun `load more does nothing when there is nothing more`() = runTest(dispatcher) {
        search.onSearch = { _, page -> SearchPage(listOf(app("solo")), page, hasMore = false) }
        val viewModel = viewModel()
        viewModel.onQueryChange("solo")
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(search.searches).containsExactly("solo" to 0)
    }

    @Test
    fun `the first results are visible before every store has answered`() =
        runTest(dispatcher) {
            // Two partial emissions and then the final one: it is what the repository produces when the
            // stores answer one at a time.
            search.partials = listOf(
                SearchProgress(
                    page = SearchPage(apps = emptyList(), page = 0, hasMore = false),
                    answered = emptySet(),
                    pending = setOf(StoreId.FDROID, StoreId.APKMIRROR),
                ),
                SearchProgress(
                    page = SearchPage(apps = listOf(app("primo")), page = 0, hasMore = false),
                    answered = setOf(StoreId.FDROID),
                    pending = setOf(StoreId.APKMIRROR),
                ),
            )
            search.onSearch = { _, _ ->
                SearchPage(apps = listOf(app("primo"), app("secondo")), page = 0, hasMore = false)
            }
            val viewModel = viewModel()
            val seen = mutableListOf<SearchUiState>()
            val job = launch { viewModel.uiState.toList(seen) }

            viewModel.onQueryChange("chat")
            advanceUntilIdle()
            job.cancel()

            // The state with **one** result and one store still pending has to be actually passed through:
            // that is the whole point of streaming. A ViewModel waiting for the end would jump from
            // "searching" to "two results".
            val partial = seen.filterIsInstance<SearchUiState.Results>().first()
            assertThat(partial.apps).hasSize(1)
            assertThat(partial.stillArriving).isTrue()
            assertThat(partial.answered).isEqualTo(1)
            assertThat(partial.queried).isEqualTo(2)

            val last = seen.last() as SearchUiState.Results
            assertThat(last.apps).hasSize(2)
            assertThat(last.stillArriving).isFalse()
        }

    @Test
    fun `while nothing is there and somebody is missing, it waits instead of saying no results`() =
        runTest(dispatcher) {
            search.partials = listOf(
                SearchProgress(
                    page = SearchPage(apps = emptyList(), page = 0, hasMore = false),
                    answered = setOf(StoreId.FDROID),
                    pending = setOf(StoreId.APKMIRROR),
                ),
            )
            search.onSearch = { _, _ -> SearchPage(apps = listOf(app("tardi")), page = 0, hasMore = false) }
            val viewModel = viewModel()
            val seen = mutableListOf<SearchUiState>()
            val job = launch { viewModel.uiState.toList(seen) }

            viewModel.onQueryChange("chat")
            advanceUntilIdle()
            job.cancel()

            // A "no results" screen that fills up a second later is worse than a wait: it tells the user
            // something false, and tells it with confidence.
            assertThat(seen.filterIsInstance<SearchUiState.NoResults>()).isEmpty()
            assertThat(seen.last()).isInstanceOf(SearchUiState.Results::class.java)
        }

    @Test
    fun `the store name is declared by the adapter, not by strings xml`() {
        // "F-Droid" is spelled the same in all five languages: it is a trademark, not an interface label.
        // The fallback to the wireName is for a store this build has not wired.
        assertThat(viewModel().storeDisplayName(StoreId.FDROID)).isEqualTo("F-Droid")
        assertThat(viewModel().storeDisplayName(StoreId.AN1)).isEqualTo("an1")
    }

    // --- The filters ---------------------------------------------------------------------------

    /**
     * The two Settings defaults reach the panel, and reach it **once**.
     *
     * A field nobody reads is hidden state. The "once" matters as much as the "reach": observing them
     * would mean that changing the setting elsewhere reorders a search already on screen.
     */
    @Test
    fun `the filters start from the values chosen in Settings`() = runTest(dispatcher) {
        settings.search.value = SearchSettings(
            defaultSort = SearchSort.NAME,
            defaultContentKind = ContentKind.GAME,
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.filters.value.sort).isEqualTo(SearchSort.NAME)
        assertThat(viewModel.filters.value.contentKind).isEqualTo(ContentKind.GAME)

        settings.search.value = SearchSettings(defaultSort = SearchSort.RATING)
        advanceUntilIdle()

        assertThat(viewModel.filters.value.sort).isEqualTo(SearchSort.NAME)
    }

    @Test
    fun `the chosen filters reach the search`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onQueryChange("solitario")
        advanceUntilIdle()
        viewModel.setContentKind(ContentKind.GAME)
        viewModel.setMinRating(4f)
        advanceUntilIdle()

        val last = search.searchFilters.last()
        assertThat(last.contentKind).isEqualTo(ContentKind.GAME)
        assertThat(last.minRating).isEqualTo(4f)
    }

    /**
     * Changing a filter redoes the search **immediately**, without waiting for the debounce.
     *
     * The three hundred milliseconds are for whoever is typing: seven letters are seven searches across
     * nine stores. A filter is touched once, deliberately, and the same wait there reads only as a
     * sluggish interface.
     */
    @Test
    fun `changing a filter redoes the search without waiting for the debounce`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onQueryChange("solitario")
        advanceUntilIdle()
        assertThat(search.searches).hasSize(1)

        viewModel.setSort(SearchSort.NAME)
        // Less than the debounce window: if the search went through it, it would not have started
        // here yet.
        advanceTimeBy(50)
        runCurrent()

        assertThat(search.searches).hasSize(2)
        assertThat(search.searchFilters.last().sort).isEqualTo(SearchSort.NAME)
    }

    @Test
    fun `an identical filter does not redo the search`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onQueryChange("solitario")
        advanceUntilIdle()

        viewModel.setSort(SearchSort.RELEVANCE)
        advanceUntilIdle()

        assertThat(search.searches).hasSize(1)
    }

    /**
     * Removing a store sends **the others** to the search, not the excluded one.
     *
     * An empty set already means "all the enabled ones", so sending the excluded one would be
     * indistinguishable from not filtering — and silently so.
     */
    @Test
    fun `excluding a store narrows the set sent to the search`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onQueryChange("solitario")
        advanceUntilIdle()
        assertThat(search.searchStores.last()).isEmpty()

        viewModel.toggleStore(StoreId.APKMIRROR, included = false)
        advanceUntilIdle()

        assertThat(search.searchStores.last()).containsExactly(StoreId.FDROID)
        assertThat(viewModel.filters.value.activeCount).isEqualTo(1)
    }

    /** The sort order does not count as an active filter: it hides nothing. */
    @Test
    fun `the sort order does not count among the active filters`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setSort(SearchSort.RATING)
        advanceUntilIdle()

        assertThat(viewModel.filters.value.activeCount).isEqualTo(0)
    }

    @Test
    fun `resetting the filters returns to the Settings values, not to nothing`() = runTest(dispatcher) {
        settings.search.value = SearchSettings(defaultContentKind = ContentKind.APP)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setContentKind(ContentKind.GAME)
        viewModel.setMinRating(4.5f)
        viewModel.toggleStore(StoreId.FDROID, included = false)
        advanceUntilIdle()
        viewModel.resetFilters()
        advanceUntilIdle()

        val filters = viewModel.filters.value
        assertThat(filters.contentKind).isEqualTo(ContentKind.APP)
        assertThat(filters.minRating).isNull()
        assertThat(filters.excludedStores).isEmpty()
    }

    private fun app(ref: String) = AggregatedApp(
        appKey = "pkg:$ref",
        listings = listOf(
            AggregatedListing(
                summary = StoreListingSummary(
                    storeId = StoreId.FDROID,
                    ref = StoreAppRef(ref),
                    title = ref,
                ),
                origin = ResultOrigin.LOCAL_INDEX,
            ),
        ),
    )
}
