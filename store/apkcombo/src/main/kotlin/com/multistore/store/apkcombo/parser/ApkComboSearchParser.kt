package com.multistore.store.apkcombo.parser

import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.ApkComboRefs
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseHtml

/**
 * apkcombo's search page.
 *
 * ### Two things this page does that a naive parser gets wrong
 *
 * **1. apkcombo searches by substring.** A nonsense query still returns nine apps because it
 * contains a real word. Not a parser problem — the results are genuine — but it explains why the
 * "no results" fixture uses a token containing no real substring: a query that *looks* absurd is
 * not absurd enough to exercise the empty branch.
 *
 * **2. A row is accepted for the shape of its URL, not for its class.** On today's fixtures all
 * twenty candidates are apps — verified, not assumed — so the ref check discards nothing right
 * now. It is there because a two-segment path is also the shape of category and tag pages: the day
 * apkcombo put a category shortcut among the results, without that check a "Communication" entry
 * with a ref leading nowhere would appear among them.
 */
internal class ApkComboSearchParser(private val config: ApkComboConfig) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            val items = document.all(config.selectors.searchItem)
                .mapRowsOrFail(config.selectors.searchItem, ::summaryOf)
            PagedResult(
                items = items,
                page = page,
                // **apkcombo's pagination does not exist**, and it is said here because this is
                // the only place that knows. Measured on 24/08/2026: later pages return the
                // **same twenty results** as the first. Claiming more would give an infinite
                // scroll repeating the same apps forever.
                hasMore = false,
                totalCount = items.size,
            )
        }

    private fun summaryOf(item: HtmlPage): StoreListingSummary? {
        val href = item.ownAbsUrlOrNull("href") ?: return null
        val ref = ApkComboRefs.refFromUrl(href) ?: return null
        val title = item.textOrNull(config.selectors.searchName) ?: return null
        val author = item.textOrNull(config.selectors.searchAuthor)
        val spans = item.all(config.selectors.searchDescriptionSpan).mapNotNull { it.ownTextOrNull() }

        return StoreListingSummary(
            storeId = StoreId.APKCOMBO,
            ref = ref,
            title = title,
            packageName = ApkComboRefs.packageNameOf(ref),
            developer = author?.substringBefore(AUTHOR_SEPARATOR)?.trim()?.takeIf { it.isNotBlank() },
            iconUrl = iconOf(item),
            categories = author?.substringAfter(AUTHOR_SEPARATOR, "")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::listOf)
                .orEmpty(),
            rating = spans.firstNotNullOfOrNull { span ->
                span.takeIf { RATING_MARK in it }?.let(TextValues::rating)
            },
            downloadsLabel = spans.firstOrNull { RATING_MARK !in it && !it.isSize() },
        )
    }

    /**
     * The icon lives in the lazy-load attribute, not in `src`.
     *
     * apkcombo lazy-loads its images: `src` is always a transparent pixel. Taking it would give a
     * list of identical empty icons — a defect visible only on screen, never in a test checking
     * "the URL is not null".
     */
    private fun iconOf(item: HtmlPage): String? =
        item.oneOrNull(config.selectors.searchIcon)?.let { image ->
            image.ownAttrOrNull("data-src") ?: image.ownAttrOrNull("src")?.takeIf { PLACEHOLDER !in it }
        }

    private fun String.isSize(): Boolean = TextValues.byteSize(this) != null

    private companion object {
        const val AUTHOR_SEPARATOR = "·"
        const val RATING_MARK = "★"
        const val PLACEHOLDER = "1.gif"
    }
}
