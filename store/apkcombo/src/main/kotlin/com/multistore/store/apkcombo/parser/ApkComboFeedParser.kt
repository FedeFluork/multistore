package com.multistore.store.apkcombo.parser

import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.ApkComboRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseXml
import kotlin.time.Instant

/**
 * The RSS feed of just-updated apps.
 *
 * ### Why the feed, and not a page
 *
 * Because the pages have nothing to read. Measured on 25/08/2026 with the real client: the home,
 * top-apps, trending and new-apps pages all answer 200 and contain **zero links to a listing**. The
 * headings are there and JavaScript writes the content below; the four pages differ only in their
 * canonical link and in a randomly chosen tag cloud. That is 96 KB naming not one app.
 *
 * The two feeds the site declares have everything, and **one carries the `packageName`**: each
 * entry's URL is `/{slug}/{packageName}/`, i.e. exactly this store's ref. Of the three measured
 * "new" sources it is the only one that does — apkmirror gives a release path and pdalife a
 * numbered slug.
 *
 * ### Which of the two feeds, and why not both
 *
 * One (98 entries) lists apps that published a new version; the other (100) lists apps appearing
 * for the first time. The first is used. The Home section is called "new", and next to F-Droid's
 * recently-updated results mixing the two species would make the list incoherent: a mature app
 * beside something that has never had a user. The second feed stays one URL away for the day
 * "just arrived" makes sense as a section of its own.
 */
internal class ApkComboFeedParser(private val config: ApkComboConfig) {

    fun parse(
        xml: String,
        baseUrl: String,
        page: Int,
        now: Instant,
    ): StoreResult<PagedResult<StoreListingSummary>> =
        parseXml(xml, baseUrl) { document ->
            val items = document.all(config.selectors.feedItem)
                .mapRowsOrFail(config.selectors.feedItem) { summaryOf(it, now) }
            // A feed does not paginate: it is a window onto the last N entries, and asking for a
            // second would return the same ones. Declaring no further pages costs zero requests and
            // tells the truth, as this store's search already does.
            PagedResult(items = items, page = page, hasMore = false)
        }

    private fun summaryOf(item: HtmlPage, now: Instant): StoreListingSummary? {
        val link = item.textOrNull(config.selectors.feedLink) ?: return null
        val ref = ApkComboRefs.refFromUrl(link) ?: return null
        val title = stripPrefix(item.textOrNull(config.selectors.feedTitle)) ?: return null
        return StoreListingSummary(
            storeId = StoreId.APKCOMBO,
            ref = ref,
            title = title,
            packageName = ApkComboRefs.packageNameOf(ref),
            lastUpdated = TextValues.rfc1123NotFuture(item.textOrNull(config.selectors.feedDate), now),
        )
    }

    /**
     * `[apk_updated] Recovery Reboot` -> `Recovery Reboot`.
     *
     * The prefix belongs to the feed, not to the app: it is how apkcombo distinguishes the two
     * species of entry inside one format. Keeping it would give a list where every row starts with
     * the same bracket and — worse — a title different from the listing's: to the identity matcher
     * they would be two apps.
     */
    private fun stripPrefix(raw: String?): String? =
        raw?.replace(FEED_PREFIX, "")?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        val FEED_PREFIX = Regex("""^\[[a-z_]+]\s*""", RegexOption.IGNORE_CASE)
    }
}
