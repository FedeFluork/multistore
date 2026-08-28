package com.multistore.store.uptodown.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseHtml
import com.multistore.store.uptodown.UptodownConfig
import com.multistore.store.uptodown.UptodownRefs

/**
 * uptodown's search page.
 *
 * ### The fixture worth more than all the others
 *
 * On a query with no results uptodown emits **no** `#content-list` at all. In its place it puts
 * `<section class="notice">Oops, we couldn't find any matching programs for "…"</section>` and
 * then, under the heading "Apps you're gonna love", **twelve cards with markup identical to the
 * results'** — `div.item`, `data-code`, icon, `.name a > h2`, `.description`. Telegram is among
 * them.
 *
 * A parser anchored on `.item` would therefore answer with twelve apps, always the same, to any
 * search that finds nothing. With nine stores queried together, an invented result is worse than no
 * result: the aggregation has no way of knowing those apps have nothing to do with the question.
 * The container is the only thing separating the two cases, and that is why it is in the selector.
 *
 * ### Pagination does not exist
 *
 * `?page=2` returns **the same 36 apps** as the first page, in a different order: the set of hrefs
 * is identical, and so is the byte count. The order changes on every request because it is
 * randomised server-side among equally scored results — the same trap seen on pdalife with
 * advertising slots, here applied to the results.
 */
internal class UptodownSearchParser(
    private val config: UptodownConfig,
    private val refs: UptodownRefs,
) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            val items = document.all(config.selectors.searchItem)
                .mapRowsOrFail(config.selectors.searchItem, ::summaryOf)
            PagedResult(
                items = items,
                page = page,
                hasMore = false,
                totalCount = items.size,
            )
        }

    private fun summaryOf(item: HtmlPage): StoreListingSummary? {
        val href = item.absUrlOrNull(config.selectors.searchLink, "href") ?: return null
        val ref = refs.refFromUrl(href) ?: return null
        val title = item.textOrNull(config.selectors.searchTitle) ?: return null

        return StoreListingSummary(
            storeId = StoreId.UPTODOWN,
            ref = ref,
            title = title,
            // uptodown publishes the package only in the listing, in the "Technical details" table.
            // It is not in the results in any form.
            packageName = null,
            summary = LocalizedText.of(item.textOrNull(config.selectors.searchDescription)),
            developer = item.textOrNull(config.selectors.searchAuthor),
            iconUrl = item.absUrlOrNull(config.selectors.searchIcon, "src"),
        )
    }
}
