package com.multistore.core.domain.usecase

import com.multistore.core.common.result.Outcome
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.repository.StoreIndexRepository
import com.multistore.core.data.repository.StoreTaxonomy
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The listing, plus the vocabulary to read it with.
 *
 * [taxonomy] is not an extra: the anti-features stored on a version are **identifiers only**
 * (`Tracking`, `NonFreeNet`), because name and description arrive already localised from the store
 * and live once in `store_anti_features` instead of being copied inside each of the 2,666 versions
 * carrying them. Without the taxonomy alongside, the listing would show an Italian user the word
 * `NonFreeNet`.
 */
data class AppDetailWithTaxonomy(
    val detail: AppDetail,
    val taxonomy: StoreTaxonomy,
)

class GetAppDetailUseCase @Inject constructor(
    private val details: AppDetailRepository,
    private val index: StoreIndexRepository,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(storeId: StoreId, ref: StoreAppRef): Flow<AppDetailWithTaxonomy?> =
        combine(details.observe(storeId, ref), index.observeTaxonomy(storeId)) { detail, taxonomy ->
            detail?.let { AppDetailWithTaxonomy(it, taxonomy) }
        }

    /**
     * The previous versions, asked for **when the user opens the section**.
     *
     * It is not part of [invoke] and not part of [refresh]: on three of the nine stores it costs a
     * request to a page the listing does not touch, and making it on opening would be the speculative
     * prefetch this project forbids. The result does not come back from here — it is written into the
     * catalogue, and [invoke]'s flow re-emits it by itself.
     */
    suspend fun loadVersionHistory(storeId: StoreId, ref: StoreAppRef): Outcome<Unit> =
        details.loadVersionHistory(storeId, ref)

    /**
     * `true` if the listing is **on disk now**, read without going through the flow.
     *
     * It exists for one race, and has to be read together with its caller. Room notifies invalidation
     * on another thread: between the `saveListing` [refresh] performs and [invoke]'s flow emitting
     * there is a window in which the listing is there and nobody has said so yet. Whoever draws, in
     * that window, would see "refresh finished" and "nothing to show" together, i.e. would conclude
     * "app not found" about a listing just saved — seen on the device as a flash of less than a second
     * before the listing.
     *
     * A direct read closes the window **with no timer**: if the disk has it we go on waiting for the
     * flow, which will arrive; if it does not, "not found" is as immediate as it should be.
     */
    suspend fun isInCatalog(storeId: StoreId, ref: StoreAppRef): Boolean =
        details.detail(storeId, ref) != null

    /** Refreshes from the source. On an indexed store it does nothing: see `AppDetailRepository`. */
    suspend fun refresh(storeId: StoreId, ref: StoreAppRef, force: Boolean = false): Outcome<Unit> =
        details.refresh(storeId, ref, force)
}
