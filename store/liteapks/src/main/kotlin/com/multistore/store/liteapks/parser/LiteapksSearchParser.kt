package com.multistore.store.liteapks.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseHtml
import com.multistore.store.liteapks.LiteapksConfig
import com.multistore.store.liteapks.LiteapksRefs
import com.multistore.store.liteapks.LiteapksSelectors

/**
 * The results page: `/?s={query}` and `/?s={query}&paged={n}`.
 *
 * ### "No results" here is not an empty container
 *
 * On liteapks a search with no results does not produce a `div#apps-grid` with nothing inside: the
 * container **does not exist**, and there is not a single `<article>` on the whole page. As seen by
 * the parser, that page is indistinguishable from one where the row selector is dead — which is
 * exactly the confusion `mapRowsOrFail` exists to prevent one level down.
 *
 * The distinction is made by [LiteapksSelectors.searchTitle]: `h1#search-title` is **always** on a
 * search page, with or without results, and is on no other page of the site (verified on listings,
 * home and 404). If it is missing, the page is not the one we thought and the outcome is
 * `ParseFailure`; if it is there and the rows are zero, the search found nothing.
 */
internal class LiteapksSearchParser(private val config: LiteapksConfig) {

    fun parse(html: String, baseUrl: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, baseUrl) { document ->
            // Before reading the rows: **is this a search page?** See the note at the head.
            val title = document.one(config.selectors.searchTitle)
            val declared = TextValues.parenthesizedCode(title.textOrNull())?.toInt()

            val rows = document.all(config.selectors.searchItem)
            val items = rows.mapRowsOrFail(LiteapksSelectors.SEARCH_ITEM_DEFAULT, ::summaryOf)

            PagedResult(
                items = items,
                page = page,
                hasMore = hasMore(page, items.size, declared),
                totalCount = declared,
            )
        }

    /**
     * Whether there is another page, without asking the store.
     *
     * Three conditions, and the injection says which bear the weight:
     *
     *  1. **the declared total, while it is true** — `telegram` says `(7)` and the rows are 7, so
     *     `hasMore` is `false` already on the first page. This is what stops small searches;
     *  2. **the cap of sixty**, for large searches: above it the total saturates and says nothing
     *     more. `game` gives 18, 18, 18, 6 and stops at the fourth, which is exactly where the site
     *     answers 404;
     *  3. "the page is not full" — **and this one stops nothing today**, because the cases where it
     *     would intervene are already stopped by the first two. Removing it leaves the suite green.
     *     It stays because it covers the one case the other two do not see — a total the site
     *     stopped declaring, with a short page — and because it costs one comparison.
     */
    private fun hasMore(page: Int, found: Int, declared: Int?): Boolean {
        if (found < config.resultsPerPage) return false
        val seen = (page + 1) * config.resultsPerPage
        if (declared != null && declared < config.searchResultCap) return seen < declared
        return seen < config.searchResultCap
    }

    /**
     * A card.
     *
     * Every field is tolerant except the link and the name: without the first there is nothing to
     * open, without the second nothing to show. Across 75 real cards none lacks an icon, a version
     * or a rating — but one malformed card in seventy-five must not make the other seventy-four
     * disappear, and that is why the rest is `…OrNull`.
     */
    private fun summaryOf(row: HtmlPage): StoreListingSummary? {
        val url = row.absUrlOrNull(config.selectors.searchLink, HREF) ?: return null
        val ref = LiteapksRefs.refFromUrl(url) ?: return null
        val name = row.textOrNull(config.selectors.searchName) ?: return null

        return StoreListingSummary(
            storeId = StoreId.LITEAPKS,
            ref = ref,
            title = name,
            // liteapks publishes the packageName nowhere in the card. It is often on the listing,
            // but capabilities are checked **against the search results**: see
            // `providesPackageName` in the adapter.
            packageName = null,
            // The MOD traits are the only description the card carries, and they are what separates
            // two otherwise identical entries: `Premium, Lite, No ADS`.
            summary = row.textOrNull(config.selectors.searchModTraits)
                ?.let(LocalizedText::of)
                ?: LocalizedText.EMPTY,
            iconUrl = row.absUrlOrNull(config.selectors.searchIcon, SRC),
            latestVersionName = versionOf(row),
            // No store of the nine publishes a version code less often than this one: here it does
            // not exist, not in the card, not in the listing, not on the CDN. `VersionSelection`
            // will answer `UpToDate(comparable = false)`, which is the truth.
            latestVersionCode = null,
            rating = TextValues.rating(row.attrOrNull(config.selectors.searchRating, ARIA_LABEL)),
        )
    }

    /** `<span aria-label="Version 12.10.1">v12.10.1</span>` -> `12.10.1`. */
    private fun versionOf(row: HtmlPage): String? =
        row.textOrNull(config.selectors.searchVersion)?.removePrefix(VERSION_PREFIX)?.takeIf { it.isNotBlank() }

    private companion object {
        const val HREF = "href"
        const val SRC = "src"
        const val ARIA_LABEL = "aria-label"
        const val VERSION_PREFIX = "v"
    }
}
