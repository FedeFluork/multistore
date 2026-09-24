package com.multistore.store.an1.parser

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.an1.An1Config
import com.multistore.store.an1.An1Refs
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseHtml

/**
 * an1's search page.
 *
 * Unlike uptodown and apkmody, the empty page does **not** fill the same markup with suggested
 * cards: the empty-search fixture contains zero occurrences of the row class and no section
 * reusing it, so the selector can stay anchored to the row. That is verified, not inferred from a
 * green suite.
 *
 * What is always needed is the distinction between "there was nothing" and "I could not read it":
 * without it a dead row selector would answer "no results" to every query.
 *
 * Pagination is real — measured: page 1 = 10 results, page 2 = 4, no overlap — but past the last
 * page DLE returns zero rows rather than an error, so "is there more" is inferred from the page
 * being full. That costs at most one empty request when the total is an exact multiple of ten.
 */
internal class An1SearchParser(private val config: An1Config) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            // The reworks, first, so a row can say whether an1 marked it. `all` and not
            // `mapRowsOrFail`: a page with no marked row is the ordinary case — 0 of 4 on the
            // page-2 fixture — and failing there would turn "nothing modified here" into a parse
            // error.
            val modRefs = document.all(config.selectors.searchModItem)
                .mapNotNull { it.absUrlOrNull(config.selectors.searchLink, "href") }
                .mapNotNull(An1Refs::refFromUrl)
                .toSet()
            val items = document.all(config.selectors.searchItem)
                .mapRowsOrFail(config.selectors.searchItem) { summaryOf(it, modRefs) }
            PagedResult(
                items = items,
                page = page,
                hasMore = items.size >= config.resultsPerPage,
            )
        }

    private fun summaryOf(item: HtmlPage, modRefs: Set<StoreAppRef>): StoreListingSummary? {
        val href = item.absUrlOrNull(config.selectors.searchLink, "href") ?: return null
        val ref = An1Refs.refFromUrl(href) ?: return null
        val title = item.textOrNull(config.selectors.searchLink) ?: return null

        return StoreListingSummary(
            storeId = StoreId.AN1,
            ref = ref,
            title = title,
            // Zero package names across the whole site: not a gap in the search results but a
            // property of an1. See the note atop `An1Config`.
            packageName = null,
            developer = item.textOrNull(config.selectors.searchDeveloper),
            iconUrl = item.absUrlOrNull(config.selectors.searchIcon, "src"),
            // The rating sits in the `li`'s text, not in an attribute. The percentage width says the
            // same thing graphically, and reading it from the style would mean interpreting CSS.
            rating = TextValues.rating(item.textOrNull(config.selectors.searchRating)),
            declaredModified = ref in modRefs,
        )
    }
}
