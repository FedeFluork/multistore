package com.multistore.store.pdalife.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.PagedResult
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.mapRowsOrFail
import com.multistore.store.common.html.parseXml
import com.multistore.store.pdalife.PdalifeConfig
import com.multistore.store.pdalife.PdalifeRefs
import kotlin.time.Instant

/**
 * pdalife's RSS feed, `/rss/`.
 *
 * ### The feed is already filtered, the search is not
 *
 * That is the difference making this surface preferable to any page on this store. pdalife
 * publishes iOS, PSP and Android in the same list, and the search forces the wrong rows to be
 * discarded one by one; in the feed, measured on 25/08/2026, **100 entries out of 100 are
 * Android** — the title suffix says so, `скачать … на Android`, and the fact that every link has
 * the form `…-android-aNNNNN.html`, the only one [PdalifeRefs] accepts, confirms it.
 *
 * The filter is written down all the same, and it is the ref: an iOS entry would not produce a
 * valid ref and would fall away. It is not redundancy — it is the only defence that keeps holding
 * if the feed's composition changes tomorrow.
 *
 * ### The five dates that cannot be true
 *
 * Five entries in a hundred are dated in the future, the furthest at **27 May 2029**: they are
 * announcements of unreleased games. A list ordered by date would keep them at the top forever,
 * i.e. the "recent" section would show as its first five things that do not exist.
 * `TextValues.rfc1123NotFuture` leaves them without a date rather than discarding the entry: the
 * app is there and its listing works, it is only the date that is unusable.
 *
 * ### The title carries the site's verb
 *
 * `The Walking Dead: A New Frontier скачать на Android`, `Winter Burrow скачать 1.0 Full на
 * Android`. Everything following `скачать` belongs to the page, not to the app: the version, the
 * channel (`Full`, `Unlocked`, `Pro`, `Premium`) and the platform. Keeping it would give a title
 * matching no other store — and to `IdentityMatcher`, which on pdalife has no `packageName` to
 * correct itself with, it would be an app different from itself.
 */
internal class PdalifeFeedParser(private val config: PdalifeConfig) {

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
        val ref = PdalifeRefs.refFromUrl(link) ?: return null
        val title = stripDownloadPhrase(item.textOrNull(config.selectors.feedTitle)) ?: return null
        return StoreListingSummary(
            storeId = StoreId.PDALIFE,
            ref = ref,
            title = title,
            summary = LocalizedText.of(item.textOrNull(config.selectors.feedDescription)),
            categories = listOfNotNull(item.textOrNull(config.selectors.feedCategory)),
            iconUrl = item.attrOrNull(config.selectors.feedEnclosure, "url"),
            lastUpdated = TextValues.rfc1123NotFuture(item.textOrNull(config.selectors.feedDate), now),
        )
    }

    /**
     * `Winter Burrow скачать 1.0 Full на Android` -> `Winter Burrow`.
     *
     * The cut is on the **Russian word**, not on "на Android", and the difference matters: between
     * the word and the platform sits everything that varies — eight forms measured, from
     * `скачать N на Android` (62 entries) to `скачать N UnlockedN на Android` — while before the
     * word there is only ever the name. Cutting at the end would mean enumerating the eight forms,
     * and the ninth would break silently.
     *
     * The verb lives in the configuration and not here because it is **site text**: pdalife also
     * answers in English, and changing its language will have to be a `parsers.json` update, not a
     * release. It is the same choice already made for apkcombo's info-table labels.
     */
    private fun stripDownloadPhrase(raw: String?): String? =
        raw?.substringBefore(config.selectors.feedTitleVerb)?.trim()?.takeIf { it.isNotBlank() }
}
