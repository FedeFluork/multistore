package com.multistore.store.an1

import com.multistore.store.api.StoreMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable

/**
 * The compiled defaults of the an1 adapter.
 *
 * CSS selectors and regex patterns are never hardcoded in Kotlin: they live in the parser's
 * configuration (compiled defaults plus overrides from the signed document).
 *
 * Every value was **measured** on 25/08/2026 against `an1.com` from an Italian consumer IP.
 *
 * ### The CMS is DataLife Engine, and it shows in the shape of the search
 *
 * Not WordPress: the usual API paths answer 301 towards the root. The search is
 * `?do=search&subaction=search&story={q}`, and pagination has **two** parameters instead of one —
 * one counting pages, one counting results. They are not redundant for DLE: their theme's submit
 * function sets both, and sending only one returns the first page.
 *
 * ### The search is `Disallow`ed in robots.txt, and is used anyway
 *
 * `robots.txt` forbids every path containing `do=search`. This project's rule is that
 * **`robots.txt` is not a constraint on requests the user originated**: a search is exactly a
 * person who has just typed a query, and a browser opening that page does not consult
 * `robots.txt`.
 *
 * What `robots.txt` still decides was read all the same: there is no `Crawl-delay`, so
 * [permitsPerSecond] is the cautious default and not a value the site dictated; and the two
 * declared sitemaps are unusable because the host they name is a Cyrillic homoglyph that does not
 * resolve.
 *
 * ### No `packageName`, and no `versionCode`, anywhere on the site
 *
 * Eight sampled listings: zero package names, zero links to Google Play, zero version codes. Not
 * an isolated case but a property of the site, with two consequences the adapter declares rather
 * than works around: cross-store identity for an1 rests **only** on title and developer (the
 * matcher will never reach `0.85` on its own, and the listing will land in "possible match"), and
 * version selection answers "cannot be known" rather than "up to date".
 */
@Serializable
data class An1Config(
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * The hosts the files actually come from. **There is more than one, and finding that out cost
     * a failure on the device.**
     *
     * The first draft declared one, because that is the one serving the sampled apps. On the
     * emulator, "Blockman GO" answered **"The store answered in an unexpected format"**: its file
     * is on the second host. Across twelve listings sampled after the failure, **two** use it, and
     * they are the two large ones (612 MB and 929 MB).
     *
     * It stays a **filter** and not a descriptive datum: on the download page an1 puts a second
     * `.apk` next to the file, its own store app, on the **same** host — so what tells them apart
     * is the anchor id, not this list — and further down a sponsor that lives elsewhere, which this
     * list is what discards.
     *
     * **Two listings out of twelve put a link shortener in the download anchor**, pointing at
     * Google Drive, and those stay out: see the note on `NotFound` in the download parser.
     */
    val downloadHosts: List<String> = DEFAULT_DOWNLOAD_HOSTS,
    /**
     * A browser User-Agent.
     *
     * an1 does not ask for one: a library UA, no UA and Chrome mobile all receive **the same
     * response**, byte for byte, on search and detail. The field stays mandatory in the contract —
     * apkmirror demonstrated what leaving it at the default costs — and these fixtures were
     * captured with this value.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    val selectors: An1Selectors = An1Selectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    /**
     * The search URL. [page] is zero-based; DLE counts its pages from 1.
     *
     * The second parameter is not derivable from the first downstream: it is
     * `(page - 1) * 10 + 1`, i.e. it depends on how many results DLE puts per page, which is a
     * fact of their theme. That is why it is in configuration.
     */
    fun searchUrl(query: String, page: Int): String {
        val encoded = query.encodeQueryValue()
        val base = "$root/index.php?do=search&subaction=search&story=$encoded"
        if (page <= 0) return base
        val start = page + 1
        val from = page * resultsPerPage + 1
        return "$base&search_start=$start&result_from=$from"
    }

    /** [ref] is already `2971-telegram`: see [An1Refs]. */
    fun listingUrl(ref: String): String = "$root/$ref.html"

    /** The page carrying the real link: `/file_2971-dw.html`. */
    fun downloadUrl(id: String): String = "$root/${DOWNLOAD_PREFIX}$id$DOWNLOAD_SUFFIX"

    val resultsPerPage: Int get() = RESULTS_PER_PAGE

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            // an1 publishes the same catalogue under a language prefix too. The root is English,
            // and it is the one serving `<html lang="en">` — but the listing texts stay mixed:
            // many descriptions are machine-translated from Russian. Store descriptions are not
            // ours to translate, and `listingLanguage` exists precisely to be able to tell the
            // user.
            listingLanguage = "en",
            host = HOST,
        )

    companion object {
        const val DISPLAY_NAME: String = "AN1"
        const val HOST: String = "an1.com"
        const val DEFAULT_BASE_URL: String = "https://an1.com"
        /**
         * The three observed hosts. The third has not turned up in the twelve sampled listings:
         * it is here because it costs nothing and its absence would cost another "unexpected
         * format".
         */
        val DEFAULT_DOWNLOAD_HOSTS: List<String> =
            listOf("files.an1.net", "files.an1.co", "file.an1.co")

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * No `Crawl-delay` in their `robots.txt`, and no 429 across some fifty requests. The
         * cautious default is enough: an1 is a high-risk store, and the cost of being slow is far
         * lower than the cost of being blocked.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.0
        const val DEFAULT_BURST: Int = 3

        /**
         * Three hours, shorter than the six of the other scraped stores because an1 does not
         * publish a `versionCode`: the only way to notice an update is to re-read the listing and
         * compare the version string.
         */
        val DEFAULT_LISTING_TTL: Duration = 3.hours

        /** `/file_2971-dw.html` — the prefix and suffix are their theme's, not ours. */
        const val DOWNLOAD_PREFIX: String = "file_"
        const val DOWNLOAD_SUFFIX: String = "-dw.html"

        /** Ten per page: measured (p.1 = 10 results, p.2 = 4, no overlap). */
        private const val RESULTS_PER_PAGE: Int = 10
    }
}

