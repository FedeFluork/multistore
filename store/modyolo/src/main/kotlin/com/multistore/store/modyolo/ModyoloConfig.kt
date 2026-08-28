package com.multistore.store.modyolo

import com.multistore.store.api.StoreMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable

/**
 * The modyolo adapter's compiled defaults.
 *
 * Selectors and patterns are never hardcoded in Kotlin. Here the "selectors" are largely **endpoint
 * and parameter names**, because modyolo exposes two JSON APIs: changing one is still a repair to
 * publish rather than to release.
 *
 * Every value was **measured** on 25/08/2026 from an Italian consumer IP.
 *
 * ### Two APIs, and both are needed
 *
 *  - the standard WordPress REST posts endpoint searches, paginates properly (with total headers)
 *    and — uniquely — accepts a category exclusion, i.e. the adult-content filter;
 *  - the theme's own post endpoint is the only one publishing publisher, genre, size, modification
 *    information and the latest version (the typo in that field name is theirs).
 *
 * Search uses the first, the listing the second.
 *
 * ### The file is written on no page
 *
 * The theme endpoint's downloads array is **always empty** — 0 out of 81 posts in one sample, and 0
 * across everything sampled here — and the download page's HTML does not contain it either. The
 * theme asks for it with a POST to the WordPress AJAX endpoint, and **decides which file to serve
 * from the `Referer`**: without it, the same request answers 200 with twenty empty bytes. The
 * `Referer` has to carry the variant's index; the one without an index is not enough.
 *
 * That AJAX endpoint is the **only** path their `robots.txt` explicitly allows, and it is the same
 * request the browser makes.
 */
@Serializable
data class ModyoloConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * A browser User-Agent.
     *
     * modyolo does not ask for one: a library UA, no UA and Chrome mobile all receive the same
     * response byte for byte. The field stays mandatory in the contract and the fixtures were
     * captured with this value.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    /**
     * The ids of the categories modyolo labels as adult.
     *
     * WordPress numbers rather than names, because that is what the exclusion parameter accepts.
     * They live in configuration — and not among the constants — precisely because a new id is this
     * adapter's likeliest update: modyolo added five alongside the original one. Covering one more
     * must cost a published document, not a release.
     *
     * **What this list is not:** a complete inventory of the catalogue's adult content. Measured
     * the same day: the site's three most recent articles are adult visual novels distributed via
     * Patreon, and they sit in "Role Playing". The app hides what modyolo labels, and the setting's
     * description says so in those words.
     */
    val nsfwCategoryIds: List<Int> = DEFAULT_NSFW_CATEGORY_IDS,
    val selectors: ModyoloSelectors = ModyoloSelectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    /**
     * Search. [page] is zero-based; WordPress counts from 1.
     *
     * Embedding the featured media costs: the response goes from 3 KB to 180 KB uncompressed (14 KB
     * on the wire) for twenty results, because WordPress attaches the whole media object with its
     * links. It is paid because it is **the only** way to get the icon without a second request per
     * result, and without building the URL by hand — which the project forbids and which would be
     * wrong here anyway, since the path contains the upload's year and month.
     */
    fun searchUrl(query: String, page: Int, includeNsfw: Boolean): String {
        val builder = StringBuilder("$root$SEARCH_PATH?$searchQueryFields")
        builder.append("&$SEARCH_PARAM=").append(query.encodeQueryValue())
        builder.append("&$PAGE_PARAM=").append(page + 1)
        if (!includeNsfw && nsfwCategoryIds.isNotEmpty()) {
            builder.append("&$EXCLUDE_PARAM=").append(nsfwCategoryIds.joinToString(","))
        }
        return builder.toString()
    }

    /** The listing, from the theme's endpoint. */
    fun detailUrl(id: String): String = "$root$DETAIL_PATH/$id"

    /**
     * The **human** page of the listing, which is not [detailUrl].
     *
     * `detailUrl` is the JSON endpoint the adapter queries; what a browser would open is
     * `<root>/<slug>.html`. The shape is not inferred from WordPress's permalink structure: it is
     * the `link` field the search API publishes on every post, and which the ref keeps in its slug.
     */
    fun webListingUrl(slug: String): String = "$root/$slug$WEB_SUFFIX"

    /** The page listing the variants. */
    fun downloadPageUrl(stem: String): String = "$root$DOWNLOAD_PATH/$stem"

    /** A **single** variant's page, which is also the `Referer` the AJAX call demands. */
    fun downloadVariantUrl(stem: String, variant: Int): String =
        "${downloadPageUrl(stem)}/$variant"

    val ajaxUrl: String get() = "$root$AJAX_PATH"

    val ajaxForm: Map<String, String> get() = mapOf(AJAX_ACTION_PARAM to AJAX_ACTION)

    private val searchQueryFields: String
        get() = "$PER_PAGE_PARAM=$PAGE_SIZE&$EMBED_PARAM=$EMBED_VALUE&$FIELDS_PARAM=$FIELDS_VALUE"

    val pageSize: Int get() = PAGE_SIZE

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            // The site is in English, but its APIs answer in Vietnamese: every response's message
            // field is a Vietnamese success string. It is an implementation detail of the theme,
            // not of the content's language — which is and stays English.
            listingLanguage = "en",
            host = HOST,
        )

    companion object {
        const val DISPLAY_NAME: String = "MODYOLO"
        const val HOST: String = "modyolo.com"
        const val DEFAULT_BASE_URL: String = "https://modyolo.com"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * No `Crawl-delay` in their `robots.txt` (it forbids only the admin area, and explicitly
         * allows the AJAX endpoint), and twelve consecutive queries without a pause all answered
         * 200. The value stays cautious anyway.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.5
        const val DEFAULT_BURST: Int = 3

        val DEFAULT_LISTING_TTL: Duration = 6.hours

        /** The six adult category slugs — see [nsfwCategoryIds]. */
        val DEFAULT_NSFW_CATEGORY_IDS: List<Int> = listOf(5410, 10637, 10638, 10639, 10640, 10644)

        const val SEARCH_PATH: String = "/wp-json/wp/v2/posts"
        const val DETAIL_PATH: String = "/wp-json/v1/posts"
        const val DOWNLOAD_PATH: String = "/download"
        const val AJAX_PATH: String = "/wp-admin/admin-ajax.php"

        const val SEARCH_PARAM: String = "search"
        const val PAGE_PARAM: String = "page"
        const val PER_PAGE_PARAM: String = "per_page"
        const val EXCLUDE_PARAM: String = "categories_exclude"
        const val FIELDS_PARAM: String = "_fields"
        const val EMBED_PARAM: String = "_embed"

        /** The extension their WordPress permalinks carry. */
        const val WEB_SUFFIX: String = ".html"
        const val EMBED_VALUE: String = "wp%3Afeaturedmedia"
        const val AJAX_ACTION_PARAM: String = "action"
        const val AJAX_ACTION: String = "k_get_download"

        /**
         * The links field is in the list **on purpose**, and without it the embedded media does not
         * arrive.
         *
         * WordPress builds the embedded objects from the links, so a `_fields` that leaves them
         * out returns a smaller response **with no icons** — with `_embed` seeming to work and not
         * working. It is the kind of fault that goes unnoticed: no error, only grey results.
         */
        const val FIELDS_VALUE: String = "id,slug,link,title,excerpt,categories,date,_links,_embedded"

        /** Twenty per page, like the app's other searches. */
        private const val PAGE_SIZE: Int = 20
    }
}

