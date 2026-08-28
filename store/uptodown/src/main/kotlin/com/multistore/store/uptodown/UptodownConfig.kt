package com.multistore.store.uptodown

import com.multistore.store.api.StoreMetadata
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable

/**
 * The uptodown adapter's compiled defaults.
 *
 * Every value is **measured**, on 24/08/2026, from an Italian consumer IP.
 *
 * ### Three findings that contradict the obvious guesses
 *
 * 1. **The search exists and is called `search`.** `/android/search` returning `410 Gone` concerns
 *    the **path** form (`/android/search/telegram`); the query form answers 200 under both names.
 *    On `en.uptodown.com` both `search?query=` and `buscar?query=` work, and the first is used
 *    because it is what the page's own search module declares in the form's `action`.
 * 2. **There is a language subdomain, and it changes the listings' language.** `www.uptodown.com`
 *    serves **Spanish** (`<html lang="es">`, "Descargar telegram"); `en.uptodown.com` serves
 *    English, and the listings become `{slug}.en.uptodown.com`. Using `www` would fill the
 *    database with Spanish descriptions for everyone.
 * 3. **Pagination does not exist.** `?page=2` returns **the same 36 apps** as the first page, in a
 *    different order — compared on the set of hrefs, not by eye. The order changes on every
 *    request: it is randomised server-side among equally scored results.
 *
 * ### Why the download stays user-assisted
 *
 * The button is not a link: it is a `<button>` running a Cloudflare Turnstile
 * (`appearance: "interaction-only"`, `execution: "execute"`) and then posting the token to
 * `POST /ajax/app/{appID}/file/{fileID}/download-url`. Calling that endpoint without the token —
 * or with a token we did not obtain by really running the challenge — would be **pretending** to
 * have solved it, which is the line this project does not cross. Hence
 * `DownloadMode.USER_ASSISTED_ONLY`: the real page, the real tap.
 *
 * Worth recording *what* makes this store different from the other assisted ones:
 * `interaction-only` means the widget stays invisible until Cloudflare really asks for a gesture.
 * A `WebViewSilentResolver` that **executes** the challenge JS would obtain the token by itself in
 * most cases, and the tap would remain only for the times Turnstile escalates to interactive. That
 * is real execution, not simulation: it sits on the permitted side of the line.
 */
@Serializable
data class UptodownConfig(
    /** The language root: search, categories, health probe. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * How a listing URL is built, with `{slug}` in place of the name.
     *
     * It is a template and not a concatenation because on uptodown the slug is a **subdomain**,
     * not a path segment: `https://telegram.en.uptodown.com/android`. A single field keeps the two
     * halves — host and path — together, which no other part of the project has reason to know
     * separately.
     */
    val appUrlTemplate: String = DEFAULT_APP_URL_TEMPLATE,
    /**
     * The suffix by which a listing is **recognised** in a page link.
     *
     * Deliberately separate from [appUrlTemplate]: the two directions are not the same thing. The
     * fixtures contain uptodown's real URLs even when the tests point the adapter at a local
     * server, and rightly so — they are the bytes the server sent. The template is for
     * **building**, the suffix for **reading**, and only the first changes in tests.
     */
    val appHostSuffix: String = DEFAULT_APP_HOST_SUFFIX,
    /**
     * A browser User-Agent.
     *
     * uptodown does not require one for its pages, but the field is mandatory in the contract and
     * the fixtures were captured with this value.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    val listingTtl: Duration = DEFAULT_LISTING_TTL,
    val selectors: UptodownSelectors = UptodownSelectors(),
) {
    private val root: String get() = baseUrl.trimEnd('/')

    fun searchUrl(query: String): String =
        "$root/$PLATFORM/$SEARCH_SEGMENT?$QUERY_PARAM=" + query.encodeQueryValue()

    /** The listing: [slug] is the ref, i.e. the subdomain. */
    fun appUrl(slug: String): String = appUrlTemplate.replace(SLUG_PLACEHOLDER, slug)

    fun versionsUrl(slug: String): String = "${appUrl(slug)}/$VERSIONS_SEGMENT"

    /**
     * The downloads chart, `/android/top`.
     *
     * Of the nine popularity surfaces probed on 25/08/2026 it is the only one declaring each
     * entry's **rank**, and it does so in the most awkward way possible: inside the title,
     * `<h2>1. Uptodown App Store</h2>`. See `UptodownTopParser`.
     */
    fun topUrl(): String = "$root/$PLATFORM/$TOP_SEGMENT"

    /**
     * Recently updated apps, `/android/latest-updates`.
     *
     * It was in none of the initial probes: it is written **on the chart page**, among the
     * `Latest Updates` / `New Releases` / `Top downloads` tabs. The address one might guess —
     * `/android/new` — answers 404.
     *
     * The container is `#content-list`, i.e. **the same one as search**, with the same row markup:
     * the parser is that one, not a second.
     */
    fun recentUrl(): String = "$root/$PLATFORM/$RECENT_SEGMENT"

    /** The download page: the current one, or a specific version's. */
    fun downloadUrl(slug: String, versionId: String?): String =
        appUrl(slug) + "/" + DOWNLOAD_SEGMENT + (versionId?.let { "/$it" }.orEmpty())

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            listingLanguage = "en",
            host = HOST,
        )

    companion object {
        const val DISPLAY_NAME: String = "Uptodown"
        const val HOST: String = "uptodown.com"
        const val DEFAULT_BASE_URL: String = "https://en.uptodown.com"
        const val DEFAULT_APP_URL_TEMPLATE: String = "https://{slug}.en.uptodown.com/android"
        const val DEFAULT_APP_HOST_SUFFIX: String = ".en.uptodown.com"

        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * No `Crawl-delay` in their `robots.txt` and no 429 observed.
         *
         * The value stays cautious because uptodown's ToS (§1.4) forbid automated access: every
         * request we make is born of a user gesture, and staying below a browser's pace is what
         * keeps that sentence true.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 1.0
        const val DEFAULT_BURST: Int = 3

        val DEFAULT_LISTING_TTL: Duration = 6.hours

        const val PLATFORM: String = "android"
        const val SEARCH_SEGMENT: String = "search"
        const val TOP_SEGMENT: String = "top"
        const val RECENT_SEGMENT: String = "latest-updates"
        const val VERSIONS_SEGMENT: String = "versions"
        const val DOWNLOAD_SEGMENT: String = "download"
        const val QUERY_PARAM: String = "query"
        const val SLUG_PLACEHOLDER: String = "{slug}"
    }
}