/**
 * The selectors, kept apart from the code that uses them.
 *
 * These are the ones observed on 25/08/2026, and each is exercised by a real fixture in
 * `src/test/resources/fixtures/an1/`.
 */
@Serializable
data class An1Selectors(
    // --- search ---
    //
    // an1 has no results container: the rows sit under the content element along with everything
    // else, and the "no results" page simply **does not contain** the row class. Verified on the
    // empty-search fixture: zero occurrences, and no sidebar reusing the same markup — unlike
    // apkmirror, where 38 sidebar rows are identical to results.
    val searchItem: String = "div.item_app",
    val searchLink: String = ".cont .data .name a[href]",
    val searchIcon: String = ".img img[src]",
    val searchDeveloper: String = ".cont .data .developer",
    val searchRating: String = ".meta li.current-rating",

    // --- detail ---
    val detailName: String = "meta[itemprop=name]",
    val detailIcon: String = "figure.img img[itemprop=image]",
    val detailVersion: String = "[itemprop=softwareVersion]",
    val detailSize: String = "[itemprop=fileSize]",
    val detailOperatingSystem: String = "[itemprop=operatingSystem]",
    val detailDeveloper: String = "[itemprop=publisher] [itemprop=name]",
    val detailDescription: String = "[itemprop=description]",
    val detailCategory: String = "meta[itemprop=applicationCategory]",
    val detailSubCategory: String = "meta[itemprop=applicationSubCategory]",
    val detailUpdated: String = "time[itemprop=datePublished]",
    val detailRatingValue: String = "[itemprop=ratingValue]",
    val detailRatingCount: String = "[itemprop=ratingCount]",

    // --- download ---
    //
    // The download anchor's id is the only anchoring that holds, because the download page
    // **contains a second `.apk` on the same host**: their own store app. A host filter would not
    // tell them apart, and taking "the first `.apk`" would take exactly that one — the same trick
    // as apkmody, with the difference that there the host was enough.
    val downloadLink: String = "a#pre_download[href]",
) {
    companion object {
        /** The compiled search-item selector, for error messages. */
        const val SEARCH_ITEM_DEFAULT: String = "div.item_app"
    }
}

private fun String.encodeQueryValue(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8)
