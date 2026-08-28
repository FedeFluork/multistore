package com.multistore.store.apkcombo.parser

import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.apkcombo.ApkComboConfig
import com.multistore.store.apkcombo.ApkComboRefs
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.parseHtmlOrNotFound

/**
 * An app's listing on apkcombo.
 *
 * The page carries the same information twice: in the header, briefly, and in the **information
 * table** at the bottom, labelled. The parser prefers the table where it exists, because it is the
 * only one of the two that publishes the version code — and without it the pipeline's
 * anti-downgrade rule has nothing to compare against.
 *
 * The `packageName` is read in two independent places: the URL's second segment and the
 * "Google Play ID" row. **If they disagree, the listing is discarded.** Not zeal: if the URL said
 * one package and the page another, the app would show one listing and install the other, and the
 * hard block would only fire at the end — after the download.
 */
internal class ApkComboDetailParser(private val config: ApkComboConfig) {

    fun parse(html: String, url: String, ref: StoreAppRef): StoreResult<StoreListingDetail> =
        parseHtmlOrNotFound(html, url) { document ->
            val title = document.textOrNull(config.selectors.detailTitle) ?: return@parseHtmlOrNotFound null
            val rows = infoRows(document)
            val expected = ApkComboRefs.packageNameOf(ref)
            val declared = rows[config.selectors.infoRowPackageName]?.let(::packageNameFrom)
            if (declared != null && expected != null && declared != expected) return@parseHtmlOrNotFound null

            val versionCell = rows[config.selectors.infoRowVersion]
            val category = rows[config.selectors.infoRowCategory]
                ?: document.all(config.selectors.detailBreadcrumbCategory).lastOrNull()?.ownTextOrNull()

            StoreListingDetail(
                summary = StoreListingSummary(
                    storeId = StoreId.APKCOMBO,
                    ref = ref,
                    title = title,
                    packageName = declared ?: expected,
                    summary = LocalizedText.of(document.textOrNull(config.selectors.detailSummary)),
                    developer = document.textOrNull(config.selectors.detailDeveloper),
                    iconUrl = document.oneOrNull(config.selectors.detailIcon)?.ownAttrOrNull("data-src"),
                    categories = listOfNotNull(category),
                    contentKind = contentKindOf(document),
                    latestVersionName = versionName(versionCell)
                        ?: document.textOrNull(config.selectors.detailVersion),
                    latestVersionCode = TextValues.parenthesizedCode(versionCell),
                    downloadsLabel = rows[config.selectors.infoRowInstalls],
                    lastUpdated = TextValues.monthDayYear(rows[config.selectors.infoRowUpdate]),
                ),
                screenshots = document.all(config.selectors.detailScreenshot)
                    .mapNotNull { it.ownAbsUrlOrNull("data-href") }
                    .map { Screenshot(url = it) },
            )
        }

    /** The information table as a label -> value map. */
    private fun infoRows(document: HtmlPage): Map<String, String> =
        document.all(config.selectors.detailInfoRow).mapNotNull { row ->
            val name = row.textOrNull(config.selectors.detailInfoName) ?: return@mapNotNull null
            val value = row.textOrNull(config.selectors.detailInfoValue) ?: return@mapNotNull null
            name to value
        }.toMap()

    /**
     * App or game, from the breadcrumb.
     *
     * apkcombo splits its catalogue into app and game categories before the real category, and that
     * first crumb is the only place it says so.
     */
    private fun contentKindOf(document: HtmlPage): ContentKind {
        val hrefs = document.all(config.selectors.detailBreadcrumbCategory)
            .mapNotNull { it.ownAttrOrNull("href") }
        return when {
            hrefs.any { GAME_PATH in it } -> ContentKind.GAME
            hrefs.any { APP_PATH in it } -> ContentKind.APP
            else -> ContentKind.UNKNOWN
        }
    }

    /** The "Google Play ID" row is a link to Play: the useful value is the text, not the URL. */
    private fun packageNameFrom(cell: String): String? = cell.trim().takeIf { it.contains('.') }

    /** `12.10.0 (70242)` -> `12.10.0`. */
    private fun versionName(cell: String?): String? =
        cell?.substringBefore('(')?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val GAME_PATH = "/category/game"
        const val APP_PATH = "/category/app"
    }
}
