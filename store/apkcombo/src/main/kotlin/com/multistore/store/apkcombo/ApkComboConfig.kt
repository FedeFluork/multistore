package com.multistore.store.apkcombo

import com.multistore.store.api.StoreMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable

/**
 * The apkcombo adapter's compiled defaults.
 *
 * CSS selectors and regex patterns are never hardcoded in Kotlin: they live here, so a markup
 * change is fixable without a release. `@Serializable` because the signed document overrides this
 * field by field.
 *
 * Every value was **measured** on 24/08/2026 from an Italian consumer IP.
 */
@Serializable
data class ApkComboConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * A browser User-Agent.
     *
     * apkcombo does not ask for one — verified: the download page returns **91,211 identical
     * bytes** with no UA, with curl's, with OkHttp's and with Chrome's. The value is here anyway
     * because the field is mandatory in the contract, and because this module's fixtures were
     * captured with **this** UA.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    /**
     * How long a saved listing stays valid.
     *
     * Six hours, against F-Droid's seven days: F-Droid declares its index's freshness in the entry
     * document, while here there is an HTML page with no validity statement at all. A scraped
     * listing ages in hours.
     */
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    val selectors: ApkComboSelectors = ApkComboSelectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    fun searchUrl(query: String): String = "$root/search/" + query.encodePathSegment()

    /** `ref` is already the `slug/packageName` path: see [ApkComboRefs]. */
    fun listingUrl(path: String): String = "$root/${path.trim('/')}/"

    fun downloadUrl(path: String, versionSegment: String): String =
        "$root/${path.trim('/')}/download/$versionSegment"

    fun oldVersionsUrl(path: String): String = "$root/${path.trim('/')}/old-versions/"

    /**
     * The feed of just-updated apps.
     *
     * One of the two the site declares in its own `<head>`; the other lists apps appearing for the
     * first time. See the note atop `ApkComboFeedParser` for why this one is used.
     */
    fun recentFeedUrl(): String = "$root/$RECENT_FEED_PATH"

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            // apkcombo publishes the same listing in 19 languages under a path prefix. We serve
            // English: it is the canonical path, the one the 301s lead to, and the only one the
            // fixtures were captured on.
            listingLanguage = "en",
            host = HOST,
        )

    companion object {
        const val DISPLAY_NAME: String = "APKCombo"
        const val HOST: String = "apkcombo.com"
        const val DEFAULT_BASE_URL: String = "https://apkcombo.com"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * No declared `Crawl-delay` and no rate limiting observed across ~35 requests. We stay
         * below what a browser does opening a page with twenty icons anyway.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.5
        const val DEFAULT_BURST: Int = 3

        val DEFAULT_LISTING_TTL: Duration = 6.hours

        /** The segment asking for the current version: `…/download/apk`. */
        const val LATEST_VERSION_SEGMENT: String = "apk"

        /** 98 entries measured on 25/08/2026, all with a `<pubDate>` of the same day. */
        const val RECENT_FEED_PATH: String = "latest-updates/feed"
    }
}

/**
 * The selectors, kept apart from the code that uses them.
 *
 * These are the ones observed on 24/08/2026, and each is exercised by a real fixture. Changing one
 * here without updating the fixture fails the test: that is how "selectors are data" stays true
 * instead of becoming "selectors are data nobody checks".
 */
@Serializable
data class ApkComboSelectors(
    // --- search ---
    val searchItem: String = "div.content-apps a.l_item",
    val searchName: String = ".info .name",
    val searchAuthor: String = ".info .author",
    val searchIcon: String = "figure img",
    val searchSizeSpan: String = ".info .description span.ltr",
    val searchDescriptionSpan: String = ".info .description > span",

    // --- detail ---
    val detailTitle: String = ".app_name h1",
    val detailVersion: String = ".info .version",
    val detailDeveloper: String = ".info .author a",
    val detailSummary: String = "h2.short-description",
    val detailIcon: String = ".app_header .avatar img",
    val detailScreenshot: String = "#gallery-screenshots a[data-href]",
    val detailBreadcrumbCategory: String = "nav.breadcrumb a[href*=\"/category/\"]",
    val detailInfoRow: String = ".information-table .item",
    val detailInfoName: String = ".name",
    val detailInfoValue: String = ".value",

    // --- download ---
    val downloadVariantsTab: String = "#variants-tab",
    val downloadBestTab: String = "#best-variant-tab",
    val downloadArchGroup: String = ".tree > ul > li",
    val downloadArchLabel: String = "code",
    val downloadVariant: String = "a.variant",
    val downloadVariantName: String = ".vername",
    val downloadVariantCode: String = ".vercode",
    val downloadVariantTypeApk: String = ".vtype .type-apk",
    val downloadVariantTypeXapk: String = ".vtype .type-xapk",
    val downloadVariantSpec: String = ".description .spec",

    // --- new releases (RSS feed) ---
    //
    // The document is XML and not HTML, and the difference is not formal: with Jsoup's HTML parser
    // `channel > item > link` reads the **empty string**, because in HTML `<link>` is an empty
    // element and the URL becomes a sibling text node. See `HtmlPage.ofXml`.
    val feedItem: String = "channel > item",
    val feedTitle: String = "title",
    val feedLink: String = "link",
    val feedDate: String = "pubDate",

    // --- old versions ---
    val oldVersionItem: String = "ul.list-versions a.ver-item",
    val oldVersionName: String = ".vername",
    val oldVersionDescription: String = ".description",

    // --- information-table labels ---
    //
    // These are **site text**, not CSS selectors, and they are here for the same reason: apkcombo
    // translates the table into its 19 languages, and serving a localised listing one day must be
    // a configuration update, not a release. Hence constructor parameters and not constants in the
    // class body: only the former are serialised.
    val infoRowPackageName: String = "Google Play ID",
    val infoRowVersion: String = "Version",
    val infoRowUpdate: String = "Update",
    val infoRowCategory: String = "Category",
    val infoRowInstalls: String = "Installs",
)

private fun String.encodePathSegment(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
