package com.multistore.store.apkmirror.parser

import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmirror.ApkMirrorConfig
import com.multistore.store.apkmirror.ApkMirrorRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseXml
import kotlin.time.Instant

/**
 * The RSS feed of the latest releases.
 *
 * ### Ten entries, deliberately few
 *
 * Measured on 25/08/2026: ten entries, all dated within the last four hours. apkmirror publishes
 * dozens of releases a day, so the feed is a very narrow window — but it is also the **only**
 * surface of this store readable in a single request. The equivalent page carries the same
 * information inside 424 KB of markup with advertising between the rows, on a site declaring
 * `Crawl-delay: 3`.
 *
 * ### The title contains three things, and they have to be separated
 *
 * `Goodnotes: AI Notes, Docs, PDF 2.0.9 (v320533) by GoodNotes` — name, version, version code and
 * developer on one line. Keeping it whole would give an app whose name includes its version, i.e.
 * a title matching no other store and not even apkmirror's own listing: to the identity matcher it
 * would be a new app on every release.
 *
 * The **developer** is why separating beats truncating: it is the only one of the four measured
 * "new release" sources that publishes it, and the inferred app key derives from title **and**
 * developer for stores that give no package name — which apkmirror does not, in the feed.
 *
 * ### The link is to a release, the ref is the app's
 *
 * A three-segment path: the first two are the listing, the third the release. Home opens the
 * listing — whoever taps "new" wants the app, not that precise file — and the version stays
 * written on the summary.
 */
internal class ApkMirrorFeedParser(private val config: ApkMirrorConfig) {

    fun parse(
        xml: String,
        baseUrl: String,
        page: Int,
        now: Instant,
    ): StoreResult<PagedResult<StoreListingSummary>> =
        parseXml(xml, baseUrl) { document ->
            val items = document.all(config.selectors.feedItem)
                .mapRowsOrFail(config.selectors.feedItem) { summaryOf(it, now) }
            PagedResult(items = items, page = page, hasMore = false, totalCount = items.size)
        }

    private fun summaryOf(item: HtmlPage, now: Instant): StoreListingSummary? {
        val link = item.textOrNull(config.selectors.feedLink) ?: return null
        val ref = ApkMirrorRefs.appRefFromReleaseUrl(link) ?: return null
        val parts = split(item.textOrNull(config.selectors.feedTitle)) ?: return null
        return StoreListingSummary(
            storeId = StoreId.APKMIRROR,
            ref = ref,
            title = parts.title,
            developer = parts.developer,
            latestVersionName = parts.version,
            iconUrl = iconOf(item),
            lastUpdated = TextValues.rfc1123NotFuture(item.textOrNull(config.selectors.feedDate), now),
        )
    }

    /**
     * The icon, which lives inside the entry's body and not among its fields.
     *
     * The feed has neither an enclosure nor a media element: the only image is the first `<img>`
     * in the encoded-content block, which is HTML inside a CDATA. It therefore has to be
     * **re-parsed as HTML** — the surrounding document is XML, where that block is text rather
     * than elements, and asking the outer document for an image finds nothing silently.
     *
     * Why it is worth it: three of the five surfaces feeding Home publish no icon at all, and this
     * was the third — measured on 27/08/2026, ten entries out of ten carry one at 384×384. Without
     * it, a third of the "new" rows showed the placeholder.
     */
    private fun iconOf(item: HtmlPage): String? {
        val content = item.textOrNull(config.selectors.feedContent) ?: return null
        return HtmlPage.of(content, config.baseUrl)
            .absUrlOrNull(config.selectors.feedContentIcon, SRC)
    }

    /**
     * `{Name} {version}[ beta][ (vNNN)] by {Developer}` split into its three parts.
     *
     * The developer cut uses the **last** occurrence of ` by `: among the apps there is one called
     * "Words by Post", and it would be the first to break with the first occurrence. The rest is
     * stripped from the right, in the order apkmirror writes it — version code in brackets, then
     * channel label, then version number — because each of the three is optional and removing them
     * in a different order would leave the preceding one behind.
     *
     * If nothing remains after the cuts the entry is discarded: `null` and not the whole title,
     * because a title that has lost its name is worse than one row fewer.
     */
    private fun split(raw: String?): Parts? {
        if (raw.isNullOrBlank()) return null
        val separator = raw.lastIndexOf(BY)
        val head = (if (separator > 0) raw.substring(0, separator) else raw).trim()
        val developer = if (separator > 0) raw.substring(separator + BY.length).trim() else null

        val withoutCode = head.replace(VERSION_CODE, "").trim()
        val withoutChannel = withoutCode.replace(CHANNEL, "").trim()
        val version = VERSION_TAIL.find(withoutChannel)?.value?.trim()
        val title = (version?.let { withoutChannel.removeSuffix(it).trim() } ?: withoutChannel)
            .takeIf { it.isNotBlank() } ?: return null

        return Parts(
            title = title,
            version = version,
            developer = developer?.takeIf { it.isNotBlank() },
        )
    }

    private data class Parts(val title: String, val version: String?, val developer: String?)

    private companion object {
        const val SRC = "src"
        const val BY = " by "

        /** `(v320533)`: the version code, which apkmirror writes only on some releases. */
        val VERSION_CODE = Regex("""\s*\(v\d+\)\s*$""", RegexOption.IGNORE_CASE)

        /** `beta`, `alpha`: the channel, written after the number. */
        val CHANNEL = Regex("""\s+(beta|alpha)\s*$""", RegexOption.IGNORE_CASE)

        /**
         * The last group containing a digit: the version number.
         *
         * It has to cope with versions containing letters and signs. What anchors it is that it
         * **starts with a digit**: without that, on a title whose name ends in a word it would
         * swallow that word too.
         */
        val VERSION_TAIL = Regex("""\s\d[^\s]*$""")
    }
}
