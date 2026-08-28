package com.multistore.feature.appdetail

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import kotlinx.serialization.Serializable

/**
 * How the detail screen is reached.
 *
 * The route lives **in the feature** rather than in `:app` alongside the four top-level ones, and
 * that is a substantive difference: those take no arguments, this one does. Declaring here what it
 * needs in order to open means navigation is compiler-checked from both sides — whoever navigates
 * cannot forget an argument, and the ViewModel can read them with
 * `savedStateHandle.toRoute<AppDetailRoute>()` rather than by name, through string keys.
 *
 * The two fields are `String` and not [StoreId]/[StoreAppRef] because they end up in a navigation
 * URL: [StoreAppRef] is an **opaque** value only the adapter interprets, and its content can be
 * anything. The two functions below are the only place the conversion happens.
 */
@Serializable
data class AppDetailRoute(
    val storeId: String,
    val ref: String,
) {
    companion object {
        fun of(storeId: StoreId, ref: StoreAppRef): AppDetailRoute =
            AppDetailRoute(storeId = storeId.wireName, ref = ref.value)
    }
}

/** `null` if the route carries a store this build does not know. */
internal fun AppDetailRoute.storeIdOrNull(): StoreId? = StoreId.fromWireNameOrNull(storeId)

internal fun AppDetailRoute.appRef(): StoreAppRef = StoreAppRef(ref)
