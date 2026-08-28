package com.multistore.store.api

import com.multistore.core.model.ContentKind
import com.multistore.core.model.SearchSort

/**
 * A search's filters.
 *
 * An adapter applies only those it declares in [StoreCapabilities.supportedFilters] and
 * **silently ignores** the rest: the caller, which knows the capabilities, decides whether an
 * inapplicable filter should be hidden or reported. An adapter that failed on a filter it cannot
 * handle would hold the aggregated search hostage to the least capable store.
 *
 * [FilterPlan] decides what to do about it, and "silently ignore" no longer happens: a store that
 * cannot apply an active filter is not queried at all, and its absence is declared.
 */
data class SearchFilters(
    val contentKind: ContentKind? = null,
    val categories: Set<String> = emptySet(),
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** Excludes apps that would not run on this device. */
    val maxMinSdk: Int? = null,
    /**
     * The minimum rating, on the 0–5 scale every adapter maps its own onto.
     *
     * Null means "any rating, **including none**": it is the neutral value, and must stay so. A
     * minimum of zero would not be the same thing — it would still require a rating to exist, i.e.
     * exclude five stores out of nine without saying so.
     */
    val minRating: Float? = null,
    /** Anti-feature identifiers to exclude (e.g. `Tracking`, `Ads`). */
    val excludeAntiFeatures: Set<String> = emptySet(),
    /** If `false`, results also include betas and non-default channels. */
    val onlyDefaultChannel: Boolean = true,
    /**
     * If `false` — the default — results exclude what the store labels as adult.
     *
     * **The default is the safe one, and that is not a matter of style.** `SearchFilters.NONE` is
     * the value that ends up in every call that says nothing, tests and code written six months
     * from now included: if the neutral value were "show everything", every new caller would start
     * by overriding the user's setting, and no compiler would say so. It is proto3's zero-value
     * rule applied to a data class.
     *
     * Only stores declaring [FilterCapability.NSFW_CONTENT] apply it. The others **silently
     * ignore** it, like any unsupported filter — but here the silence weighs differently, and it
     * is written in `settings_search_nsfw_description`: the app hides what the store declares
     * adult, not what is.
     *
     * **It is also the only filter [FilterPlan] does not govern**, on purpose: see the note there.
     */
    val includeNsfw: Boolean = false,
) {
    companion object {
        val NONE: SearchFilters = SearchFilters()
    }
}
