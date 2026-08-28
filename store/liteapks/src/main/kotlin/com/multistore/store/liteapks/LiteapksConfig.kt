package com.multistore.store.liteapks

import com.multistore.store.api.StoreMetadata
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable

/**
 * The liteapks adapter's compiled defaults.
 *
 * CSS selectors and regex patterns are never hardcoded in Kotlin: they live in the parser's
 * configuration (compiled defaults plus `parsers.json` overrides).
 *
 * Every value is **measured**, on 25/08/2026, against `liteapks.com` from an Italian consumer IP,
 * with OkHttp — not with `curl`, and on this store the difference is not theoretical: see below.
 *
 * ### Four `curl` findings the real client contradicts
 *
 * `curl` gets `403 cf-mitigated: challenge` almost everywhere here. With the client the app really
 * ships, the four conclusions fall one by one:
 *
 * | with `curl` | measured with OkHttp |
 * |---|---|
 * | "the listing is 403 → a silent challenge resolver is needed" | **200**, the whole listing |
 * | "search capped at **one** page, ~9 results" | **18 per page**, four pages, `paged` honoured |
 * | "`?s=…&paged=2` → **404**" | 200 with different results; the 404 arrives **past** the last page |
 * | "download to be measured" | **DIRECT**: no captcha, no human gesture |
 *
 * ### The search cap exists, but it is sixty and not nine
 *
 * `h1#search-title` declares the total in parentheses, and it is true while it is small: `telegram`
 * says `(7)` and the rows are 7, `minecraft` says `(8)` and they are 8. Above that it **saturates**:
 * `a`, `game`, `mod`, `pro` and `e` all five say `(60)`, which is the theme's cap. At 18 per page
 * that is exactly four pages — `paged=4` serves 6, `paged=5` answers 404.
 *
 * Hence [SEARCH_RESULT_CAP]: the number is not "how many there are" but "how many can be had", and
 * calling it a total would be a lie on every popular query.
 *
 * ### `robots.txt`, read for what it actually decides
 *
 * Two lines in all: `User-agent: *` and an **empty** `Disallow:`, i.e. no prohibitions. No
 * `Crawl-delay`, so [permitsPerSecond] is the cautious default and not a value dictated by the site.
 * The `Sitemap:` declares `sitemap_index.xml` — the bulk channel, which we do not use because we do
 * not do mass crawling.
 */
