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

/**
 * A screenshot published by the store.
 *
 * [locale] is the BCP-47 tag the source filed it under, and it is `null` for the eight scraped
 * stores, which publish one set and do not say for whom. Only F-Droid localises them — and it does
 * so on a scale nothing had to reckon with while nobody drew them: **95 images for AntennaPod**,
 * measured on the device, the same handful of screens repeated in every language the pruning keeps.
 *
 * Discarding the tag was reasonable when the field had no reader; with a strip on the page it turns
 * into a reader scrolling past four copies of a screen in languages they do not read. See
 * [forLanguages].
 */
data class Screenshot(
    val url: String,
    val kind: ScreenshotKind = ScreenshotKind.PHONE,
    val locale: String? = null,
)

/**
 * The screenshots worth showing to somebody who reads [preferredTags], in order.
 *
 * ### Why the ladder is borrowed rather than rewritten
 *
 * Choosing among `de`, `de-DE`, `en-US` and `it` is exactly the problem [LocalizedText.resolve]
 * already solves — exact match, then language without region, then any region of that language, then
 * English, then whatever there is. Writing a second ladder here would be a second set of rules that
 * can drift from the first, so this builds a `LocalizedText` whose value **is** the tag and asks it
 * which tag wins.
 *
 * ### And a source that files nothing under a language keeps everything
 *
 * Eight stores out of nine publish screenshots with no tag at all. Resolving over an empty set of
 * tags would leave those listings with no images, which is the opposite of the point.
 */
fun List<Screenshot>.forLanguages(preferredTags: List<String>): List<Screenshot> {
    val tags = mapNotNull { it.locale }.distinct()
    if (tags.isEmpty()) return this
    val winner = LocalizedText(tags.associateWith { it }).resolve(preferredTags) ?: return this
    // The untagged ones stay: on a source that mixes the two — none today, but the model allows it —
    // dropping them would hide images nobody claimed belonged to another language.
    return filter { it.locale == null || it.locale.equals(winner, ignoreCase = true) }
}

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
