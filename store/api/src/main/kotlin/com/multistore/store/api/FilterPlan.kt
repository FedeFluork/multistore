package com.multistore.store.api

import com.multistore.core.model.StoreListingSummary

/**
 * Who applies an active filter, store by store.
 *
 * ### The problem, measured before writing a line
 *
 * A census of the committed search fixtures (26/08/2026) says **eight adapters out of nine
 * declare an empty `supportedFilters`**, and that the fields the app could filter on itself are
 * very few:
 *
 * | store | rows | `contentKind` | `rating` | `categories` |
 * |---|---|---|---|---|
 * | apkmody | 20 | **20** | 0 | 0 |
 * | an1 | 10 | 0 | **10** | 0 |
 * | liteapks | 7 | 0 | **7** | 0 |
 * | pdalife | 18 | 0 | **18** | **18** |
 * | apkcombo | 20 | 0 | 19 | **20** |
 * | apkmirror | 10 | 0 | 0 | 0 |
 * | uptodown | 36 | 0 | 0 | 0 |
 * | modyolo | 20 | 0 | 0 | 0 |
 * | f-droid (fallback) | 10 | 0 | 0 | 0 |
 *
 * apkcombo at **19 out of 20** is the number that makes the criterion a rule rather than a
 * sentence: "almost always" is not enough, because filtering on a field that is sometimes missing
 * means discarding rows nothing is known about and presenting the result as though the filter had
 * judged them.
 *
 * ### The answer: three tiers, and the third declares itself
 *
 * For every active filter and every store:
 *
 *  1. [FilterTier.STORE_SIDE] — the store declares it in [StoreCapabilities.supportedFilters]: it
 *     is passed along and the store applies it, over the whole set and before pagination;
 *  2. [FilterTier.CLIENT_SIDE] — the store cannot do it, but the field is present on **every** row
 *     ([StoreCapabilities.clientFilters], which the contract test verifies against real fixtures):
 *     the store is queried and the rows are discarded here;
 *  3. [FilterTier.UNSUPPORTED] — neither: **the store is not queried at all**, and that is written
 *     next to the results.
 *
 * The third tier is why this file exists. The obvious alternative — letting the results of
 * whoever cannot filter through untouched — produces a list containing exactly what the filter
 * says it excluded, with no row distinguishing them. Not querying the store instead costs one
 * fewer request to a third-party site and gives a fact to show.
 *
 * ### What this mechanism does **not** govern, and why
 *
 * - **[SearchFilters.includeNsfw]**. Its safe value is the neutral one, so "active" would be the
 *   normal case: every search would exclude the eight stores that do not label adult content,
 *   i.e. almost the whole app, over a setting nobody touched. A filter that reads a label does not
 *   protect against a source that does not label, and the setting's description says so.
 * - **[SearchFilters.sort]**. An ordering excludes nothing: a store that cannot sort still
 *   contributes its rows, and the aggregated list puts them in order. Excluding a store because it
 *   cannot sort would remove results over a question that removes none.
 */
object FilterPlan {

    /**
     * The capabilities [filters] demands of a store, i.e. the **active** filters.
     *
     * A filter at its neutral value does not appear: there is nothing to apply, so there is
     * nothing a store could fail to do.
     */
    fun required(filters: SearchFilters): Set<FilterCapability> = buildSet {
        if (filters.contentKind != null) add(FilterCapability.CONTENT_KIND)
        if (filters.minRating != null) add(FilterCapability.MIN_RATING)
        if (filters.categories.isNotEmpty()) add(FilterCapability.CATEGORY)
        if (filters.maxMinSdk != null) add(FilterCapability.MIN_SDK)
        if (filters.excludeAntiFeatures.isNotEmpty()) add(FilterCapability.ANTI_FEATURES)
    }

