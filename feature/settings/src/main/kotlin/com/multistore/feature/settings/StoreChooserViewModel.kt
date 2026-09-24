package com.multistore.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multistore.core.common.net.StoreDiagnosis
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.model.StoreId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The store list, and the two ways it is written.
 *
 * A ViewModel of its own rather than a slice of `SettingsViewModel`: this is a separate destination,
 * and reusing the other one would build the whole settings graph — remote config, diagnostics,
 * storage sizes, the maintenance repository — to draw nine cards.
 */
@HiltViewModel
class StoreChooserViewModel @Inject constructor(
    private val storeHealth: StoreHealthRepository,
) : ViewModel() {

    val stores: StateFlow<List<StoreEntry>> = storeHealth.observeStores()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    /**
     * Turns one store on or off.
     *
     * Writes to Room and not to `settings.proto`: the `enabled` column of the `stores` table is what
     * `SearchRepository` already reads to decide who to query, and a second copy in the DataStore
     * would be a value free to diverge from the one in charge.
     */
    fun setEnabled(storeId: StoreId, enabled: Boolean) {
        viewModelScope.launch { runCatching { storeHealth.setEnabled(storeId, enabled) } }
    }

    /**
     * Turns a whole group on or off, **writing only what changed**.
     *
     * This is the surviving half of the dialog's batching, and the reason it survived: each write
     * touches a Room row and makes the flow search observes re-emit, so "select all" over a group
     * already all on would otherwise be five rewrites announcing nothing. The read is of the current
     * value rather than of what the screen last drew — between the tap and this line a sync may have
     * registered a store, and writing the screen's snapshot would undo it.
     */
    fun setAll(storeIds: List<StoreId>, enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                val current = stores.first().associate { it.storeId to it.enabled }
                storeIds.filter { current[it] != enabled }
                    .forEach { storeHealth.setEnabled(it, enabled) }
            }
        }
    }

    /**
     * Why one store is not answering, read at the moment somebody asks.
     *
     * `suspend` and on demand: it is one store out of nine, and keeping nine queries warm to answer
     * a question nobody has asked would be nine queries for nothing.
     */
    suspend fun storeDiagnosis(storeId: StoreId): StoreDiagnosis = storeHealth.diagnosis(storeId)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
