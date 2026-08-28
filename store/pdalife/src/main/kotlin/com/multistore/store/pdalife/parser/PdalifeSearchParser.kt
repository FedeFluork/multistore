package com.multistore.store.pdalife.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseHtml
import com.multistore.store.pdalife.PdalifeConfig
import com.multistore.store.pdalife.PdalifeRefs

/**
 * The `/search/{slug}/` page, which is pdalife's real search.
 *
 * Twenty results per page and a pagination the page itself declares. Why not `/suggest/`: see the
 * note at the head of [PdalifeConfig].
 *
 * ### Two ways of having no results, and only one looks like a fault
 *
 * On an empty search pdalife emits the container all the same, and puts **one row** inside it:
 *
 * ```html
 * <ul class="catalog-list"> <li class="catalog-item"> Oops, maybe try another request? </li> </ul>
 * ```
 *
 * The dangerous case, though, is not this one — that row contains no title, so the selector does
 * not take it anyway. It is the other: a page **full** of results that are not for Android.
 * `/search/procreate/` has twenty, all iOS. If those rows came in and were discarded afterwards,
 * [mapRowsOrFail] would say — rightly, from its point of view — `ParseFailure`, i.e. "this store
 * broke" instead of "there is nothing for Android". That is why the OS filter sits **in the
 * selector**.
 *
 * Telling the two situations apart is exactly what [mapRowsOrFail] exists for, and keeping that
 * alive means not letting rows that were never ours reach the count.
 */
internal class PdalifeSearchParser(private val config: PdalifeConfig) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            val items = document.all(config.selectors.searchItem)
                .mapRowsOrFail(config.selectors.searchItem, ::summaryOf)
            PagedResult(
                items = items,
                page = page,
                hasMore = hasMore(document),
            )
        }

    /**
     * Whether there is another page, **read** rather than deduced.
     *
     * `<div class="catalog__more-button" data-max_page="2" data-current_page="1">`, and `-1` on the
     * page with no results. Deducing it from the page being full would cost an empty request
     * whenever the total is a multiple of twenty — and on this store it would also be **wrong**,
     * because the list the adapter returns is already filtered by OS: on "minecraft" the first page
     * has twenty rows and eighteen results, so "full" and "there is another" are no longer the same
     * thing.
     *
     * If the attributes were gone the outcome is `false`, not an error: a pagination that
     * disappears degrades to "a single page", which is how half the stores we aggregate behave.
     */
    private fun hasMore(document: HtmlPage): Boolean {
        val pager = document.oneOrNull(config.selectors.searchPager) ?: return false
        val max = pager.ownAttrOrNull(MAX_PAGE)?.toIntOrNull() ?: return false
        val current = pager.ownAttrOrNull(CURRENT_PAGE)?.toIntOrNull() ?: return false
        return current < max
    }

    private fun summaryOf(item: HtmlPage): StoreListingSummary? {
        val href = item.absUrlOrNull(config.selectors.searchLink, HREF) ?: return null
        val ref = PdalifeRefs.refFromUrl(href) ?: return null
        val title = item.textOrNull(config.selectors.searchLink) ?: return null

        return StoreListingSummary(
            storeId = StoreId.PDALIFE,
            ref = ref,
            title = title,
            // The `packageName` is **only** on the listing, and only when the app is on Play: it
            // never appears among the results. See `PdalifeSelectors.detailPlayLink`.
            packageName = null,
            summary = LocalizedText.of(item.textOrNull(config.selectors.searchDescription)),
            iconUrl = item.absUrlOrNull(config.selectors.searchIcon, SRC),
            categories = listOfNotNull(item.textOrNull(config.selectors.searchCategory)),
            // `v9.7.3`: the `v` is their template's typography, not part of the number.
            latestVersionName = item.textOrNull(config.selectors.searchVersion)
                ?.removePrefix(VERSION_PREFIX)
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            // No version code anywhere on the site. `data-version_id` is not one: see
            // `PdalifeDetailParser`.
            latestVersionCode = null,
            rating = ratingOf(item),
        )
    }

    /**
     * The rating, out of ten and with zero meaning "none".
     *
     * `<div class="rating-circle rating-circle_size_small rating-circle_rating_9">9</div>`, and the
     * block **is always there**: apps with no votes show `0`. The scale the listing declares starts
     * at one (`<meta itemprop='worstRating' content='1'/>`), so zero is not a very low score — it
     * is the absence of votes. Reporting it as `0.0` would tell the user that app has been judged
     * terrible.
     */
    private fun ratingOf(item: HtmlPage): Float? {
        val raw = item.textOrNull(config.selectors.searchRating) ?: return null
        return TextValues.rating(raw, config.ratingScale)?.takeIf { it > 0f }
    }

    private companion object {
        const val HREF = "href"
        const val SRC = "src"
        const val VERSION_PREFIX = "v"
        const val MAX_PAGE = "data-max_page"
        const val CURRENT_PAGE = "data-current_page"
    }
}
