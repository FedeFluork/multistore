package com.multistore.store.pdalife

import com.multistore.store.api.StoreMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable

/**
 * The pdalife adapter's compiled defaults.
 *
 * CSS selectors and regex patterns are never hardcoded in Kotlin: they live in the parser's
 * configuration (compiled defaults plus `parsers.json` overrides).
 *
 * Every value is **measured**, on 25/08/2026, against `pdalife.com` from an Italian consumer IP.
 *
 * ### The search is not the obvious endpoint, and the difference is measured in results
 *
 * `GET /suggest/?query=` looks ideal — JSON, robots-allowed, with `id`, `hash`, `alias`. It exists
 * and is exactly as it looks. **It is not a search**:
 *
 * - it returns **always ten** results and does not paginate (`page`, `limit`, `count` are ignored:
 *   identical bytes);
 * - it **never comes back empty.** `zzqxwvnbtklmj` answers "SEGA NET MAHJONG MJ", "Zoe", "Zoi",
 *   "ZEG"… ten apps with nothing to do with the query. In an aggregator merging nine stores, one
 *   that answers ten random results to every query is not a source: it is noise the user must learn
 *   to ignore;
 * - it carries no version, rating, description or category.
 *
 * The HTML page `/search/{slug}/` does the opposite on all three points: **20 per page, real
 * pagination**, and on `zzqxwvnbtklmj` it produces zero rows. It also carries version, rating,
 * description and date. It costs 68 KB against 5, and that is a price gladly paid.
 *
 * ### The query is normalised before being sent, because otherwise the site does it
 *
 * `/search/plus%20messenger/` answers **301** towards `/search/plus-messenger/page-1`, and from
 * there a second 301. `c++` and `a/b` answer **404**. The site has its own slugification and it is
 * better to apply it ourselves: [slugify]. That way the first page costs no redirects and
 * pagination already has the slug it needs.
 *
 * **`/search/?search={q}` is not a shortcut**: it answers 200 with "Search the site" and a generic
 * list of 17 apps (ids 63, 64, 65…) unrelated to the query. It looks like it works — it is the most
 * convenient form, it returns 200, and it even has results.
 *
 * ### `robots.txt`, read for what it actually decides
 *
 * It forbids `/forum/flud`, `/api/getapp/`, `/restore-password/`, `/confirm-account/`: `/search/`,
 * `/suggest/` and the listings are all allowed. **No `Crawl-delay`**, so [permitsPerSecond] is the
 * cautious default and not a value dictated by the site. The `Sitemap:` declares
 * `sitemap_index.xml`, 13 files of ~3,500 URLs: that is the bulk channel, and we do not use it
 * because we do not do mass crawling.
 *
 * ### The catalogue is not Android only
 *
 * pdalife also publishes iOS and PSP under the same markup: `/telegram1-ios-a26129.html`,
 * `/-psp-a34978.html` (with an empty alias). On "minecraft" they are 2 of 20 on the first page and
 * 2 of 14 on the second. The filter is structural and lives in [PdalifeSelectors.searchItem]: the
 * row must contain `a.color-android`. See the note there.
 */
