package com.multistore.feature.appdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.multistore.core.data.repository.CrossStoreRepository
import com.multistore.core.data.repository.StoreComparison
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What each store says about this app, side by side.
 *
 * ### Opening the screen is the request
 *
 * Most of the table comes from rows Room already holds — `store_listings`, `app_versions` — or from
 * an adapter's own declaration. The rows that do not are the ones cross-store matching discovered in
 * a **result list**: they carry no versions, so they have no size, no version name and no package,
 * and a comparison whose cells mostly say "not read yet" answers the question it was opened to
 * answer with a shrug. Those are read on arrival.
 *
 * It is a fetch per unread row, up to eight of them, and it is affordable here for a reason that is
 * about the gesture rather than the cost: **nobody reaches this screen by scrolling.** They press a
 * button whose only purpose is to put the stores side by side. Opening a *listing* still reads
 * nothing, and finding stores that have never been matched is still `lookUp`, still behind its own
 * button.
 *
 * ### Once per arrival, from `init`
 *
 * Not from the flow's collection: `WhileSubscribed` re-subscribes whenever the screen comes back to
 * the foreground, so a read started there would fire again on every rotation and every return from
 * the background. The ViewModel is built once per navigation to this route, which is exactly the
 * grain wanted — and the repository refuses a second call for a row already in flight anyway.
 */
@HiltViewModel
class StoreComparisonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val crossStore: CrossStoreRepository,
    private val registry: StoreRegistry,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<StoreComparisonRoute>()
    private val storeId: StoreId? = route.storeIdOrNull()
    private val ref: StoreAppRef = route.appRef()

    init {
        storeId?.let { id -> viewModelScope.launch { crossStore.readListings(id, ref) } }
    }

    val uiState: StateFlow<StoreComparison> =
        // A route naming a store this build does not know is not an error to draw: it is a link from
        // a version that had one more adapter. An empty table says the truth — there is nothing to
        // compare — without a branch anybody has to remember to handle.
        (storeId?.let { crossStore.compare(it, ref) } ?: flowOf(StoreComparison()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StoreComparison())

    /** The name a store presents itself under. The adapter declares it: it is not interface text. */
    fun storeDisplayName(id: StoreId): String =
        registry.adapter(id)?.metadata?.displayName ?: id.wireName

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
