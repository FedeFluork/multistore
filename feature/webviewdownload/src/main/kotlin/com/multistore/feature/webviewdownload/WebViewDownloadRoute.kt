package com.multistore.feature.webviewdownload

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadHint
import kotlinx.serialization.Serializable

/**
 * How one reaches the store page that requires a human gesture.
 *
 * The route carries the URL and the hint **as well**, instead of making the screen obtain them again:
 * resolving them a second time would mean another request to the store for something already known, and
 * on a signed expiring link the two answers could even differ. Compose's type-safe navigation encodes the
 * URL into the argument by itself.
 *
 * [hint] travels as the enum's name and not as its ordinal: ordinals change if somebody reorders the
 * values, and a deep link saved in the navigation stack survives an app update.
 */
@Serializable
data class WebViewDownloadRoute(
    val storeId: String,
    val ref: String,
    val versionRef: String,
    val pageUrl: String,
    val hint: String,
) {
    companion object {
        fun of(
            storeId: StoreId,
            ref: StoreAppRef,
            versionRef: VersionRef,
            pageUrl: String,
            hint: DownloadHint,
        ): WebViewDownloadRoute = WebViewDownloadRoute(
            storeId = storeId.wireName,
            ref = ref.value,
            versionRef = versionRef.value,
            pageUrl = pageUrl,
            hint = hint.name,
        )
    }
}

/** `null` if the route carries a store this build does not know. */
internal fun WebViewDownloadRoute.storeIdOrNull(): StoreId? = StoreId.fromWireNameOrNull(storeId)

internal fun WebViewDownloadRoute.appRef(): StoreAppRef = StoreAppRef(ref)

internal fun WebViewDownloadRoute.version(): VersionRef = VersionRef(versionRef)

/**
 * The hint, or [DownloadHint.TAP_DOWNLOAD_BUTTON] if the name is not recognised.
 *
 * The fallback is not laziness: a new value added to `:store:api` and not yet translated would arrive here
 * as an unknown name, and showing the most common instruction is better than showing none at all on a
 * screen whose only purpose is to tell the user what to do.
 */
internal fun WebViewDownloadRoute.downloadHint(): DownloadHint =
    DownloadHint.entries.firstOrNull { it.name == hint } ?: DownloadHint.TAP_DOWNLOAD_BUTTON
