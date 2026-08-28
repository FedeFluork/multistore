package com.multistore.core.domain.usecase

import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.data.repository.StoreEntry
import com.multistore.core.data.repository.StoreHealthRepository
import com.multistore.core.model.SearchSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the filter panel starts with: the values chosen in Settings, and the switched-on stores.
 *
 * ### Why the two defaults are **not** applied by the repository
 *
 * "Show adult content" is applied by `SearchRepositoryImpl` overriding the caller, and the reason is
 * written there: it is a safety setting, and every new caller forgetting to read it would produce
 * content the user asked not to see, with no error.
 *
 * Here it is the opposite, and it is not an inconsistency. `default_sort` and `default_content_kind`
 * are **initial values**, not constraints: the user has to be able to change them for a single
 * search, and a repository re-applying them on every call would make the filter panel incapable of
 * the one thing it exists for. The criterion separating the two cases is whether overriding the
 * caller is a defence or a defect.
 *
 * [stores] serves the "only these stores, for this search" choice. It reads the same `stores.enabled`
 * column the search reads, and not a copy in the DataStore: a second copy would be a value that
 * diverges — and it is also why number 24 of `settings.proto` stays reserved and unwritten.
 */
class SearchOptionsUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val health: StoreHealthRepository,
) {
    /** The values the search starts with when the user has not yet touched anything. */
    suspend fun defaults(): SearchSettings = settings.search.first()

    /** The stores the user has left on, in the order they are shown. */
    fun enabledStores(): Flow<List<StoreEntry>> =
        health.observeStores().map { entries -> entries.filter { it.enabled } }
}
