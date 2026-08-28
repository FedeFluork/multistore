package com.multistore.store.apkmirror

import com.multistore.store.api.StoreMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable

/**
 * The apkmirror adapter's compiled defaults, measured on **24/08/2026** from an Italian consumer
 * IP.
 *
 * ### The protocol finding, and how it was got wrong
 *
 * With **curl**, release pages answer `403 cf-mitigated: challenge` over HTTP/2 and `200` over
 * HTTP/1.1, four URLs out of four. From that came a "force HTTP/1.1" configuration flag, a network
 * tier value and a field in the network profile.
 *
 * **It was wrong, and the nightly canary found out.** With OkHttp — the client the app actually
 * ships — the result is the **opposite**: HTTP/1.1 gets the challenge and HTTP/2 passes. Retried
 * with and without the full set of navigation headers and with the parent page's Referer: no
 * difference; the protocol together with the stack's TLS fingerprint decides.
 *
 * Cloudflare is not looking at the HTTP version in the abstract but at the **coherence** between
 * TLS handshake and application layer.
 *
 * The lesson, which reaches beyond this store: **a measurement taken with one client is not a
 * measurement of your client.** The static pin was removed; if it were ever needed, rung 1 of the
 * escalation ladder already retries over HTTP/1.1 and does so knowing it tried.
 */
@Serializable
data class ApkMirrorConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * A browser User-Agent, and here it **is not optional**.
     *
     * Re-verified on 24/08/2026: `okhttp/4.12.0` receives **403 with 153 bytes**, a Chrome mobile
     * UA receives 200 with 414 KB. It is the single configuration line without which this adapter
     * does not even make its first request, and the reason `StoreCapabilities.userAgent` is a
     * mandatory contract field rather than an HTTP-client detail.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    /**
     * One permit every three seconds, with four in reserve.
     *
     * `Crawl-delay: 3` is what apkmirror declares in its `robots.txt`. It is no longer treated as
     * an obligation — the requests MultiStore makes are ones a user just asked for — but it
     * remains **the tolerance the site declares**, and the only measured number we have. Ignoring
     * it is the fastest way to a permanent 403.
     *
     * The burst of four does not contradict it: it is the traffic shape of a browser, which opens
     * the listing → release → variant → interstitial chain at once and then sits still. The
     * sustained average stays as declared.
     */
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    /**
     * How long to keep an apkmirror page in the HTTP cache.
     *
     * Measured on 24/08/2026: apkmirror's pages send **no cache headers at all** — no
     * `Cache-Control`, no `Expires`, no `ETag`, no `Last-Modified`. To OkHttp such a response is
     * not storable, so the app's 50 MB cache held nothing for this store: going back and forth
     * between search and listing was two requests to a site that declares `Crawl-delay: 3` and
     * answers 429 to whoever ignores it.
     *
     * Five minutes cover that back-and-forth and are not enough to show a stale version. It is a
     * number that fills a silence, not one that contradicts an answer: on stores declaring
     * `no-store` it stays zero. See `CacheHeaderInterceptor`.
     */
    val pageCacheTtl: Duration = DEFAULT_PAGE_CACHE_TTL,
    val selectors: ApkMirrorSelectors = ApkMirrorSelectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    /**
     * Search lives on the root with four parameters, not on a `/search` path.
     *
     * The type parameter searches among **apps**; without it apkmirror also returns individual
     * releases, which in the same list have a four-segment URL instead of three and are not what a
     * search result should be.
     */
    /**
     * The feed of the latest releases.
     *
     * Ten entries, 24 KB. The equivalent page carries the same ones inside 424 KB of markup, on a
     * site that declares `Crawl-delay: 3` and answers 429 to whoever ignores it.
     */
    fun recentFeedUrl(): String = "$root/$RECENT_FEED_SEGMENT/"

    fun searchUrl(query: String, page: Int): String {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8)
        val base = "$root/?post_type=app_release&searchtype=app&s=$encoded"
        return if (page <= 0) base else "$base&page=${page + 1}"
    }

    /**
     * The URL of a content path, from the listing to the variant.
     *
     * The paths adapters pass around are **relative to `/apk/`**, because that is how
     * [ApkMirrorRefs] trims them. The prefix is restored here, in one place: restoring it by hand
     * at every call is exactly how you forget it on one of the three levels and get a 404 only for
     * variants.
     */
    fun pageUrl(path: String): String = "$root/$APK_PREFIX/${path.trim('/')}/"

    /** An app's listing: [pageUrl] with a name saying which level we are at. */
    fun appUrl(path: String): String = pageUrl(path)

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            listingLanguage = "en-US",
            host = HOST,
        )

    companion object {
        /** The feed WordPress publishes for the release archive. */
        const val RECENT_FEED_SEGMENT: String = "feed"

        const val DISPLAY_NAME: String = "APKMirror"
        const val HOST: String = "www.apkmirror.com"
        const val DEFAULT_BASE_URL: String = "https://www.apkmirror.com"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * One permit every three seconds: the `Crawl-delay` the store declares.
         *
         * Not theoretical caution: while this adapter was being written apkmirror answered **429**
         * after too dense a run of probes. The number it declares is also the number it enforces.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.0 / 3.0
        const val DEFAULT_BURST: Int = 4

        val DEFAULT_LISTING_TTL: Duration = 6.hours

        val DEFAULT_PAGE_CACHE_TTL: Duration = 5.minutes

        /** The prefix of every content path. */
        const val APK_PREFIX: String = "apk"
    }
}

