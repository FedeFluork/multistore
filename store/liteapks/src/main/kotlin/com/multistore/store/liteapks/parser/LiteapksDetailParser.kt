package com.multistore.store.liteapks.parser

import com.multistore.core.model.ContentKind
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.Screenshot
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.TextValues
import com.multistore.store.common.html.Urls
import com.multistore.store.common.html.parseFailed
import com.multistore.store.common.html.parseHtml
import com.multistore.store.liteapks.LiteapksConfig
import com.multistore.store.liteapks.LiteapksRefs

/**
 * The listing: `/{slug}.html`.
 *
 * It also returns the **file page's stem**, which is not derivable from the slug and without which
 * no download can be resolved: `/h-i-d-e.html` downloads from `/download/hide-h-i-d-e-72683`. See
 * [LiteapksRefs].
 *
 * ### The Google Play link is a trap that works almost always
 *
 * This store's most important finding, and it is not new — it is pdalife's family, but more
 * insidious. On **31 listings out of 31** there is an advert pointing at
 * `play.google.com/store/apps/details?id=io.apkmody.sai` ("XAPKS Installer", another store's
 * installer). The real link is on 26, and when present it is **the first of the two**.
 *
 * So the naive read — "the page's first `play.google.com`" — returns the right package 26 times out
 * of 31 and `io.apkmody.sai` the other five. Not `null`, not an error: **the wrong package**, on a
 * listing that looks entirely healthy. On pdalife the same mistake was luckier, because there the
 * advert came first *always* and the defect would have shown immediately.
 *
 * The `.app-stats` container excludes it by construction: the advert lives in the article's tail
 * and never appears inside that box (0 out of 31).
 */
internal class LiteapksDetailParser(private val config: LiteapksConfig) {

    /** The listing, plus what is needed to reach the files. */
    data class Parsed(
        val detail: StoreListingDetail,
        /** `minecraft-11909`, read from the "Download APK" button. `null` if the listing has none. */
        val downloadStem: String?,
    )

    fun parse(html: String, baseUrl: String, ref: StoreAppRef): StoreResult<Parsed> =
        parseHtml(html, baseUrl) { document ->
            val blocks = document.all(config.selectors.detailJsonLd).mapNotNull { it.dataOrNull() }
            // The schema.org block is mandatory: 31 listings out of 31 have it, and without it this
            // is not the page we think we are reading. A `ParseFailure` naming the selector is what
            // makes it repairable by publishing `parsers.json`.
            val schema = LiteapksSchemaApp.firstIn(blocks) ?: document.parseFailed(config.selectors.detailJsonLd)
            val title = schema.name ?: document.parseFailed(SCHEMA_NAME)

            val crumbs = document.all(config.selectors.detailBreadcrumb)

            val summary = StoreListingSummary(
                storeId = StoreId.LITEAPKS,
                ref = ref,
                title = title,
                packageName = packageNameOf(document),
                summary = LocalizedText.of(document.textOrNull(config.selectors.detailModTraits)),
                developer = document.textOrNull(config.selectors.detailDeveloper),
                iconUrl = document.absUrlOrNull(config.selectors.detailIcon, SRC),
                categories = listOfNotNull(categoryOf(crumbs, schema)),
                contentKind = contentKindOf(crumbs),
                latestVersionName = schema.version,
                latestVersionCode = null,
                rating = TextValues.rating(schema.rating?.toString(), outOf = schema.bestRating ?: DEFAULT_BEST)
                    ?.takeIf { (schema.ratingCount ?: 0) > 0 },
                ratingCount = schema.ratingCount?.takeIf { it > 0 },
                lastUpdated = TextValues.isoInstant(
                    document.attrOrNull(config.selectors.detailPublished, CONTENT),
                ),
            )

            Parsed(
                detail = StoreListingDetail(
                    summary = summary,
                    description = LocalizedText.of(document.textOrNull(config.selectors.detailDescription)),
                    screenshots = document.all(config.selectors.detailScreenshot)
                        .mapNotNull { it.ownAbsUrlOrNull(SRC) }
                        .map(::Screenshot),
                    // The versions are not here: the listing shows only one, the list lives on the
                    // file page. The adapter joins them, knowing whether the request is worth it.
                    versions = emptyList(),
                ),
                downloadStem = document.absUrlOrNull(config.selectors.detailDownloadLink, HREF)
                    ?.let(LiteapksRefs::downloadStemFromUrl),
            )
        }

    /**
     * The `packageName`, and **only** the one inside the stats box.
     *
     * The query's `id` parameter is read rather than cutting the string after `id=`: five listings
     * out of thirty-one write `?id=com.hnib.smslater&gl=US`, and a naive cut would give
     * `com.hnib.smslater&gl=US`. That value would never match the APK's package, and step 4 of the
     * pre-install pipeline would **block** every installation of those apps — a silent fault that
     * shows up at the last metre.
     */
    private fun packageNameOf(document: HtmlPage): String? =
        document.absUrlOrNull(config.selectors.detailPlayLink, HREF)
            ?.let { Urls.queryParam(it, PLAY_ID_PARAM) }
            ?.takeIf { PACKAGE_NAME.matches(it) }

    /**
     * The category, from the **third** breadcrumb, with the microdata as a fallback.
     *
     * The breadcrumbs say `Communication` and `Arcade`, i.e. the store's taxonomy; the JSON-LD says
     * `UtilitiesApplication` and `GameApplication`, i.e. schema.org's, which has about four values.
     * The first is preferred because it is what the user sees written on the page — and on one
     * listing out of thirty-one (`adventure-block`) it is absent, which is why the fallback exists.
     */
    private fun categoryOf(crumbs: List<HtmlPage>, schema: LiteapksSchemaApp): String? =
        crumbs.getOrNull(CATEGORY_CRUMB)?.textOrNull() ?: schema.category

    /** App or game, from the **second** breadcrumb: `/apps` or `/games`. */
    private fun contentKindOf(crumbs: List<HtmlPage>): ContentKind =
        LiteapksRefs.contentKindOf(crumbs.getOrNull(KIND_CRUMB)?.ownAbsUrlOrNull(HREF))

    private companion object {
        const val HREF = "href"
        const val SRC = "src"
        const val CONTENT = "content"
        const val PLAY_ID_PARAM = "id"
        const val SCHEMA_NAME = "ld+json SoftwareApplication.name"
        const val DEFAULT_BEST = 5f

        /** `Home / Apps / Communication / Telegram`: the second and the third. */
        const val KIND_CRUMB = 1
        const val CATEGORY_CRUMB = 2

        /** An Android package name, so that `com.something&gl=US` does not get through. */
        val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    }
}