/**
 * The selectors, kept apart from the code using them.
 *
 * These are the ones observed on 24/08/2026 and each is exercised by a real fixture in
 * `src/test/resources/fixtures/uptodown/`.
 */
@Serializable
data class UptodownSelectors(
    // --- search ---
    //
    // **`#content-list` is not a flourish: it is the difference between "no results" and a lie.**
    // On a query without results uptodown does not emit that container at all, and in its place
    // puts `<section class="notice">Oops, we couldn't find any matching programs</section>`
    // followed by "Apps you're gonna love" with **twelve `.item`s of identical markup** — Telegram
    // among them. A bare `.item` selector would answer with twelve apps to a search that found
    // nothing.
    val searchItem: String = "#content-list .item",
    val searchLink: String = ".name a[href]",
    /**
     * The **link**'s text, not the `<h2>` usually inside it.
     *
     * It looks like the less precise choice and is instead the only defensible one. The "Apps
     * you're gonna love" cards uptodown shows in place of results have no `<h2>`: the title is bare
     * text inside the anchor. Reading `.name h2` would therefore discard them **by itself**, and
     * the parser would look correct even with `#content-list` removed from the container selector
     * — verified, the suite stayed green. Correctness would then depend on an accidental detail of
     * markup belonging to a section that is none of our business, and the day uptodown unified the
     * two card shapes the app would start answering "Telegram" to searches that found nothing,
     * silently.
     *
     * With this selector the suggested cards would be perfectly readable, and **only** the
     * container keeps them out. Which is the right thing, and also the only one a test can prove.
     */
    val searchTitle: String = ".name a",
    val searchIcon: String = "figure img[src]",

    // --- download chart (`/android/top`) ---
    //
    // The container is `#list-top-items` and **not** `#content-list`: the chart and the search have
    // the same row and two different containers. Anchoring on `.item` alone would also pick up the
    // "Apps you're gonna love" cards, which on this site carry the results' markup — the same trap
    // described in `UptodownSearchParser`.
    val topItem: String = "#list-top-items .item",
    // The top chart writes a longer description in `.description-max`; search uses `.description`.
    // The rest of the row — link, title, icon — is identical and reuses the search selectors
    // instead of duplicating them.
    val topDescription: String = ".description-max",
    val searchAuthor: String = ".author",
    val searchDescription: String = ".description",

    // --- listing ---
    val detailTitle: String = "#detail-app-name",
    val detailVersion: String = "#detail-app-name + .version",
    val detailAuthor: String = "#author-link",
    val detailIcon: String = ".detail > .icon img",
    val detailSummary: String = ".detail > h2",
    val detailRating: String = "#rating-inner-text",
    val detailRatingCount: String = "#show-comments_app span",
    val detailDescription: String = ".text-description",
    val detailScreenshot: String = ".gallery img.screenshot",
    /** Carries the **current** version's `data-file-id`, which the version list does not. */
    val detailCurrentFile: String = "#security-report[data-file-id]",

    // --- info tables, identical on the listing and the download page ---
    val infoRow: String = ".info-block table.content tr",
    val infoName: String = "th",
    /**
     * **The last** cell, not the first.
     *
     * An uptodown row is `<tr><td><img icon></td><th>Package Name</th><td>value</td></tr>`: the
     * first `<td>` holds only the row's decorative icon. With a bare `td` selector the value read
     * is an empty string, the row is discarded as "valueless", and the listing comes out **with no
     * `packageName` and no SHA-256** — that is, without the two fields that make this store
     * verifiable. No selector fails and no error appears: the defect is silent, which is exactly
     * what `HtmlPage` exists to prevent.
     */
    val infoValue: String = "td:last-child",

    // --- version list, present both at the foot of the listing and on `/versions` ---
    val versionItem: String = "#versions-items-list > div[data-version-id]",
    val versionName: String = ".version",
    val versionType: String = ".type",
    val versionSdk: String = ".sdkVersion",
    val versionDate: String = ".date",

    // --- download page ---
    val downloadButton: String = "#detail-download-button",
    val downloadTurnstile: String = "#download-turnstile-widget[data-sitekey]",

    // --- table labels ---
    //
    // These are **site text**: uptodown publishes the same listing in some twenty languages, and
    // serving another one some day will have to be a `parsers.json` update.
    val infoRowPackageName: String = "Package Name",
    val infoRowSha256: String = "SHA256",
    val infoRowSize: String = "Size",
    val infoRowDate: String = "Date",
    val infoRowFileType: String = "File type",
    val infoRowDownloads: String = "Downloads",
    val infoRowArchitecture: String = "Architecture",
    val infoRowCategory: String = "Category",
    val infoRowLicense: String = "License",
)

private fun String.encodeQueryValue(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8)