@Serializable
data class LiteapksConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * A browser User-Agent, and here it **is not cosmetic**.
     *
     * Measured on the same URL, with the same client: OkHttp with a Chrome mobile UA gets 200;
     * OkHttp with `curl/8.7.1` gets **403 `cf-mitigated: challenge`**. Among the nine stores it is
     * the second, after apkmirror, where the User-Agent alone decides between the page and the
     * block.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    /**
     * How long the transit permit attached to the file URL is worth.
     *
     * Three hours, which is the value the site itself writes in its own JavaScript. See the note on
     * `downloadToken` in [LiteapksRefs]: it is not a secret and proves nothing, it is a
     * client-declared expiry. It lives in configuration because if the worker one day shortened the
     * window, the fix must be publishable without a release.
     */
    val downloadTokenTtl: Duration = DEFAULT_DOWNLOAD_TOKEN_TTL,
    /**
     * The hosts serving the files, and which therefore want the transit permit.
     *
     * They are the two the theme lists in `WORKER_DOWNLOAD_HOSTS`, and its own note explains why
     * the list does not cover all the store's CDNs: "most point to Google Play / `gp*.liteapks.com`
     * (no barrier); only the few pointing to `download*.liteapks.dev` get 403 without a token".
     *
     * Verified host by host: `download.liteapks.dev` answers **403 "Access is not allowed"**
     * without a token *and* without a `Referer` from liteapks.com, and 200 with both;
     * `down.appsupload.com` and `gp4.liteapks.com` ask for nothing.
     */
    val tokenizedFileHosts: Set<String> = DEFAULT_TOKENIZED_FILE_HOSTS,
    val selectors: LiteapksSelectors = LiteapksSelectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    /**
     * The search URL. [page] is zero-based; liteapks numbers its pages from 1 and the first
     * **has no** parameter.
     *
     * Two forms work — `/?s=q&paged=2` and `/page/2/?s=q`, nearly identical bytes — and the first
     * is used because it is the one the theme's search module generates. **`page` is not `paged`**:
     * `?s=game&page=2` answers 200 with the *first* page's results, which is the most convenient
     * way to believe pagination does not exist.
     */
    fun searchUrl(query: String, page: Int): String {
        val base = "$root/?$SEARCH_PARAM=${query.encodeQueryValue()}"
        if (page <= 0) return base
        return "$base&$PAGE_PARAM=${page + 1}"
    }

    /** [slug] is already `telegram`: see [LiteapksRefs]. */
    fun listingUrl(slug: String): String = "$root/$slug$HTML_SUFFIX"

    /**
     * A post's file page: `/download/{any-slug}-{postId}`.
     *
     * The slug in front **does not matter** — `/download/zzz-810` answers 200 with Telegram's page —
     * because WordPress resolves on the trailing id. That is not an invitation to write anything:
     * the slug the listing publishes is kept, so the URL we ask for is the one a browser would ask
     * for. It does tell us that **the id is the identity**, though, and that is why the version ref
     * carries the id and not the slug.
     */
    fun downloadUrl(stem: String): String = "$root/$DOWNLOAD_PATH/$stem"

    /** The single file of that post: `/download/{stem}/{index}`. */
    fun downloadSlotUrl(stem: String, slot: Int): String = "${downloadUrl(stem)}/$slot"

    val resultsPerPage: Int get() = RESULTS_PER_PAGE

    val searchResultCap: Int get() = SEARCH_RESULT_CAP

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            listingLanguage = LISTING_LANGUAGE,
            host = HOST,
        )

    companion object {
        const val DISPLAY_NAME: String = "LiteAPKs"
        const val HOST: String = "liteapks.com"
        const val DEFAULT_BASE_URL: String = "https://liteapks.com"
        const val LISTING_LANGUAGE: String = "en"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * No `Crawl-delay` in their `robots.txt` and no 429 across some ninety requests spanning
         * search, listings, download pages and slots.
         *
         * The cautious default stays: liteapks is a high-risk store, a listing weighs over 100 KB,
         * and Cloudflare here **really does challenge** whoever does not resemble a browser —
         * unlike pdalife, where it sits in passive CDN mode.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.0
        const val DEFAULT_BURST: Int = 3

        /**
         * Six hours, like the other scraped stores that publish only the version name.
         *
         * Not shorter: there is no version code here, so re-reading more often would not buy a more
         * exact comparison — it would buy the same string.
         */
        val DEFAULT_LISTING_TTL: Duration = 6.hours

        /** `Math.floor(Date.now() / 1000) + 3600 * 3`, as their `site.js` writes it. */
        val DEFAULT_DOWNLOAD_TOKEN_TTL: Duration = 3.hours

        val DEFAULT_TOKENIZED_FILE_HOSTS: Set<String> =
            setOf("download.liteapks.dev", "download-old.liteapks.dev")

        /** Eighteen per page: measured on `game` (18, 18, 18, 6) and on `a` (18). */
        private const val RESULTS_PER_PAGE: Int = 18

        /**
         * The theme's cap: **sixty**, and the same number for five different queries.
         *
         * It is neither the catalogue's total nor the results' total: it is how much the search is
         * willing to serve. Past `paged=4` it answers 404.
         */
        private const val SEARCH_RESULT_CAP: Int = 60

        private const val SEARCH_PARAM = "s"
        private const val PAGE_PARAM = "paged"
        private const val DOWNLOAD_PATH = "download"
        private const val HTML_SUFFIX = ".html"
    }
}

/**
 * The query segment, percent-encoded.
 *
 * `URLEncoder` is right **here** and was wrong on pdalife: there the query lived in the path and `+`
 * meant 404; here it lives in a query string, where `+` is the correct encoding of a space —
 * verified, `?s=plus+messenger` and `?s=plus%20messenger` give the same results.
 */
