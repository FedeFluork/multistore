package com.multistore.store.apkmirror.parser

import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.ApkMirrorRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseHtml

/**
 * apkmirror's search page.
 *
 * ### The trap, and why the "no results" fixture is the most important of all
 *
 * apkmirror renders every page with six sidebar widgets using **the identical markup of results**.
 * On the page of a query that finds nothing there are **38** of them. A parser looking for that
 * class across the document would return 38 arbitrary apps, all real and all wrong, and no
 * assertion about "the elements have a title" would notice.
 *
 * The right container is the **first** list widget inside the search area: on the fixture with
 * results it contains exactly the 10 real ones, on the empty one zero.
 *
 * ### The second trap: not every row is an app
 *
 * The search also returns **releases** of apps whose title contains the term. They are told apart
 * by the number of segments, not by the markup: three means app, four means release. See
 * [ApkMirrorRefs].
 */
internal class ApkMirrorSearchParser(private val config: ApkMirrorConfig) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            val container = document.all(config.selectors.searchResults).firstOrNull()
            val rows = container?.all(config.selectors.searchRow).orEmpty()
            val items = rows.mapRowsOrFail(config.selectors.searchRow, ::summaryOf)
            PagedResult(
                items = items,
                page = page,
                // apkmirror really paginates. A full page probably means there is another; the
                // store does not declare the exact total.
                hasMore = items.size >= PAGE_SIZE,
            )
        }

    private fun summaryOf(row: HtmlPage): StoreListingSummary? {
        val link = row.oneOrNull(config.selectors.searchTitleLink) ?: return null
        val href = link.ownAbsUrlOrNull("href") ?: return null
        val ref = ApkMirrorRefs.appRefFromUrl(href) ?: return null
        val title = link.ownTextOrNull() ?: return null

        return StoreListingSummary(
            storeId = StoreId.APKMIRROR,
            ref = ref,
            title = title,
            // apkmirror does **not** publish the packageName in search results: it is only on the
            // listing, in the Play Store link. Leaving it null is why this adapter's
            // `providesPackageName` capability cannot be `true`.
            packageName = null,
            developer = row.textOrNull(config.selectors.searchDeveloper)
                ?.removePrefix(DEVELOPER_PREFIX)
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            iconUrl = iconOf(row),
            lastUpdated = TextValues.utcDateTime(
                row.attrOrNull(config.selectors.searchDate, "data-utcdate"),
            ),
        )
    }

    /**
     * The real icon, not the resizer that wraps it.
     *
     * apkmirror serves images through a resize endpoint: passing that link to the image loader
     * would download a 32-pixel thumbnail and show it enlarged on a 3x screen. The original URL is
     * already in the query.
     */
    private fun iconOf(row: HtmlPage): String? {
        val raw = row.absUrlOrNull(config.selectors.searchIcon, "src") ?: return null
        return Urls.queryParam(raw, RESIZE_PARAM) ?: raw
    }

    private companion object {
        const val DEVELOPER_PREFIX = "by "
        const val RESIZE_PARAM = "src"

        /** apkmirror serves ten results per page: measured on the fixture. */
        const val PAGE_SIZE = 10
    }
}
