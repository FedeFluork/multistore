package com.multistore.feature.storelisting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.GetHomeContentUseCase
import com.multistore.core.model.Category
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The screen's state: one screen, not a hierarchy of states.
 *
 * The category row stays visible while the list below reloads, which is why this is a `data class` and
 * not a `sealed interface`: modelling "loading" as a state of its own would make the filters disappear at
 * the very moment the user has just touched one, and every category change would become a flicker.
 *
 * **The apps are no longer in here.** The list is a separate `Flow<PagingData<…>>`: keeping the rows here
 * would mean recomposing the header on every page loaded, and above all putting back into this class the
 * in-memory accumulation Paging exists to remove.
 */
data class StoreListingUiState(
    val storeName: String,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
)

/**
 * Browsing a store's local catalogue, by category or in full.
 *
 * It is the screen that makes an 18 MB sync **useful**: without it, the 4,280 downloaded packages are
 * only reachable by typing the name of what one is already looking for. It makes no network request at
 * all: it reads the index that is there.
 *
 * Paging 3 is here and nowhere else because of a measurement: **4,280 listings** for F-Droid. It is the
 * only one of the three candidate surfaces that justifies it — the version history averages **3** versions
 * per listing with a maximum of 26, and "My apps" holds only what MultiStore installed. The reasoning in
 * full is on `SearchRepository.browsePaged`.
 *
 * What is gained is not pagination — that was already here, written by hand — but two things it did not
 * have: the list **does not grow in memory** while scrolling, and it **reloads itself** when the index is
 * resynced underneath. It used to stay as it was, forever, with nothing saying so.
 */
@HiltViewModel
class StoreListingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogue: GetHomeContentUseCase,
    index: StoreIndexRepository,
    registry: StoreRegistry,
) : ViewModel() {

    private val route: StoreListingRoute = savedStateHandle.toRoute()
    private val storeId: StoreId? = route.storeIdOrNull()

    private val storeName: String = storeId
        ?.let { registry.adapter(it)?.metadata?.displayName }
        ?: route.storeId

    private val selected = MutableStateFlow(route.categoryId)

    private val categories: Flow<List<Category>> = storeId
        ?.let { id -> index.observeTaxonomy(id).map { it.categories } }
        ?: flowOf(emptyList())

    val uiState: StateFlow<StoreListingUiState> =
        combine(categories, selected) { taxonomy, selectedId ->
            StoreListingUiState(
                storeName = storeName,
                categories = taxonomy,
                selectedCategoryId = selectedId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = StoreListingUiState(storeName = storeName),
        )

    /**
     * The apps of the chosen category.
     *
     * `flatMapLatest` and not `combine`: changing category **cancels** the previous read instead of leaving
     * two in flight. It is the same reason the hand-written version of this screen kept a `loadJob` to
     * cancel — touching three categories quickly otherwise let the last to *finish* win rather than the
     * last touched.
     *
     * `cachedIn(viewModelScope)` is mandatory and not an optimisation: without it, rotating the screen — or
     * anything else that reattaches the screen — restarts from the first page, and Paging throws if the flow
     * is collected twice.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val apps: Flow<PagingData<StoreListingSummary>> = selected
        .flatMapLatest { categoryId ->
            storeId?.let { catalogue.browsePaged(it, categoryId) } ?: flowOf(PagingData.empty())
        }
        .cachedIn(viewModelScope)

    /** @param categoryId `null` to go back to the whole catalogue. */
    fun selectCategory(categoryId: String?) {
        selected.value = categoryId
    }

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