private fun String.encodeQueryValue(): String = URLEncoder.encode(this, Charsets.UTF_8)

/**
 * The selectors, kept apart from the code using them.
 *
 * These are the ones observed on 25/08/2026 and each is exercised by a real fixture in
 * `src/test/resources/fixtures/liteapks/`.
 *
 * ### Why half the listing is read from the JSON-LD and not from the markup
 *
 * The theme is written in Tailwind utility classes, and the listing shows it: the app's name is in
 * an `h1` reading `Telegram v12.10.1 MOD APK (Premium, Lite, No ADS)`, the version in a `div.value`
 * distinguishable from its three siblings only by the English label next to it, and the rating in
 * another `div.value` identical to the previous one. Anchoring on those classes means anchoring on
 * a styling choice.
 *
 * On the same page, though, there is an `application/ld+json` block of type `SoftwareApplication` —
 * schema.org, not theme markup — with `name`, `softwareVersion`, `applicationCategory` and an
 * `aggregateRating` complete with `bestRating`, `worstRating` and `ratingCount`. **Measured across
 * all thirty-one sampled listings: 31 out of 31 have it, with all four fields.**
 *
 * Hence the division: from the JSON-LD what that block declares, from the markup what does not fit
 * in it (icon, developer, screenshots, Play link, file page, MOD traits, date). The block's selector
 * still lives here, so that choice too is repairable by publishing `parsers.json`.
 */
