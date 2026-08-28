package com.multistore.feature.storelisting

import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.GetHomeContentUseCase
import com.multistore.core.model.Category
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.testing.FakeSearchRepository
import com.multistore.core.testing.FakeStoreAdapter
import com.multistore.core.testing.FakeStoreIndexRepository
import com.multistore.core.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Browsing the local catalogue.
 *
 * Up to the Paging 3 rewrite this class mostly tested **page bookkeeping**: that the second page appended
 * instead of replacing, that a short list closed pagination, that changing category did not restart from
 * page three. That code is no longer ours — it is Paging, and it is tested by whoever writes it. Aping it
 * here would mean testing the library.
 *
 * What is left is what the ViewModel **decides**: which category it opens on, that `null` means the whole
 * catalogue, that changing category queries the new one, and that re-picking the one already chosen queries
 * nothing. Plus one thing Paging cannot know: that the rows coming out are the ones the repository gave,
 * which is read with `asSnapshot()`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StoreListingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val search = FakeSearchRepository()
    private val index = FakeStoreIndexRepository(
        taxonomy = StoreTaxonomy(
            categories = listOf(
                Category("Internet", LocalizedText(mapOf("it" to "Internet"))),
                Category("Games", LocalizedText(mapOf("it" to "Games"))),
            ),
        ),
    )
    private val subscriptions = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() = subscriptions.cancel()

    private fun viewModel(categoryId: String? = null) = StoreListingViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf("storeId" to StoreId.FDROID.wireName, "categoryId" to categoryId),
        ),
        catalogue = GetHomeContentUseCase(search),
        index = index,
        registry = StoreRegistry(setOf(FakeStoreAdapter())),
    )

    @Test
    fun `it opens on the route's category`() = runTest(dispatcher) {
        search.onBrowse = { _, _ -> listOf(app("uno")) }
        val viewModel = viewModel(categoryId = "Internet")

        // The state is read **first**: `asSnapshot()` collects on a scope of its own and returns control
        // when the list has settled, not when `uiState` has emitted.
        assertThat(viewModel.state().selectedCategoryId).isEqualTo("Internet")
        val rows = viewModel.apps.asSnapshot()

        assertThat(search.browsed).containsExactly("Internet" to 0)
        assertThat(rows.map { it.ref.value }).containsExactly("uno")
    }

    @Test
    fun `with no category it browses the whole catalogue`() = runTest(dispatcher) {
        search.onBrowse = { _, _ -> listOf(app("uno")) }

        viewModel().apps.asSnapshot()

        // `null` is not a degenerate case: it is the only way to reach the packages whose name one does
        // not already know, and a store's categories are not a partition.
        assertThat(search.browsed).containsExactly(null to 0)
    }

    @Test
    fun `cambiare categoria interroga la nuova, e la lista e' la sua`() = runTest(dispatcher) {
        search.onBrowse = { category, _ -> listOf(app("$category-1")) }
        val viewModel = viewModel(categoryId = "Internet")
        viewModel.apps.asSnapshot()

        viewModel.selectCategory("Games")
        val rows = viewModel.apps.asSnapshot()

        assertThat(search.browsed).containsExactly("Internet" to 0, "Games" to 0).inOrder()
        // The list is not the sum of the two categories: it is the new one.
        assertThat(rows.map { it.ref.value }).containsExactly("Games-1")
    }

    /**
     * Re-picking the already-chosen category does not query the catalogue again.
     *
     * The guard is no longer a hand-written `if` — there was one, and it went with the rewrite — but the
     * fact that `selected` is a `StateFlow`, which does not re-emit a value equal to the previous one.
     *
     * **The collector stays alive for the whole test**, and that is not a detail: `cachedIn` keeps the
     * chain alive only while somebody collects, and between two separate `asSnapshot()` calls nobody does
     * — so an emission in between would be lost and the test would stay green even with the defence
     * removed. That is what the first draft did, and an injection said so.
     */
    @Test
    fun `re-tapping the already-chosen category reloads nothing`() = runTest(dispatcher) {
        search.onBrowse = { _, _ -> listOf(app("uno")) }
        val viewModel = viewModel(categoryId = "Internet")
        subscriptions.launch { viewModel.apps.collect { } }

        viewModel.selectCategory("Internet")

        assertThat(search.browsed).hasSize(1)
    }

    /** The counter-proof: a **different** category crosses the collector and really does query. */
    @Test
    fun `tapping a different category reloads`() = runTest(dispatcher) {
        search.onBrowse = { _, _ -> listOf(app("uno")) }
        val viewModel = viewModel(categoryId = "Internet")
        subscriptions.launch { viewModel.apps.collect { } }

        viewModel.selectCategory("Games")

        assertThat(search.browsed).containsExactly("Internet" to 0, "Games" to 0).inOrder()
    }

    /**
     * Coming back to the screen does not reload the catalogue from scratch.
     *
     * That is what `cachedIn(viewModelScope)` buys, and without a test nobody buys it: the ViewModel
     * survives a rotation, the `Flow` does not. Without the cache, every reattachment rebuilds the `Pager`
     * and re-reads the first page — that is, the user who rotates the phone goes back to the top of a
     * 4,280-row list.
     *
     * Two collections in sequence and not two in parallel: in parallel Paging throws ("Attempt to collect
     * twice"), and a test expecting an exception would prove `cachedIn` is there, not that it is good for
     * anything.
     */
    @Test
    fun `reattaching to the list does not re-read the catalogue`() = runTest(dispatcher) {
        search.onBrowse = { _, _ -> listOf(app("uno")) }
        val viewModel = viewModel(categoryId = "Internet")

        viewModel.apps.asSnapshot()
        viewModel.apps.asSnapshot()

        assertThat(search.browsed).hasSize(1)
    }

    @Test
    fun `an empty catalogue gives an empty list, not an error`() = runTest(dispatcher) {
        search.onBrowse = { _, _ -> emptyList() }

        assertThat(viewModel().apps.asSnapshot()).isEmpty()
    }

    @Test
    fun `the filters are the ones the store publishes, already localised`() = runTest(dispatcher) {
        val state = viewModel().state()

        assertThat(state.categories.map { it.displayName(listOf("it")) })
            .containsExactly("Internet", "Games")
        assertThat(state.storeName).isEqualTo("F-Droid")
    }

    private fun StoreListingViewModel.state(): StoreListingUiState {
        subscriptions.launch { uiState.collect { } }
        return uiState.value
    }

    private fun app(ref: String) = StoreListingSummary(
        storeId = StoreId.FDROID,
        ref = StoreAppRef(ref),
        title = ref,
    )
}
