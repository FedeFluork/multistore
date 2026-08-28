package com.multistore.store.apkmody.parser

import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.StoreResult
import com.multistore.store.apkmody.ApkModyConfig
import com.multistore.store.apkmody.ApkModyRefs
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.parseHtmlOrNotFound

/**
 * An app's listing on apkmody.
 *
 * ### The `packageName` is there, and it is the only thing making this store verifiable
 *
 * apkmody redistributes **modified** APKs: there is no original developer signature to compare
 * against, and no hash is published. Of the seven pre-install steps only one can still say yes or
 * no, and that is the **`packageName` match** — the one control with no switch. The "Package Name"
 * row of the information table is what allows it; without it, apkmody would be a store you
 * download from blind.
 *
 * The same information appears a second time in the CDN's path, and the two check each other at
 * download time: see `ApkModyStoreAdapter.getDownloadLink`.
 *
 * ### The rating block is not a rating
 *
 * It shows **four stars out of five on every app measured** — five apps, five times. It is
 * decoration, not a datum, which is why the capability is `false` and nothing is read here.
 * Reporting it as `4.0` next to another store's real `3.9` would give a graphical constant the
 * appearance of a measurement.
 */
internal class ApkModyDetailParser(private val config: ApkModyConfig) {

    fun parse(html: String, url: String, ref: StoreAppRef): StoreResult<StoreListingDetail> =
        parseHtmlOrNotFound(html, url) { document ->
            // apkmody's 404 page is complete — menu, footer, 226 KB — and its heading reads `404`.
            // The only way to tell it apart is that it has no app header: if the title is not
            // there, the listing is not there.
            val title = document.textOrNull(config.selectors.detailTitle) ?: return@parseHtmlOrNotFound null
            val rows = infoRows(document)

            StoreListingDetail(
                summary = StoreListingSummary(
                    storeId = StoreId.APKMODY,
                    ref = ref,
                    title = title,
                    packageName = rows[config.selectors.infoRowPackageName]?.takeIf { it.contains('.') },
                    summary = LocalizedText.of(rows[config.selectors.infoRowModFeatures]),
                    developer = rows[config.selectors.infoRowPublisher],
                    iconUrl = document.absUrlOrNull(config.selectors.detailIcon, "src"),
                    categories = listOfNotNull(categoryOf(document, ref)),
                    contentKind = ApkModyRefs.contentKindOf(ref),
                    latestVersionName = rows[config.selectors.infoRowVersion],
                    // The listing publishes the version code in no form: it arrives from the file
                    // name on the CDN, i.e. from the history. Inventing it from the version name
                    // would give a number bearing no relation to the real one.
                    latestVersionCode = null,
                    lastUpdated = TextValues.isoInstant(
                        document.attrOrNull(config.selectors.detailUpdatedTime, "datetime"),
                    ),
                ),
                description = LocalizedText.of(
                    cleaned(document.textOrNull(config.selectors.detailDescriptionParagraph)),
                ),
                screenshots = document.all(config.selectors.detailScreenshot)
                    .mapNotNull { it.ownAbsUrlOrNull("src") }
                    .distinct()
                    .map { Screenshot(url = it) },
            )
        }

    /**
     * The description without the sentences about the site rather than about the app.
     *
     * If nothing remains after cleaning, `null` is returned and not the empty string: a description
     * made **only** of filler is a description that is not there, and the localised-text type tells
     * the two apart — with an empty string the listing would draw a "Description" heading over
     * nothing.
     */
    private fun cleaned(raw: String?): String? {
        if (raw == null) return null
        val text = noise.fold(raw) { text, pattern -> text.replace(pattern, "") }
        return text.trim().takeIf { it.isNotBlank() }
    }

    /**
     * The expressions are compiled once per adapter, not once per listing.
     *
     * A pattern the remote configuration writes badly must not bring parsing down: that one is
     * discarded and the others applied. It is the same rule as the remote override — a wrongly
     * typed value costs that store, not the document — one level down.
     */
    private val noise: List<Regex> = config.selectors.detailDescriptionNoise.mapNotNull {
        runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
    }

    /** The "APP INFO" table as a label -> value map. */
    private fun infoRows(document: HtmlPage): Map<String, String> =
        document.all(config.selectors.detailInfoRow).mapNotNull { row ->
            val name = row.textOrNull(config.selectors.detailInfoName) ?: return@mapNotNull null
            val value = row.textOrNull(config.selectors.detailInfoValue) ?: return@mapNotNull null
            name to value
        }.toMap()

    /**
     * The category from the breadcrumb: `Home / Apps / music / Spotify Pro`.
     *
     * The filter is deliberately not positional: the category link and the app link have **the same
     * shape** — two segments, first one `apps` — and no class tells them apart. What does is that
     * one of the two is the app being read. Taking "the breadcrumb's last link" would work here,
     * where the app is a span, and would fail on the download page, where the app is a link and
     * would be taken as its own category.
     */
    private fun categoryOf(document: HtmlPage, ref: StoreAppRef): String? =
        document.all(config.selectors.detailBreadcrumbLink)
            .firstOrNull { link ->
                val candidate = link.ownAttrOrNull("href")?.let(ApkModyRefs::refFromUrl)
                candidate != null && candidate != ref
            }
            ?.textOrNull()
}
