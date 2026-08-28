package com.multistore.core.model

/**
 * Where a search result came from.
 *
 * Not an implementation detail to hide: [BOOTSTRAP] means "this is the best that can be said
 * right now", and the UI has to be able to say so rather than passing ten truncated results off
 * as the whole catalogue.
 */
enum class ResultOrigin {
    /** The store's complete index, held locally: no network, no truncation. */
    LOCAL_INDEX,

    /** An HTTP request to the store. */
    REMOTE,

    /**
     * The fallback search of a locally-indexed store, used until the index is there.
     *
     * For F-Droid that is at most 10 results with neither `packageName` nor version: they cover
     * the window between first launch and the end of the first sync, and nothing else.
     */
    BOOTSTRAP,
}

/**
 * A listing inside a group, with **how strongly** and **why** it belongs there.
 *
 * [confidence] and [method] are not diagnostics: they are what separates a demonstrated match
 * from a plausible one, and the UI uses them to decide whether a listing merges silently or is
 * offered as a "possible match". Below `0.85` nothing ever merges silently.
 */
data class AggregatedListing(
    val summary: StoreListingSummary,
    val origin: ResultOrigin = ResultOrigin.REMOTE,
    val confidence: Float = 1.0f,
    val method: MatchMethod = MatchMethod.PACKAGE_NAME,
) {
    val storeId: StoreId get() = summary.storeId
    val ref: StoreAppRef get() = summary.ref
}

/**
 * The same app as seen by several stores.
 *
 * [listings] is ordered: the first one represents the group — the strongest match wins, ties
 * broken by how high the store placed it. The group **never** contains uncertain matches: those
 * live separately, because merging them silently would mean offering another app's APK.
 */
data class AggregatedApp(
    val appKey: String,
    val listings: List<AggregatedListing>,
) {
    init {
        require(listings.isNotEmpty()) { "a group with no listings is not an app: $appKey" }
    }

    val primary: AggregatedListing get() = listings.first()

    /**
     * The identity **of a row in a list**, which is not [appKey].
     *
     * `appKey` is the **domain** identity: where a store publishes the `packageName` it is
     * `pkg:{package}` and exact; where it does not, it is `sig:{digest of normalised title +
     * developer}`, which is **not unique by construction** — two listings with no `packageName`
     * and no declared publisher share the key while remaining two distinct groups for the
     * matcher.
     *
     * With an1, which publishes no `packageName` anywhere on the site, that collision happens on
     * the first search, and it showed up as `IllegalArgumentException: Key "sig:…" was already
     * used` — `LazyColumn` closing the app.
     *
     * The fix is **not** to make `appKey` unique: that key must keep matching the row already
     * written in `store_listings` for the same package. It is to separate the two questions. The
     * answer here is "which row of this list", and the representative listing settles it: the
     * pair `(storeId, ref)` is unique by construction, because a group cannot contain the same
     * listing of the same store twice. It is also **stable** across recompositions, because the
     * order of a group's members is deterministic.
     */
    val listKey: String get() = "$appKey@${primary.summary.storeId.wireName}/${primary.summary.ref.value}"

    /**
     * How many **distinct** stores, not how many listings.
     *
     * The difference shows on stores that publish several pages for the same app — apkmirror has
     * one per variant — and "available on 3 stores" must count sources, not pages.
     */
    val storeCount: Int get() = stores.size

    val stores: List<StoreId> get() = listings.map { it.summary.storeId }.distinct()

    /** `true` if any listing came from an indexed store's fallback search. */
    val hasBootstrapListing: Boolean get() = listings.any { it.origin == ResultOrigin.BOOTSTRAP }

    /**
     * The listing to **show**: the primary one, completed with what the other stores publish and
     * it does not.
     *
     * Not cosmetic. Some stores publish neither `packageName` nor icon in search results, and
     * one (apkmody) has a cover image rather than an icon, so its adapter leaves it `null`.
     * Taking the first available value across the group, field by field, is the only way for the
     * row to show a real icon instead of none.
     */
    val displaySummary: StoreListingSummary
        get() {
            // Rating and rating count from the **same** listing, not two independent
            // `firstNotNullOfOrNull` calls: taken separately they would give "4.5 from 96
            // ratings" with the 4.5 from one store and the 96 from another — a figure that
            // exists nowhere.
            val rated = listings.firstOrNull { it.summary.rating != null }?.summary
            return primary.summary.copy(
                packageName = listings.firstNotNullOfOrNull { it.summary.packageName },
                iconUrl = listings.firstNotNullOfOrNull { it.summary.iconUrl },
                developer = listings.firstNotNullOfOrNull { it.summary.developer },
                contentKind = listings.map { it.summary.contentKind }
                    .firstOrNull { it != ContentKind.UNKNOWN } ?: ContentKind.UNKNOWN,
                rating = rated?.rating,
                ratingCount = rated?.ratingCount,
                summary = listings.map { it.summary.summary }.firstOrNull { !it.isEmpty }
                    ?: LocalizedText.EMPTY,
            )
        }
}
