package com.multistore.core.domain.usecase

import com.multistore.core.data.repository.CrossStoreAvailability
import com.multistore.core.data.repository.CrossStoreRepository
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Which other stores this app is on — and which might have it.
 *
 * The name says what the listing asks; the repository says how it is answered. The separation
 * matters more than usual here, because the answer will change: when the remote index publishes the
 * `packageName ← (store, slug)` mappings, another source will join the current three without this
 * signature changing.
 */
class GetCrossStoreAvailabilityUseCase @Inject constructor(
    private val crossStore: CrossStoreRepository,
) {

    operator fun invoke(storeId: StoreId, ref: StoreAppRef): Flow<CrossStoreAvailability> =
        crossStore.observe(storeId, ref)

    /** On the user's request: queries the stores that have not yet spoken. */
    suspend fun lookUp(storeId: StoreId, ref: StoreAppRef) = crossStore.lookUp(storeId, ref)

    suspend fun confirm(storeId: StoreId, ref: StoreAppRef, candidateListingId: Long) =
        crossStore.confirm(storeId, ref, candidateListingId)

    suspend fun reject(storeId: StoreId, ref: StoreAppRef, candidateListingId: Long) =
        crossStore.reject(storeId, ref, candidateListingId)
}