@Serializable
data class PdalifeConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * A browser User-Agent.
     *
     * pdalife does not require one: `okhttp/4.12.0`, `curl/8.7.1` and no UA all answer **200** on
     * search and listing. Only the amount of advertising changes — 50,232 bytes against 56,849 on
     * the same search — and the temptation to keep the lighter page is real.
     *
     * The browser UA is sent all the same, and the reason is not uniformity: **the light page is a
     * page no human ever sees.** A selector proven on it might not exist on the real one, and the
     * day pdalife stopped serving it we would notice from the empty results. We read the user's
     * page.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    /**
     * The **store's** rating scale, which here is ten and not five.
     *
     * `<meta itemprop='bestRating' content='10'/>`, with `ratingValue` at `9.2292`. It lives in
     * configuration and not in code for the same reason as the selectors: it is a fact about the
     * site, and if one day they moved to five the fix must be publishable.
     */
    val ratingScale: Float = DEFAULT_RATING_SCALE,
    val selectors: PdalifeSelectors = PdalifeSelectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    /**
     * The search URL. [page] is zero-based; pdalife numbers its pages from 1 and the first
     * **has no** segment: `/search/minecraft/page-1/` answers 301 towards `/search/minecraft/`.
     */
    fun searchUrl(slug: String, page: Int): String {
        val base = "$root/$SEARCH_PATH/${slug.encodePathSegment()}/"
        if (page <= 0) return base
        return "$base$PAGE_PREFIX${page + 1}/"
    }

    /** [ref] is already `telegram-android-a14523`: see [PdalifeRefs]. */
    fun listingUrl(stem: String): String = "$root/$stem$HTML_SUFFIX"

    /**
     * The RSS feed, `/rss/`.
     *
     * A hundred entries, all Android — unlike any page on this store, which mixes iOS and PSP in
     * the same list. See `PdalifeFeedParser`.
     */
    fun recentFeedUrl(): String = "$root/$RECENT_FEED_PATH/"

    /**
     * The **first** download hop: `/dwn/fe8bc99d.html`.
     *
     * It answers 301 towards `https://mobdisc.com/dw{hash}/download.html`, which is a different
     * domain. The URL handed to the WebView is this one all the same, not that one: the user starts
     * from the store's domain and sees the jump happen in the assisted screen's address bar,
     * instead of finding themselves already elsewhere with no idea why.
     */
    fun downloadUrl(hash: String): String = "$root/$DOWNLOAD_PATH/$hash$HTML_SUFFIX"

    val resultsPerPage: Int get() = RESULTS_PER_PAGE

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            // From an Italian IP pdalife serves `<html lang="en">` and English labels, and
            // `?lang=ru` does not change it: the server picks the language. The site is largely
            // Russian in **its texts**, not in its shell: among the results for "telegram" there is
            // "Remote Bot для Telegram", and many descriptions are machine-translated.
            // `listingLanguage` is there to be able to tell the user, not to promise everything is
            // English.
            listingLanguage = "en",
            host = HOST,
        )

    /**
     * The query in the form pdalife puts in the path.
     *
     * Measured from its own redirects: `plus messenger` -> `plus-messenger`,
     * `Telegram` -> `telegram`, `  spaces  ` -> `-spaces-`. Cyrillic is **not** transliterated
     * (`/search/телеграм/` answers 200 directly), so the rule is not "ASCII only" but "everything
     * that is not a letter or a digit becomes a hyphen".
     *
     * That our normalisation is enough is verified by comparing outcomes: `tom-jerry` and
     * `tom-&-jerry` — the form the site produces by itself — give **the same 4 results**. The `+`
     * instead has to be dropped and not preserved: `/search/c%2B%2B/` answers **404**.
     */
    fun slugify(query: String): String =
        query.lowercase()
            .replace(NON_ALPHANUMERIC, SLUG_SEPARATOR)
            .trim(SLUG_SEPARATOR_CHAR)

    companion object {
        const val DISPLAY_NAME: String = "PDALIFE"
        const val HOST: String = "pdalife.com"
        const val DEFAULT_BASE_URL: String = "https://pdalife.com"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * No `Crawl-delay` in their `robots.txt`, no 429 across some sixty requests, and Cloudflare
         * in passive CDN mode — no challenge on any read.
         *
         * The cautious default stays: pdalife is a high-risk store, and one listing weighs 96 KB.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.0
        const val DEFAULT_BURST: Int = 3

        /**
         * Six hours, like the other scraped stores that publish the version name.
         *
         * Not three like an1: there the short TTL was needed because the only way to see an update
         * is to re-read the listing. Here there is `data-version_id`, strictly increasing over time
         * across the whole site, which makes the comparison exact rather than textual — and does
         * not require re-reading more often.
         */
        val DEFAULT_LISTING_TTL: Duration = 6.hours

        /** `<meta itemprop='bestRating' content='10'/>`: ten, not five. */
        const val DEFAULT_RATING_SCALE: Float = 10f

        /** Twenty per page: measured on `minecraft` (p.1 = 20, p.2 = 14, p.3 = 0). */
        private const val RESULTS_PER_PAGE: Int = 20

        private const val SEARCH_PATH = "search"
        private const val DOWNLOAD_PATH = "dwn"
        private const val PAGE_PREFIX = "page-"
        /** The feed path, declared by the site in its own `<head>`. */
        const val RECENT_FEED_PATH: String = "rss"

        private const val HTML_SUFFIX = ".html"
        private const val SLUG_SEPARATOR = "-"
        private const val SLUG_SEPARATOR_CHAR = '-'

        /**
         * Everything that is not a letter or a digit, **in Unicode**.
         *
         * `\p{L}` and not `a-z`: Cyrillic is made of letters and pdalife accepts it in the path
         * without transliterating. An ASCII class here would turn every Russian search into a row
         * of hyphens.
         */
        private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{Nd}]+""")
    }
}

/**
 * The selectors, kept apart from the code using them.
 *
 * These are the ones observed on 25/08/2026 and each is exercised by a real fixture in
 * `src/test/resources/fixtures/pdalife/`.
 *
 * ### On this store a positional selector is not fragile: it is wrong
 *
 * The cookies `advert_order_app_download_buttons` and `advert_order_app_description` already
 * declare a server-decided order for the advertising slots. The measurement confirms it in the
 * worst possible way: Telegram's listing has **five** links to `play.google.com`, and four are the
 * same advert (`cc.peacedeath.peacedeathapp`). Taking the first — or the last — means reading an
 * advert. See [detailPlayLink].
 */
@Serializable
data class PdalifeSelectors(
    // --- recent updates (RSS feed) ---
    //
    // An XML document, not HTML: with Jsoup's HTML parser `channel > item > link` reads the empty
    // string, because `<link>` in HTML is a void element. See `HtmlPage.ofXml`.
    //
    // `enclosure` are the screenshots, not the icon: pdalife attaches up to seven per entry and the
    // first is what the card shows. Calling it an icon would be incorrect — it is a frame of the
    // game — but it is the only image the feed publishes, and a card with no image next to eight
    // that have one reads as a fault.
    val feedItem: String = "channel > item",
    val feedTitle: String = "title",
    val feedLink: String = "link",
    val feedDate: String = "pubDate",
    val feedDescription: String = "description",
    val feedCategory: String = "category",
    val feedEnclosure: String = "enclosure[url]",
    // **Site text, not a selector**, and it lives here for the same reason as apkcombo's info-table
    // labels: pdalife also serves English, and changing language will have to be a `parsers.json`
    // update, not a release.
    val feedTitleVerb: String = "скачать",

    // --- search ---
    //
    // Three conditions in a single selector. **Only one bears the weight**, and the injection says
    // so: removing them one at a time and rerunning the suite, two of three stay green.
    //
    // 1. `ul.catalog-list >` — the container. **Removing it fails nothing**, and that has to be
    //    said rather than left implied: the "Top best" sidebar publishes ten links to listings with
    //    `a.color-android` — even on the page with no results — but puts them in
    //    `li.side-top__item`, which is not `li.catalog-item`. It stays because it costs nothing and
    //    because it tells the reader *where* we are looking; not because it defends anything today.
    // 2. `li.catalog-item.js-list-item` — the second class. This one too, on its own, is not
    //    provable: the "Oops, maybe try another request?" row of the empty page has the first class
    //    and not the second, but point 3 already discards it, because it contains no title. It
    //    stays as a net below point 3, which is the only one that really holds.
    // 3. `:has(p.catalog-item__title a.color-android)` — **this is the defence**. pdalife publishes
    //    iOS and PSP in the same list with the same markup. The filter is in the selector and not
    //    in the parser's body because discarding the rows **after** taking them turns a search for
    //    iOS-only apps into a `ParseFailure` — "the store broke" instead of "there is nothing for
    //    Android". This is not a textbook case: `/search/procreate/` returns twenty results and
    //    none is Android, and it is the `search-other-os.html.gz` fixture.
    val searchItem: String =
        "ul.catalog-list > li.catalog-item.js-list-item:has(p.catalog-item__title a.color-android)",
    val searchLink: String = "p.catalog-item__title a[href]",
    val searchIcon: String = ".catalog-item__poster img[src]",
    val searchDescription: String = "p.catalog-item__description",
    val searchVersion: String = "p.catalog-item__version",
    val searchRating: String = ".catalog-item__rating .rating-circle",
    val searchCategory: String = ".catalog-item__genre-button",
    /**
     * The page counter, which pdalife publishes rather than making it be guessed.
     *
     * `<div class="catalog__more-button ..." data-max_page="2" data-current_page="1">`. It is `-1`
     * on the page with no results. It beats the criterion "the page is full, so perhaps there is
     * another": that costs an empty request whenever the total is a multiple of twenty, and on a
     * search filtered by OS it would not even be true.
     */
    val searchPager: String = ".catalog__more-button",

    // --- detail ---
    val detailTitle: String = "h1[itemprop=name]",
    val detailIcon: String = "img.game__poster-picture[src]",
    val detailCategory: String = "[itemprop=applicationCategory]",
    val detailDeveloper: String = "[itemprop=author] meta[itemprop=name]",
    /**
     * The description, anchored **to the containing `div`** and not to the microdata alone.
     *
     * `itemprop='description'` appears twice: once on a `<meta>` in the head, carrying the
     * **truncated** description in a `content` attribute and no text, and once on the real `div`.
     * The generic selector takes the first, and `textOrNull` of a `<meta>` is `null`: the listing
     * would have come out **with no description**, silently. Found by `readsApp`, not by eye.
     */
    val detailDescription: String = "div.game__description[itemprop=description]",
    val detailRatingValue: String = "[itemprop=aggregateRating] meta[itemprop=ratingValue]",
    val detailRatingCount: String = "[itemprop=aggregateRating] meta[itemprop=ratingCount]",
    val detailRatingBest: String = "[itemprop=aggregateRating] meta[itemprop=bestRating]",
    val detailScreenshot: String = ".game-gallery a[href]",
    /**
     * The Google Play link **inside the offers container**, which is the only real one.
     *
     * `<div class="game-download__stores" itemscope itemprop='offers'>`. Across 17 sampled
     * listings, reading the page's first `play.google.com` returns `cc.peacedeath.peacedeathapp` —
     * an advert — **17 times out of 17**. With the container it yields 12 real packages and 5
     * absences (apps that are not on Play), which is the truth.
     *
     * The container, and not the `itemprop='offers'` attribute alone, because it is the class their
     * template uses; the microdata next to it confirms it.
     */
    val detailPlayLink: String = ".game-download__stores a[href*=play.google.com]",
    /**
     * The requirements, which are a list of `li` with the label **translated by the server**.
     *
     * `OS version: Android 2.2+`, `Internet: required`, `Requires free space: 30 Mb`. The first
     * `ul.game-download__list` always looks like the right one and is not: next to it sits "Help",
     * with the same markup, and positional selectors are forbidden on this site. All the `li`s are
     * taken and the one **containing an Android version number** is kept, which is the only part
     * of the row translation does not touch.
     */
    val detailRequirement: String = "ul.game-download__list li",
    /**
     * The catalogue division, read from the breadcrumbs.
     *
     * `/android/games/` or `/android/programmy/`. The label next to it is translated ("Programs on
     * Android"), the href is not.
     */
    val detailBreadcrumb: String = ".breadcrumbs__list a[href]",

    // --- versions ---
    //
    // Each version is a `div.accordion-item` inside `.game-versions`, and the three things needed
    // sit in three different places of the same block. They are 4 out of 4 inside `.game-versions`:
    // no accordion anywhere else on the page.
    val versionItem: String = ".game-versions .accordion-item",
    val versionTitle: String = "p.accordion-title",
    val versionChanges: String = ".js-changes-wrapper",
    /**
     * The file row, which carries `data-version_id` — the monotonic discriminator.
     *
     * **What excludes the advert is the `ul`, not the attribute**, and the injection says so:
     * removing `[data-version_id]` from the selector leaves the suite green. The banner
     * `<div class="js-banner" data-type="app_download_buttons">` — an advert that calls itself
     * "download buttons" in its own attributes — sits **after** the close of
     * `ul.game-versions__downloads-list`, so an `li` inside that `ul` never reaches it.
     *
     * The attribute stays in the selector because it is the value **read** right afterwards, to
     * order the versions: asking for a row that lacks it would mean discarding it in the parser's
     * body instead of in the selector. It is not a defence against the banner; it is the shape of
     * the row we need.
     */
    val versionFile: String = "ul.game-versions__downloads-list li[data-version_id]",
    val versionFileLink: String = "a.game-versions__downloads-button[href]",
    val versionFileSize: String = ".game-versions__downloads-size",
) {
    companion object {
        /** The compiled value of [PdalifeSelectors.searchItem], for error messages. */
        const val SEARCH_ITEM_DEFAULT: String =
            "ul.catalog-list > li.catalog-item.js-list-item:has(p.catalog-item__title a.color-android)"
    }
}

/**
 * The path segment, percent-encoded but **with the hyphens intact**.
 *
 * `URLEncoder` is for queries and would turn the space into `+`, which here means 404. The slug
 * arriving here already contains only letters, digits and hyphens: what is left to encode are the
 * non-ASCII characters, i.e. Cyrillic.
 */
private fun String.encodePathSegment(): String =
    java.net.URI(null, null, this, null).rawPath