/**
 * The subtrees to remove before reading the description: the adverts and the "More" control.
 */
private val DEFAULT_DESCRIPTION_NOISE: List<String> = listOf("div.ains", "div.show-more")

/**
 * apkmirror's selectors, kept apart from the code that uses them.
 *
 * Observed on 24/08/2026, each exercised by a real fixture in
 * `src/test/resources/fixtures/apkmirror/`.
 */
@Serializable
data class ApkMirrorSelectors(
    // --- new releases (RSS feed) ---
    //
    // An XML document, not HTML: with Jsoup's HTML parser `channel > item > link` reads the empty
    // string, because `<link>` in HTML is an empty element. See `HtmlPage.ofXml`.
    val feedItem: String = "channel > item",
    val feedTitle: String = "title",
    val feedLink: String = "link",
    val feedDate: String = "pubDate",
    /**
     * The entry's HTML body, the only place the feed puts the icon.
     *
     * The description element does not have it: it carries the same two sentences without the
     * image. In the encoded content the first `<img>` is the app's icon at 384×384 — measured on
     * 27/08/2026 against the committed feed, ten entries out of ten. The file name carries the
     * package in its path, but the package cannot be read from there: that segment is a convention
     * of their uploader, not a field.
     *
     * The namespace separator is a vertical bar and not a colon: in an XML document Jsoup uses the
     * bar, and with a colon the selector finds nothing **silently**.
     */
    val feedContent: String = "content|encoded",
    /** The first image inside the entry's body. */
    val feedContentIcon: String = "img[src]",

    // --- search ---
    /**
     * The results container, and it is **not the row class on the document**.
     *
     * The "no results" page contains **38** rows of that class: they are sidebar widgets —
     * "Popular uploads", "Latest uploads" — which apkmirror renders with the identical markup of
     * results. A parser looking for that class across the whole page would return 38 arbitrary
     * apps for a query that found nothing.
     */
    val searchResults: String = "#content.search-area .listWidget",
    val searchRow: String = "div.appRow",
    val searchTitleLink: String = "h5.appRowTitle a.fontBlack",
    val searchDeveloper: String = "a.byDeveloper",
    val searchIcon: String = "img.ellipsisText",
    val searchDate: String = "span.dateyear_utc",

    // --- app listing ---
    val appTitle: String = "h1.app-title",
    val appDeveloper: String = "h3.dev-title a",
    val appIcon: String = "div.bubble-wrap img",
    val appPlayStoreLink: String = "a[href*=\"play.google.com/store/apps/details\"]",
    val appScreenshot: String = ".gallery-container img[data-lightbox]",
    /**
     * The description, which exists and was not read until 27/08/2026.
     *
     * apkmirror was **the only one of the nine** not populating the description. Not because it
     * does not publish one: it sits in the "About {app}" panel. What keeps it away from the page's
     * other notes blocks — there is a second one, holding the two-line summary — is the sibling of
     * the anchor declaring which panel it is. A bare selector would take the first, i.e. the
     * summary, and the listing would show two lines in place of the description.
     */
    val appDescription: String = "a.doc-anchor[name=description] ~ .row div.notes",
    /**
     * What sits **inside** the description and is not the description.
     *
     * One block is what apkmirror inserts between the first and second paragraph: it carries the
     * word "Advertisement", an upsell and an ad slot. The other is the "More"/"Less" links at the
     * end, which are a control of the page. Without removing them, every app's description on this
     * store would start with an advert — a different case from pdalife's "first element": here the
     * advert is not next to the content, it is in the middle of it.
     */
    val appDescriptionNoise: List<String> = DEFAULT_DESCRIPTION_NOISE,
    val appReleaseRow: String = ".listWidget div.appRow",
    val appReleaseLink: String = "h5.appRowTitle a.fontBlack",

    // --- release page ---
    val releaseVariantsTable: String = ".variants-table",
    val releaseRow: String = ".table-row",
    val releaseVariantLink: String = "a.accent_color",
    val releaseBadge: String = ".apkm-badge",
    val releaseCell: String = ".table-cell",
    val releaseVersionCode: String = "span.colorLightBlack",

    // --- variant page ---
    val variantSpec: String = ".apk-detail-table .appspec-value",
    val variantDownloadButton: String = "a.downloadButton",
    val variantSafeModal: String = "#safeDownload .modal-body",

    // --- interstitial ---
    val interstitialLink: String = "a#download-link",

    // --- labels inside the blocks ---
    val labelVersion: String = "Version:",
    val labelPackage: String = "Package:",
    val labelMinSdk: String = "Min:",
    val labelTargetSdk: String = "Target:",
    val labelCertificateHashes: String = "APK certificate fingerprints",
    val labelFileHashes: String = "APK file hashes",
    val badgeBundle: String = "BUNDLE",
)
