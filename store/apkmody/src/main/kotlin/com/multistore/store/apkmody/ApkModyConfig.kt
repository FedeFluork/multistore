package com.multistore.store.apkmody

import com.multistore.store.api.StoreMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable

/**
 * The apkmody adapter's compiled defaults.
 *
 * CSS selectors and regex patterns are never hardcoded in Kotlin: they live here, so a markup
 * change is fixable without a release.
 *
 * Every value was **measured** on 24/08/2026 against `apkmody.mobi` from an Italian consumer IP.
 *
 * ### The right domain is `.mobi`, and the other two are worse than dead
 *
 * The `.com` domain **is not a fallback**: its deep paths answer 301 towards an unrelated site —
 * the same fate as the `.fun` one, which redirects to an IPTV site. Following that redirect would
 * mean presenting a third party's page to the user as though it were the store they chose. Only
 * `.mobi` answers with the catalogue, and it answers **without** a User-Agent too.
 *
 * ### The file sits on a CDN, and the CDN's path is the only control we have
 *
 * The binaries live under a path containing the package name and a file name containing the
 * version name and version code. Both halves of that path were **verified against the real APK**,
 * not inferred from the name: one file was downloaded and read with `aapt2 dump badging`, and the
 * package, version code and version name all matched. Hence [downloadHost] as a configuration
 * datum: in its download list apkmody also places a button pointing at its **own** installer, and
 * that is an `.apk` like the others — only the host tells them apart.
 */
@Serializable
data class ApkModyConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * The CDN the files actually come from.
     *
     * A field and not a constant because it is a **safety filter**, not a detail: every link in the
     * download list that does not point here is discarded, and the advertising apkmody places among
     * the real downloads is exactly an `.apk` on another host.
     */
    val downloadHost: String = DEFAULT_DOWNLOAD_HOST,
    /**
     * A browser User-Agent.
     *
     * apkmody does not ask for one — the root answers 200 without — but the field is mandatory in
     * the contract and this module's fixtures were captured with **this** value.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    val selectors: ApkModySelectors = ApkModySelectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    /** `/?s=spotify`. The path is the root: the query is everything. */
    fun searchUrl(query: String): String = "$root/?s=" + query.encodeQueryValue()

    /**
     * The chart, `/popular`.
     *
     * A site menu entry, not an inferred page: the link sits in the navigation bar of every page.
     */
    fun popularUrl(): String = "$root/$POPULAR_SEGMENT"

    /** [path] is already `apps/spotify-pro` or `games/minecraft`: see [ApkModyRefs]. */
    fun listingUrl(path: String): String = "$root/${path.trim('/')}"

    fun downloadUrl(path: String): String = "$root/${path.trim('/')}/$DOWNLOAD_SEGMENT"

    fun historyUrl(path: String): String = "$root/${path.trim('/')}/$HISTORY_SEGMENT"

    /** The page serving a specific version: [segment] is `download` or `history/{id}`. */
    fun versionUrl(path: String, segment: String): String = "$root/${path.trim('/')}/${segment.trim('/')}"

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            // apkmody publishes the same catalogue in six languages under a path prefix. English
            // is the one without a prefix, and it is the canonical the alternate links declare.
            listingLanguage = "en",
            host = HOST,
        )

    companion object {
        /** The chart's path, declared in the site's menu. */
        const val POPULAR_SEGMENT: String = "popular"

        const val DISPLAY_NAME: String = "APKMODY"
        const val HOST: String = "apkmody.mobi"
        const val DEFAULT_BASE_URL: String = "https://apkmody.mobi"
        const val DEFAULT_DOWNLOAD_HOST: String = "cdn.topmongo.com"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * No `Crawl-delay` in their `robots.txt` and no 429 observed across ~40 requests. The
         * value stays below what a browser does opening a page with twenty images.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.5
        const val DEFAULT_BURST: Int = 3

        val DEFAULT_LISTING_TTL: Duration = 6.hours

        const val DOWNLOAD_SEGMENT: String = "download"
        const val HISTORY_SEGMENT: String = "history"
    }
}

/**
 * The selectors, kept apart from the code that uses them.
 *
 * Observed on 24/08/2026, each exercised by a real fixture in
 * `src/test/resources/fixtures/apkmody/`.
 */
