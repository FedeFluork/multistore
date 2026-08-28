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
 * The downloads chart, `/android/top`.
 *
 * ### The rank sits **inside** the title
 *
 * uptodown writes `<h2>1. Uptodown App Store</h2>`: the number is not an attribute, not an element
 * of its own, it is the first characters of the name. Reading the title as-is yields an app called
 * "1. Uptodown App Store" — a title that does not match the listing's, therefore two different apps
 * for `IdentityMatcher`, therefore the same app twice on the Home screen.
 *
 * Arrival order alone would not be enough to replace it: it would be the same until the markup
 * changes, and would become silently wrong the day the page inserts a promotional row in the
 * middle. The declared number instead says **which** rank it is, and if it disappeared the title
 * would not be damaged.
 *
 * ### The first entry is uptodown's own app
 *
 * Measured: rank 1 is "Uptodown App Store". It is not discarded — it is a real app, with its own
 * listing and its own APK, and removing a row because it belongs to the site's operator would be an
 * editorial decision that is not ours to make. It should be known, though, that *that* rank is not
 * a measure of popularity, and it is one more form of the rule "on a store, the first is never the
 * answer".
 */
internal class UptodownTopParser(
    private val config: UptodownConfig,
    private val refs: UptodownRefs,
) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            val items = document.all(config.selectors.topItem)
                .mapRowsOrFail(config.selectors.topItem, ::summaryOf)
            PagedResult(items = items, page = page, hasMore = false, totalCount = items.size)
        }

    private fun summaryOf(item: HtmlPage): StoreListingSummary? {
        val href = item.absUrlOrNull(config.selectors.searchLink, "href") ?: return null
        val ref = refs.refFromUrl(href) ?: return null
        val title = stripRank(item.textOrNull(config.selectors.searchTitle)) ?: return null

        return StoreListingSummary(
            storeId = StoreId.UPTODOWN,
            ref = ref,
            title = title,
            // As in search: uptodown publishes the package only in the listing.
            packageName = null,
            summary = LocalizedText.of(item.textOrNull(config.selectors.topDescription)),
            iconUrl = item.absUrlOrNull(config.selectors.searchIcon, "src"),
        )
    }

    /**
     * `1. Uptodown App Store` -> `Uptodown App Store`.
     *
     * The dot after the number is mandatory in the pattern: without it, an app called `1917` would
     * lose its own name. With the dot, the ambiguous case would be a title like `1. Battle` —
     * which exists in none of the ten measured entries, and which would in any case lose only a
     * prefix, not the name.
     */
    private fun stripRank(raw: String?): String? =
        raw?.replace(RANK_PREFIX, "")?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        val RANK_PREFIX = Regex("""^\d{1,3}\.\s+""")
    }
}