    /**
     * Who applies [capability] on this store.
     *
     * [servedByIndex] distinguishes the two ways a locally-indexed store answers, and the
     * distinction is not theoretical: F-Droid declares seven filters in
     * [StoreCapabilities.supportedFilters] and **the index** applies them, with a SQL query. Until
     * the index has been downloaded the fallback search answers, which is the ten-result remote
     * API and accepts no filters — so in that window the same store sits in the third tier, not
     * the first. Reading the capability alone would announce a filter that is never applied.
     */
    fun tier(
        capability: FilterCapability,
        capabilities: StoreCapabilities,
        servedByIndex: Boolean,
    ): FilterTier = when {
        capabilities.searchSource == SearchSource.LOCAL_INDEX ->
            if (servedByIndex && capability in capabilities.supportedFilters) {
                FilterTier.STORE_SIDE
            } else {
                FilterTier.UNSUPPORTED
            }

        capability in capabilities.supportedFilters -> FilterTier.STORE_SIDE
        capability in capabilities.clientFilters -> FilterTier.CLIENT_SIDE
        else -> FilterTier.UNSUPPORTED
    }

    /**
     * The active filters this store cannot apply in any way. Empty = it is queried.
     */
    fun unsupported(
        filters: SearchFilters,
        capabilities: StoreCapabilities,
        servedByIndex: Boolean,
    ): Set<FilterCapability> =
        required(filters).filterTo(LinkedHashSet()) {
            tier(it, capabilities, servedByIndex) == FilterTier.UNSUPPORTED
        }

    /**
     * Applies to the rows the filters it falls to us to apply.
     *
     * Only those in [FilterTier.CLIENT_SIDE]: redoing here the work of whoever already filtered
     * store-side would be harmless to the result and harmful to the diagnosis — a row discarded
     * twice for different reasons is indistinguishable from a row discarded wrongly.
     *
     * A row missing the field **does not pass**. That is what a filter means, and the census is
     * why it never happens here: this point is reached only for stores where the field is on every
     * row.
     */
    fun applyClientSide(
        rows: List<StoreListingSummary>,
        filters: SearchFilters,
        capabilities: StoreCapabilities,
        servedByIndex: Boolean,
    ): List<StoreListingSummary> {
        val mine = required(filters).filter {
            tier(it, capabilities, servedByIndex) == FilterTier.CLIENT_SIDE
        }
        if (mine.isEmpty()) return rows
        return rows.filter { row -> mine.all { it.accepts(row, filters) } }
    }

    /**
     * Whether a row satisfies this filter. **A missing field never satisfies it.**
     *
     * A missing field does not occur here, and the census guarantees it: this function is reached
     * only for stores declaring the filter in [StoreCapabilities.clientFilters], i.e. only for
     * those where the contract test verified the field is on **every** row of the fixtures. If a
     * store ever stopped publishing it, the row would vanish rather than pass unnoticed — and the
     * contract test would be red first, on the updated fixture.
     */
    private fun FilterCapability.accepts(row: StoreListingSummary, filters: SearchFilters): Boolean =
        when (this) {
            FilterCapability.CONTENT_KIND -> row.contentKind == filters.contentKind
            FilterCapability.MIN_RATING -> {
                val rating = row.rating
                rating != null && rating >= (filters.minRating ?: 0f)
            }

            FilterCapability.CATEGORY -> row.categories.any { it in filters.categories }
            // The others do not apply to a list row: `minSdk` and the anti-features live in
            // `AppVersion`, and the `SORT_*` values are not filters. No store can therefore
            // declare them in `clientFilters`, and the contract test demands it — this branch is
            // unreachable and returns the value that removes nothing.
            else -> true
        }
}

/** Who applies a filter on a given store. See [FilterPlan]. */
enum class FilterTier {
    /** The store applies it, over the whole set. */
    STORE_SIDE,

    /** We apply it to the rows that come back, because the field is on all of them. */
    CLIENT_SIDE,

    /** Neither: the store is not queried, and that is declared. */
    UNSUPPORTED,
}
