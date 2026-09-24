package com.multistore.feature.appdetail

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import kotlinx.serialization.Serializable

/**
 * How the comparison table is reached: from the listing one is already on.
 *
 * ### Why it lives in `:feature:appdetail` and not in a module of its own
 *
 * It answers the listing screen's own question — "which of these stores do I take it from" — with the
 * data that screen already holds, and it is opened from it and returns to it. A `:feature:compare`
 * would need the same repositories, the same store display names and the same navigation to another
 * store's listing, and it could not reach the listing screen anyway: a `:feature:*` never depends on
 * another. Two routes in one feature is what this project already does everywhere the second screen
 * is part of the first one's job.
 *
 * The two fields are `String` for the same reason as [AppDetailRoute]'s: they end up in a navigation
 * URL, and a [StoreAppRef] is opaque — its content is whatever the adapter put there.
 */
@Serializable
data class StoreComparisonRoute(
    val storeId: String,
    val ref: String,
) {
    companion object {
        fun of(storeId: StoreId, ref: StoreAppRef): StoreComparisonRoute =
            StoreComparisonRoute(storeId = storeId.wireName, ref = ref.value)
    }
}

internal fun StoreComparisonRoute.storeIdOrNull(): StoreId? = StoreId.fromWireNameOrNull(storeId)

internal fun StoreComparisonRoute.appRef(): StoreAppRef = StoreAppRef(ref)
