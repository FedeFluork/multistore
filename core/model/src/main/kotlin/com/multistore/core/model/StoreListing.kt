package com.multistore.core.model

import kotlin.time.Instant

/**
 * An app as it appears in **one** store's result list.
 *
 * Deliberately thin: it is what can be read off a search page without opening the detail. Every
 * field except [storeId], [ref] and [title] is optional, because the aggregated stores publish
 * different sets — an1, for one, publishes no `packageName` anywhere on its site.
 */
data class StoreListingSummary(
    val storeId: StoreId,
    val ref: StoreAppRef,
    val title: String,
    val packageName: String? = null,
    val summary: LocalizedText = LocalizedText.EMPTY,
    val developer: String? = null,
    val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
    val contentKind: ContentKind = ContentKind.UNKNOWN,
    val latestVersionName: String? = null,
    val latestVersionCode: Long? = null,
    val rating: Float? = null,
    val ratingCount: Int? = null,
    val downloadsLabel: String? = null,
    val lastUpdated: Instant? = null,
)

/** A screenshot published by the store. */
data class Screenshot(
    val url: String,
    val kind: ScreenshotKind = ScreenshotKind.PHONE,
)

/**
 * The complete listing of an app on **one** store.
 *
 * [preferredSignerSha256] is the signer the store recommends for a fresh installation. F-Droid
 * publishes it for 4,256 packages out of 4,257 (the one without is the OTA `.zip`, which is
 * discarded anyway), and it is what separates a reproducible build signed by the developer from
 * one signed by the repository: picking either at random means an update the OS will refuse.
 */
data class StoreListingDetail(
    val summary: StoreListingSummary,
    val description: LocalizedText = LocalizedText.EMPTY,
    val whatsNew: LocalizedText = LocalizedText.EMPTY,
    val screenshots: List<Screenshot> = emptyList(),
    val versions: List<AppVersion> = emptyList(),
    val preferredSignerSha256: Sha256? = null,
    val license: String? = null,
    val sourceCodeUrl: String? = null,
    val issueTrackerUrl: String? = null,
    val webSiteUrl: String? = null,
    val changelogUrl: String? = null,
    val translationUrl: String? = null,
    val donateUrls: List<String> = emptyList(),
    val authorName: String? = null,
    val addedAt: Instant? = null,
) {
    val storeId: StoreId get() = summary.storeId
    val ref: StoreAppRef get() = summary.ref
}
