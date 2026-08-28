package com.multistore.store.apkmody.parser

import com.multistore.store.api.StoreResult
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseHtml
import kotlin.time.Instant

/** A history row: what apkmody declares without opening its page. */
internal data class ApkModyVersionEntry(
    /** The path fragment that serves it: `history/xyTAa4R6VE`. */
    val segment: String,
    val versionName: String,
    val sizeBytes: Long?,
    val publishedAt: Instant?,
)

/** The version history plus the current file, which sit on the same page. */
internal data class ApkModyHistory(
    val latest: ApkModyFile?,
    val entries: List<ApkModyVersionEntry>,
)

/**
 * The history page: the list of versions **and** the link to the current file.
 *
 * Having both on one page is what keeps the listing at two requests instead of three. Both are
 * needed because the list alone is not enough for version selection: the rows publish name, date
 * and size, **not the version code**, and that lives only in the file name. The button at the top
 * carries the current file, whose name declares version name and version code together: attaching
 * one to the other gets the current version — the only one needed to decide whether an update
 * exists — complete.
 *
 * **The current row is already in the list.** Verified on two apps. That is why the versions are
 * taken **only** from here: adding the current one as a separate entry would produce two rows for
 * the same file, with two different refs and no way for the database to notice.
 */
internal class ApkModyHistoryParser(
    private val config: ApkModyConfig,
    private val downloadParser: ApkModyDownloadParser,
) {

    fun parse(html: String, url: String): StoreResult<ApkModyHistory> =
        parseHtml(html, url) { document ->
            ApkModyHistory(
                latest = downloadParser.fileIn(document),
                entries = document.all(config.selectors.historyItem).mapNotNull(::entryOf),
            )
        }

    private fun entryOf(item: HtmlPage): ApkModyVersionEntry? {
        val href = item.ownAttrOrNull("href") ?: return null
        val segments = Urls.segments(href)
        // The link is relative, so the last two segments are taken rather than a known prefix
        // stripped: the adapter knows the prefix, the parser does not.
        if (segments.size < VERSION_PATH_SEGMENTS) return null
        val kind = segments[segments.size - 2]
        if (kind != ApkModyConfig.HISTORY_SEGMENT) return null

        val label = item.textOrNull(config.selectors.historyItemVersion) ?: return null
        return ApkModyVersionEntry(
            segment = "$kind/${segments.last()}",
            versionName = label.removePrefix(VERSION_PREFIX).trim().ifBlank { return null },
            sizeBytes = TextValues.byteSize(item.textOrNull(config.selectors.historyItemSize)),
            publishedAt = TextValues.weekdayMonthDayYear(item.textOrNull(config.selectors.historyItemDate)),
        )
    }

    private companion object {
        /** `Ver 9.1.36.1948` -> `9.1.36.1948`. */
        const val VERSION_PREFIX = "Ver"
        const val VERSION_PATH_SEGMENTS = 4
    }
}