@Serializable
data class ApkModySelectors(
    // --- search ---
    //
    // On a search with no results apkmody leaves the container **empty** and keeps the footer
    // intact, where "Trending" and "Latest" link to paths of the identical shape as results.
    //
    // What keeps them out today is the card selector, not the container: **verified by removing the
    // container, and the suite stays green**. The container is here anyway, because the alternative
    // would be trusting that the footer keeps being made of list items — i.e. making "no results"
    // depend on the markup of a section that does not concern us. On uptodown the same bet lost:
    // there the suggested cards are identical to results, and only the container tells them apart.
    val searchItem: String = ".flex-container article.card > a[href]",
    val searchName: String = ".card-title .truncate",

    // --- chart (`/popular`) ---
    //
    // Not a card selector: the chart is read from the structured-data list block, which declares
    // each entry's position. See `ApkModyPopularParser`.
    val popularJsonLd: String = "script[type=application/ld+json]",
    /**
     * The chart's cards, which serve **one purpose**: the icon.
     *
     * The order still comes from the structured data, for the reason above. But that block carries
     * only position, name and URL, while the card next to it has the image — and here, unlike the
     * search cards, it is a **real icon**: measured on 27/08/2026 against the committed page,
     * twelve entries out of twelve, at 52×52. The search cards stay what they are — covers,
     * eighteen placeholders out of twenty — and the difference between the two surfaces is why this
     * selector is separate from the search one rather than being the same.
     *
     * **The package is not read from that URL**, despite being written inside it: the path
     * convention is measured on APK files, not on icons, and the committed fixtures contain no page
     * allowing the two to be checked against each other. A wrong package is not an empty field: it
     * feeds cross-store identity and the pipeline's hard block.
     */
    val popularCard: String = "a.popular-apk-card[href]",
    val popularCardIcon: String = "img[src]",
    val searchExcerpt: String = "p.card-excerpt",

    // --- detail ---
    val detailTitle: String = ".app-name h1 strong",
    val detailHeadline: String = ".app-name h1 span",
    val detailUpdatedTime: String = ".app-name time[datetime]",
    val detailIcon: String = ".app .app-icon img",
    val detailBreadcrumbLink: String = "#breadcrumb a[href]",
    val detailInfoRow: String = "figure.wp-block-table table tr",
    val detailInfoName: String = "th",
    val detailInfoValue: String = "td",
    val detailDescriptionParagraph: String = "#apkmody-detail-prose > p",
    /**
     * The filler sentences the theme attaches to the description, to be removed.
     *
     * Every listing's first paragraph is written half about the app and half about the site: after
     * the sentence saying what the modification does comes a fixed one, identical on every page,
     * about what the page does **not** contain. It is not a description of the app, and in an
     * aggregator placing that text next to eight other stores' it is repeated noise.
     *
     * It lives in configuration and not in code for the same reason as the selectors: it is a
     * sentence of their theme, and changing it must not need a release. Each entry is a regular
     * expression; one that matches nothing is not an error — the theme may already have dropped it.
     *
     * The cut runs to the first full stop because the sentence is glued to a real one: removing the
     * whole element would take away what describes the app too.
     */
    val detailDescriptionNoise: List<String> = DEFAULT_DESCRIPTION_NOISE,
    val detailScreenshot: String = ".apkmody-preview-item img[src]",

    // --- download (the download page) ---
    val downloadItem: String = ".download-list a[href]",
    val downloadItemName: String = ".download-item-name > div",
    val downloadItemTag: String = ".download-item-name .app-tag",

    // --- history (the history page and its per-version pages) ---
    val historyLatestLink: String = "a#download-button[href]",
    val historyItem: String = ".historyListRows .historyItem a[href]",
    val historyItemVersion: String = ".top .font18",
    val historyItemDate: String = ".top .grayColor",
    val historyItemSize: String = ".bottom .grayColor",

    // --- information-table labels ---
    //
    // These are **site text**, not CSS selectors, and they are here for the same reason: apkmody
    // translates the table into its six languages, and serving a localised catalogue one day must
    // be a configuration update, not a release.
    val infoRowName: String = "Name",
    val infoRowPackageName: String = "Package Name",
    val infoRowVersion: String = "Version",
    val infoRowSize: String = "Size",
    val infoRowModFeatures: String = "MOD Features",
    val infoRowPublisher: String = "Publisher",
)

/**
 * The sentence apkmody's theme appends to the first paragraph of every listing.
 *
 * Observed on 27/08/2026 on the committed fixture: it sits at the end of the sentence that really
 * describes the modification, in the same paragraph, so it is removed by text and not by element.
 * The cut runs to the first full stop and takes the preceding space with it.
 */
private val DEFAULT_DESCRIPTION_NOISE: List<String> = listOf(
    """\s*Instead of repeating a generic APK install guide[^.]*\.""",
)

private fun String.encodeQueryValue(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8)