/**
 * Where the response is read: JSON keys, and the selectors of the two HTML pages.
 *
 * The JSON keys live here for the same reason as the CSS selectors. `lastest_version` is spelled
 * that way in their schema — the typo is theirs — and the day they fix it will be a `parsers.json`,
 * not a release.
 */
@Serializable
data class ModyoloSelectors(
    // --- download page: the variant list ---
    //
    // Each variant is an `<a href="#version-N">` carrying the version's name. The link to the file
    // **is not there**: the current variant has an empty body because it is the page you are
    // already on, and the others link to `/download/{slug}-{id}/N`.
    val versionItem: String = "#accordion-versions > div",

    /**
     * The download page's heading: `Toolbox for Minecraft: PE - v5.4.54 - Mod`.
     *
     * Needed **when there is no accordion**, i.e. when there is a single variant: modyolo then
     * emits no "Other available link(s)" section at all, and without this fallback the listing
     * would say "no installable package" in front of a file that downloads perfectly well. The
     * site logo is also an `<h1>`, but with class `h3 … site-logo`: the `h5` class is what
     * separates them.
     */
    val downloadHeading: String = "h1.h5",
    val versionToggle: String = "a.toggler[href^=#version-]",
    val versionSize: String = ".collapse a[href] .ml-auto",

    // --- fragment returned by the AJAX call ---
    //
    // `a.download[href]`. It is the only link in the fragment leading to the file; the other
    // (`#click-here`) points at the same URL and exists only in case the automatic download does
    // not start.
    val ajaxDownloadLink: String = "a.download[href]",
    val ajaxDownloadSize: String = "a.download .align-middle",

    // --- the theme endpoint's keys ---
    val detailEnvelope: String = "data",
) {
    companion object {
        /** The prefix of the accordion's `href`s: `#version-3` -> variant 3. */
        const val VERSION_ANCHOR_PREFIX: String = "#version-"
    }
}

private fun String.encodeQueryValue(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8)
