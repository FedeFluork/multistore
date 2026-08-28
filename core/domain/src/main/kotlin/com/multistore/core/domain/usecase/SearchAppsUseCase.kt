package com.multistore.core.domain.usecase

import androidx.paging.PagingData
import com.multistore.core.data.repository.SearchPage
import com.multistore.core.data.repository.SearchProgress
import com.multistore.core.data.repository.SearchRepository
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.SearchFilters
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Searches every enabled store.
 *
 * It adds almost nothing to the repository, and that is fine: this layer's value is not doing
 * something more but **giving the operation a name** and keeping the ViewModels out of the
 * repositories.
 *
 * The two forms are not a duplicate. [stream] is the **first** page's, where the results have to
 * appear as the stores answer; [invoke] is "load more"'s, where there is nothing to show
 * progressively because the list is already on screen and grows at the bottom. Underneath it is the
 * same fusion: `search` is `searchStreaming`'s last emission.
 */
class SearchAppsUseCase @Inject constructor(
    private val search: SearchRepository,
) {
    fun stream(
        query: String,
        storeIds: Set<StoreId> = emptySet(),
        page: Int = 0,
        filters: SearchFilters = SearchFilters.NONE,
    ): Flow<SearchProgress> = search.searchStreaming(query, storeIds, page, filters)

    suspend operator fun invoke(
        query: String,
        storeIds: Set<StoreId> = emptySet(),
        page: Int = 0,
        filters: SearchFilters = SearchFilters.NONE,
    ): SearchPage = search.search(query, storeIds, page, filters)
}

/**
 * A page of the local catalogue, with the one piece of information the screen cannot deduce.
 *
 * [hasMore] is `true` when the page is full: it can therefore happen that the next comes out empty.
 * It is the honest compromise — the alternative is a `COUNT(*)` per page, i.e. an extra scan on every
 * scroll to avoid one "Load more" button too many.
 */
data class CataloguePage(
    val apps: List<StoreListingSummary>,
    val hasMore: Boolean,
)

/**
 * What to show on the Home screen, read from the local index.
 *
 * The Home makes no network requests, by choice: "recently updated" and the categories come from
 * what the sync has already written. The first launch, when the index is not there yet, is the only
 * case in which it returns empty lists — and it is the case in which the Home has to show the sync's
 * progress, not a "no results" state.
 */
class GetHomeContentUseCase @Inject constructor(
    private val search: SearchRepository,
) {
    suspend fun recentlyUpdated(storeId: StoreId, page: Int = 0): List<StoreListingSummary> =
        search.recentlyUpdated(storeId, page)

    /**
     * Browses the local catalogue: [categoryId] `null` = all the store's apps.
     *
     * It returns a [CataloguePage] and not a list because "there is more" is arithmetic on the page
     * size, and the page size is a property of the repository. Making the screen deduce it would mean
     * that a change to `PAGE_SIZE` breaks the pagination of every screen that copied the comparison.
     */
    suspend fun browse(storeId: StoreId, categoryId: String?, page: Int = 0): CataloguePage {
        val apps = search.browse(storeId, categoryId, page)
        return CataloguePage(apps = apps, hasMore = apps.size == SearchRepository.PAGE_SIZE)
    }

    /**
     * The same catalogue, paged by Paging 3.
     *
     * It does not return a [CataloguePage] and has no `page`: "there is more" stops being the caller's
     * arithmetic and becomes a state of the flow. It is why this signature is shorter than the other
     * while doing more — and why [browse] remains, for the Home, which shows a single page.
     */
    fun browsePaged(storeId: StoreId, categoryId: String?): Flow<PagingData<StoreListingSummary>> =
        search.browsePaged(storeId, categoryId)
}
