package com.multistore.store.apkmody.parser

import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.ApkModyRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.parseFailed
import com.multistore.store.common.html.parseHtml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `/popular` chart, read from the structured-data list block.
 *
 * ### Why from the structured data and not from the cards
 *
 * Because the structured data **declares the position** and the cards do not. The block carries
 * twelve entries, each with a position, a name and a URL. From the cards it would be inferred from
 * arrival order, which is the same thing until the theme inserts a promotional row in the middle —
 * and on a store that lives on advertising that is the case to expect, not the exception.
 *
 * The same choice already made on liteapks, for the same reason: schema.org is a stable
 * vocabulary, a utility CSS class is a styling decision.
 *
 * ### The page does not paginate
 *
 * A page parameter returns **the same bytes**. The chart is twelve entries and ends there.
 */
internal class ApkModyPopularParser(private val config: ApkModyConfig) {

    fun parse(html: String, url: String, page: Int): StoreResult<PagedResult<StoreListingSummary>> =
        parseHtml(html, url) { document ->
            val entries = itemList(document)
                ?: document.parseFailed(config.selectors.popularJsonLd)
            val icons = iconsByRef(document)
            val items = entries.mapNotNull { summaryOf(it, icons) }
            // The same distinction the row mapper makes, written by hand because that works on
            // HTML nodes and these are JSON entries: an **empty** list is an empty chart, a list
            // full of unreadable entries is a parse failure. Confusing them would answer "no
            // popular apps" to a format change.
            if (entries.isNotEmpty() && items.isEmpty()) document.parseFailed(config.selectors.popularJsonLd)
            PagedResult(items = items, page = page, hasMore = false, totalCount = items.size)
        }

    /**
     * The first `ld+json` block that is a list, chosen by type and not by position.
     *
     * The page publishes more than one — a site block, a breadcrumb block and this — and taking the
     * first would give an object without list elements, i.e. **zero entries** instead of an error.
     * The same distinction that on liteapks separated the SEO plugin's graph from the application
     * block.
     */
    private fun itemList(document: HtmlPage): List<Entry>? =
        document.all(config.selectors.popularJsonLd)
            .asSequence()
            .mapNotNull { it.dataOrNull() }
            .mapNotNull(::entriesIn)
            .firstOrNull()

    private fun entriesIn(raw: String): List<Entry>? {
        val root = runCatching { LENIENT.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (root.string(TYPE) != ITEM_LIST) return null
        val elements = root[ELEMENTS] as? JsonArray ?: return null
        return elements.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string(NAME) ?: return@mapNotNull null
            val href = item.string(URL) ?: return@mapNotNull null
            Entry(name = name, url = href)
        }
    }

    private fun summaryOf(entry: Entry, icons: Map<String, String>): StoreListingSummary? {
        val ref = ApkModyRefs.refFromUrl(entry.url) ?: return null
        val title = stripSuffix(entry.name) ?: return null
        return StoreListingSummary(
            storeId = StoreId.APKMODY,
            ref = ref,
            title = title,
            iconUrl = icons[ref.value],
            contentKind = ApkModyRefs.contentKindOf(ref),
        )
    }

    /**
     * `ref -> icon`, read from the cards next to the chart.
     *
     * The link between the two sources is the **ref**, not the position: they are the same list
     * written twice on the same page, and pairing them by index would attach the wrong icon the day
     * the theme slips a promotional card in the middle — exactly the case the chart is read from
     * structured data for.
     *
     * A card without an icon, or a ref the structured data does not name, is not a failure: the
     * entry keeps the placeholder, which is what this page did for all twelve before.
     */
    private fun iconsByRef(document: HtmlPage): Map<String, String> =
        document.all(config.selectors.popularCard).mapNotNull { card ->
            val ref = card.ownAttrOrNull(HREF)?.let(ApkModyRefs::refFromUrl) ?: return@mapNotNull null
            val icon = card.absUrlOrNull(config.selectors.popularCardIcon, SRC) ?: return@mapNotNull null
            ref.value to icon
        }.toMap()

    /**
     * `YouTube Premium Mod APK` -> `YouTube Premium`.
     *
     * The suffix is an SEO label apkmody attaches to **all twelve** entries on this page and which
     * does not appear in the listing's title. Keeping it would give a title different from the one
     * the same store publishes elsewhere — i.e. two apps to the identity matcher, and the same app
     * twice on Home as soon as another store names it.
     */
    private fun stripSuffix(raw: String): String? =
        raw.replace(SEO_SUFFIX, "").trim().takeIf { it.isNotBlank() }

    private data class Entry(val name: String, val url: String)

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
        val SEO_SUFFIX = Regex("""\s+MOD\s+APK\s*$""", RegexOption.IGNORE_CASE)

        const val HREF = "href"
        const val SRC = "src"

        const val TYPE = "@type"
        const val ITEM_LIST = "ItemList"
        const val ELEMENTS = "itemListElement"
        const val NAME = "name"
        const val URL = "url"
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
