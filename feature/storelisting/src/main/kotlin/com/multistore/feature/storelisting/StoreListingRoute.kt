package com.multistore.feature.storelisting

import com.multistore.core.model.StoreId
import kotlinx.serialization.Serializable

/**
 * How one reaches a store's catalogue.
 *
 * A null [categoryId] means **the whole catalogue**, and it is a legitimate value rather than a fallback:
 * it is what makes reachable the packages whose name one does not already know.
 *
 * As for the detail page, the fields are `String` and not [StoreId]: they end up in a navigation URL, and
 * the conversion happens in one place only — just below.
 */
@Serializable
data class StoreListingRoute(
    val storeId: String,
    val categoryId: String? = null,
) {
    companion object {
        fun of(storeId: StoreId, categoryId: String? = null): StoreListingRoute =
            StoreListingRoute(storeId = storeId.wireName, categoryId = categoryId)
    }
}

/** `null` if the route carries a store this build does not know. */
internal fun StoreListingRoute.storeIdOrNull(): StoreId? = StoreId.fromWireNameOrNull(storeId)
