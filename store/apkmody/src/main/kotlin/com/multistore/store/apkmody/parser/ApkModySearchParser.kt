package com.multistore.store.apkmody.parser

import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.ApkModyRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseHtml

/**
 * apkmody's search page.
 *
 * ### Three measured things a naive parser gets wrong
 *
 * **1. The "no results" page still contains links to real apps.** The results container stays
 * empty, but the footer keeps "Trending" and "Latest" with hrefs of the same shape as results.
 * What excludes them is the card selector, which the footer does not have — measured, not assumed:
 * removing the container from the selector leaves the suite green, removing the card selector does
 * not. The container stays because the only alternative is betting that the footer keeps being made
 * of list items.
 *
 * **2. The search is fuzzy, not by substring.** A string of repeated letters returns twenty apps;
 * another returns exactly one; nonsense returns twenty more. Finding a query that gives zero
 * results requires characters appearing in no title: hence the Georgian fixture.
 *
 * **3. The card's image is not the app's icon.** It is a **cover** at 360×180, and of twenty
 * results eighteen are the site's placeholder; of the remaining two neither is an icon — they are
 * YouTube frames. Putting it in the icon field would mean showing a video frame in place of the
 * icon, which is worse than showing nothing. The real icon is on the listing.
 */
internal class ApkModySearchParser(private val config: ApkModyConfig) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            val items = document.all(config.selectors.searchItem)
                .mapRowsOrFail(config.selectors.searchItem, ::summaryOf)
            PagedResult(
                items = items,
                page = page,
                // **Pagination does not exist**, and that is measured: a page parameter returns
                // the same bytes as the first page, and the path form answers 404. Claiming more
                // would give an infinite scroll over the same apps.
                hasMore = false,
                totalCount = items.size,
            )
        }

    private fun summaryOf(item: HtmlPage): StoreListingSummary? {
        val href = item.ownAttrOrNull("href") ?: return null
        val ref = ApkModyRefs.refFromUrl(href) ?: return null
        val title = item.textOrNull(config.selectors.searchName) ?: return null
        val excerpt = item.textOrNull(config.selectors.searchExcerpt)

        return StoreListingSummary(
            storeId = StoreId.APKMODY,
            ref = ref,
            title = title,
            // apkmody publishes the `packageName` **only** on the listing, in its information
            // table. Here it is absent in any form: leaving it null is what makes the
            // `providesPackageName = false` capability true.
            packageName = null,
            iconUrl = null,
            contentKind = ApkModyRefs.contentKindOf(ref),
            latestVersionName = versionNameOf(excerpt),
            // What follows the bullet is the list of the modification's changes: not a category,
            // but the only line saying *what differs* from the original — and in a store aggregator
            // that is the information distinguishing this entry from the other eight.
            summary = com.multistore.core.model.LocalizedText.of(modFeaturesOf(excerpt)),
        )
    }

    /** `v9.1.36.1948 • Download Music Offline` -> `9.1.36.1948`. */
    private fun versionNameOf(excerpt: String?): String? =
        excerpt?.substringBefore(SEPARATOR)?.trim()?.removePrefix(VERSION_PREFIX)
            ?.trim()?.takeIf { it.isNotBlank() }

    /** `v9.1.36.1948 • Download Music Offline` -> `Download Music Offline`. */
    private fun modFeaturesOf(excerpt: String?): String? =
        excerpt?.substringAfter(SEPARATOR, "")?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val SEPARATOR = "•"
        const val VERSION_PREFIX = "v"
    }
}
