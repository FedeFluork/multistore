package com.multistore.store.pdalife

import java.util.zip.GZIPInputStream

/**
 * pdalife's real pages, captured on 25/08/2026 and committed compressed.
 *
 * They are the server's bytes, untouched: the provenance of each is in the `README.md` next to the
 * files. To read one: `gzcat detail.html.gz | less`.
 *
 * They are not trimmed, and on this store the reason is the strongest of all those collected so
 * far: Telegram's listing contains **five** links to `play.google.com` of which four are the same
 * advert, the search page contains ten sidebar links with the same `a.color-android` as the results
 * **even when the results are zero**, and the download page contains three identical buttons of
 * which one is a real `.apk` of another app. A parser proven on a fragment chosen by whoever wrote
 * it meets none of the three traps.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/pdalife"

    fun bytes(name: String): ByteArray {
        val stream = requireNotNull(Fixtures::class.java.classLoader.getResourceAsStream("$DIRECTORY/$name")) {
            "Missing fixture: $DIRECTORY/$name"
        }
        return stream.use { raw ->
            if (name.endsWith(GZIP_SUFFIX)) GZIPInputStream(raw).readBytes() else raw.readBytes()
        }
    }

    fun html(name: String): String = bytes(name).toString(Charsets.UTF_8)

    private const val GZIP_SUFFIX = ".gz"

    const val SEARCH: String = "search.html.gz"

    /** `/rss/`, 25/08/2026: 100 `<item>`, all Android, five dated in the future. */
    const val RECENT_FEED: String = "recent-feed.xml.gz"
    const val SEARCH_PAGE_2: String = "search-page2.html.gz"
    const val SEARCH_EMPTY: String = "search-empty.html.gz"

    /**
     * Twenty results and **none** Android: `/search/procreate/` is a catalogue of iPad brushes.
     *
     * It is the fixture that makes the operating-system filter a provable defence rather than a
     * declaration. Without the `:has(a.color-android)` in the selector, `mapRowsOrFail` would find
     * twenty rows, would be unable to read any of them — the ref requires `-android-` — and would
     * answer `ParseFailure`: that is, "the store broke" instead of "there is nothing for Android".
     * With the filter the rows are not even selected, and the outcome is zero results.
     */
    const val SEARCH_OTHER_OS: String = "search-other-os.html.gz"

    /**
     * A single result, Android, with a **zero rating**.
     *
     * Zero is the absence of votes: the scale the listing declares starts at one
     * (`worstRating = 1`), and the rating block is on every row even when nobody has voted. The
     * other search fixtures contain not a single zero row — the minimum is 1 — so without this page
     * the distinction between "unrated" and "judged terrible" would stay a sentence in the code.
     */
    const val SEARCH_UNRATED: String = "search-unrated.html.gz"

    /** The listing the values are taken from: a **program**, with the Google Play link. */
    const val DETAIL: String = "detail.html.gz"

    /** A **game**, and a modified build at that: `v6.3.5   Money Mod`. */
    const val DETAIL_MOD: String = "detail-mod.html.gz"

    /**
     * A listing **without** the offers container, i.e. without a `packageName`.
     *
     * They are 5 out of 17 in the sample: apps that are not on Google Play. It is the fixture that
     * stops the parser falling back on the page's first `play.google.com` — which here is, as on
     * all the others, `cc.peacedeath.peacedeathapp`.
     */
    const val DETAIL_NO_PACKAGE: String = "detail-no-package.html.gz"

    /**
     * The download's **second** hop, on `mobdisc.com`: reCAPTCHA v3 and three buttons.
     *
     * The adapter does not read it — it is the page handed to the WebView — and it is committed for
     * exactly that reason: it is the evidence of what is on the other side, and what
     * `DownloadMode.USER_ASSISTED_ONLY` rests on.
     */
    const val DOWNLOAD: String = "download.html.gz"

    /** The same hop for the modified build, where the third button is a real `.apk`. */
    const val DOWNLOAD_MOD: String = "download-mod.html.gz"

    const val NOT_FOUND: String = "not-found.html.gz"

    // --- The program's listing ---------------------------------------------------------------

    const val APP_REF: String = "telegram-android-a14523"
    const val APP_ID: String = "14523"
    const val APP_TITLE: String = "Telegram"
    const val APP_DEVELOPER: String = "Telegram FZ-LLC"
    const val APP_PACKAGE: String = "org.telegram.messenger"
    const val APP_CATEGORY: String = "Internet and network"
    const val APP_VERSION: String = "9.7.3 Original"
    const val APP_VERSIONS: Int = 4
    const val APP_SCREENSHOTS: Int = 7
    const val APP_DOWNLOAD_HASH: String = "fe8bc99d"

    /** The **second** version's octet, to prove the ref chooses the file. */
    const val APP_OLD_DOWNLOAD_HASH: String = "33n84e18"

    /** `<meta itemprop='ratingValue' content='9.2292'/>` with `bestRating` at 10. */
    const val APP_RATING_OUT_OF_TEN: Float = 9.2292f
    const val APP_RATING_COUNT: Int = 96

    // --- The modified game's listing -----------------------------------------------------------

    const val MOD_REF: String = "real-gangster-crime-android-a32255"
    const val MOD_TITLE: String = "Real Gangster Crime"
    const val MOD_PACKAGE: String = "com.gta.real.gangster.crime"
    const val MOD_VERSION: String = "6.3.5 Money Mod"
    const val MOD_DOWNLOAD_HASH: String = "6d2d7bca"

    // --- The listing with no package ---------------------------------------------------------

    const val NO_PACKAGE_REF: String = "unleashed-pixel-dungeon-android-a27009"
    const val NO_PACKAGE_TITLE: String = "Unleashed Pixel Dungeon"

    /**
     * The advert that sits on **every** listing and that a naive read returns.
     *
     * Across 17 sampled listings, taking the page's first `play.google.com` gives this package 17
     * times out of 17. The value lives here because the tests use it **positively**: it is not
     * enough to check the right one comes out, it must be shown that the fallback really produces
     * the wrong one.
     */
    const val PLAY_ADVERT_PACKAGE: String = "cc.peacedeath.peacedeathapp"

    // --- Search ------------------------------------------------------------------------------

    /** Twenty rows on the first page, fourteen on the second, zero on the third. */
    const val QUERY_WITH_RESULTS: String = "minecraft"
    const val PAGE_1_ROWS: Int = 20
    const val PAGE_1_ANDROID: Int = 18
    const val PAGE_2_ROWS: Int = 14
    const val PAGE_2_ANDROID: Int = 12

    /**
     * A query that genuinely finds nothing on pdalife.
     *
     * It holds for the **page** `/search/`, not for `/suggest/`: that endpoint answers ten random
     * apps even to this string. See the note at the head of `PdalifeConfig`.
     */
    const val QUERY_WITHOUT_RESULTS: String = "zzqxwvnbtklmj"

    /** Twenty results, all iOS. See [SEARCH_OTHER_OS]. */
    const val QUERY_OTHER_OS: String = "procreate"
    const val OTHER_OS_ROWS: Int = 20

    /** One result, Android, with no rating. See [SEARCH_UNRATED]. */
    const val QUERY_UNRATED: String = "turbogram"
    const val UNRATED_REF: String = "turbogrampro-advanced-telegram-android-a27714"
}