@Serializable
data class LiteapksSelectors(
    // --- search ---
    /**
     * The results heading, which serves **two** purposes and the second is the one that counts.
     *
     * The first: it carries the total in parentheses. The second: it is the proof that the page
     * downloaded **is** a search page. A search with no results on liteapks does not produce an
     * empty container — `div#apps-grid` **does not exist at all**, and there is no `<article>`
     * anywhere. Without a second signal, "no results" and "the selector is dead" would be the same
     * page as seen by the parser, which is exactly the distinction `mapRowsOrFail` exists to make
     * and which here has to be made one level up.
     */
    val searchTitle: String = "h1#search-title",
    /**
     * The result row.
     *
     * **The container is not a defence, and the code says so rather than letting it be assumed.**
     * Counted across six real pages — 75 cards in all, including the zero-result one and the
     * partial last page — the `article[aria-label]`s outside `div#apps-grid` are **zero**: the
     * sidebar, the listings' "similar" section and the home use different markup and produce no
     * `article`. It stays because it costs nothing and because it tells the reader *where* we are
     * looking; not because it excludes anything today.
     */
    val searchItem: String = "div#apps-grid > article[aria-label]",
    val searchLink: String = "a[href]",
    val searchName: String = "h2",
    val searchIcon: String = "figure img[src]",
    /**
     * The rating, read from the `aria-label` and not from the text.
     *
     * `<span class="absolute -bottom-1.5 …" aria-label="Rating 4">` with an SVG star and the number
     * inside. The selector takes `figure`'s direct child carrying an `aria-label` — of which there
     * is exactly one — instead of matching the word "Rating", which is English text.
     */
    val searchRating: String = "figure > span[aria-label]",
    /** `<span aria-label="Version 12.10.1">v12.10.1</span>`, the row's only `aria-label`. */
    val searchVersion: String = "p span[aria-label]",
    /** The MOD traits: `Premium, Lite, No ADS`. It is the only description the card carries. */
    val searchModTraits: String = "p.text-orange",
    // --- detail ---
    /** The schema.org block. There are two on the page: Yoast's and this one. See the note above. */
    val detailJsonLd: String = "script[type=application/ld+json]",
    val detailIcon: String = ".app-info-icon img[src]",
    val detailDeveloper: String = ".app-info-text .developer a",
    val detailDescription: String = "#tab-desc .desc-content",
    /** `MOD: Premium, Lite, No ADS`. Absent on unmodified listings. */
    val detailModTraits: String = ".sub-info p.text-orange",
    val detailScreenshot: String = "#screenshotScroll img[src]",
    /**
     * The Google Play link **inside the stats box**, which is the only real one.
     *
     * On this store the trap is worse than on pdalife, because it works almost always. Measured
     * across 31 listings: the `io.apkmody.sai` advert ("XAPKS Installer") is on **31 out of 31**;
     * the real link is on 26, and is always the first of the two. So "take the page's first
     * `play.google.com`" returns the right package 26 times and `io.apkmody.sai` the other 5 — not
     * a `null`, not an error: **the wrong package, with the same confident face**.
     *
     * The container excludes it by construction: the advert sits in the article's tail, and never
     * appears inside `.app-stats` (0 out of 31).
     */
    val detailPlayLink: String = ".app-stats .app-stat a[href*=play.google.com]",
    /** The "Download APK" button, from which the file page's stem is derived. */
    val detailDownloadLink: String = ".app-cta a[href]",
    /**
     * The breadcrumbs: `Home / Apps / Communication / Telegram`.
     *
     * The second says whether it is an app or a game, the third is the category. On one listing out
     * of thirty-one the third is missing (`adventure-block`), so the category is optional and the
     * division is not.
     */
    val detailBreadcrumb: String = "nav[aria-label=Breadcrumb] a[href]",
    /**
     * The date, taken from the `<meta>` rather than from the info box.
     *
     * The box writes `Aug 25` and `2026` in two different `div`s, in English; the `<meta>` carries
     * `2026-08-25T15:15:39+00:00`, i.e. a complete instant with no months to translate. Present on
     * 31 listings out of 31.
     *
     * It is called `published_time` and is used as "updated", because on this WordPress they are
     * the same thing: the "UPDATED" box shows exactly that day — verified on Telegram (25/08) and
     * Minecraft (20/08) — and no listing publishes a `modified_time`.
     */
    val detailPublished: String = "meta[property=article:published_time]",

    // --- file page ---
    /**
     * A file's row. It is **not** anchored to `#dl-versions`, and the reason is measured.
     *
     * The page has two forms: with several versions there is a `div#dl-versions` grouping the
     * blocks, with a single one there is none at all. Across 31 pages, 17 have the tab buttons and
     * 14 do not — anchoring to the container would lose **fourteen pages out of thirty-one**, i.e.
     * nearly half this store's downloads, silently.
     */
    val downloadItem: String = "a.dl-item[href]",
    /** The block grouping the rows of one version: [HtmlPage.closest] climbs to this. */
    val downloadGroup: String = "div.border-border",
    /**
     * The block's heading, which is its **first child** in both markup forms.
     *
     * With several versions it is a `button.dl-version-tab`, with a single one a coloured `div`:
     * they share no class, and what they do share is the position. It is a positional selector
     * chosen on purpose, because here position **is** the structure — not a fallback on "the first
     * one that comes along".
     */
    val downloadGroupHeader: String = "> :first-child",
    /** The row's second label: the version, or the variant alone. See the parser. */
    val downloadItemLabel: String = "div.min-w-0 > span:nth-of-type(2)",
    /** The size, written rounded: `800 MB`, `607M`, `1.2 GB`. */
    val downloadItemSize: String = "span.ml-auto",

    // --- single-file page ---
    /**
     * The container carrying the file's URL in base64.
     *
     * `<div id="download" data-post-id="810" data-file-index="1" data-link="aHR0cHM6…">`. Present
     * on 14 sampled slots out of 14. The `data-link` is the only thing to read: the button starts
     * with `href="#!"` and their JavaScript fills it with the same value.
     */
    val slotDownload: String = "div#download[data-link]",
) {
    companion object {
        /** The compiled value of [LiteapksSelectors.searchItem], for error messages. */
        const val SEARCH_ITEM_DEFAULT: String = "div#apps-grid > article[aria-label]"

        /** The compiled value of [LiteapksSelectors.downloadItem], for error messages. */
        const val DOWNLOAD_ITEM_DEFAULT: String = "a.dl-item[href]"
    }
}
