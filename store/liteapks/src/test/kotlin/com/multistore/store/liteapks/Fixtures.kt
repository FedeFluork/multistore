package com.multistore.store.liteapks

import java.util.zip.GZIPInputStream

/**
 * Real liteapks pages, captured and committed compressed.
 *
 * They are the server's bytes, untouched: each one's provenance is in the `README.md` next to the
 * files. To read one: `gzcat detail.html.gz | less`.
 *
 * **Captured with OkHttp and [LiteapksConfig.DEFAULT_USER_AGENT]**, and on this store that is not a
 * ritual note: `curl` with the same UA receives `403 cf-mitigated: challenge` on the same pages, and
 * so does OkHttp with `curl`'s UA. A fixture taken with the wrong client here would not be a poorer
 * fixture: it would be a block page.
 *
 * They are not trimmed. The strongest reason on this store is the Google Play link: **31 listings out
 * of 31** carry one that is an advert, and on 26 there is also a real one, before it. A parser tested
 * on a fragment chosen by whoever wrote it never meets the trap — and the trap here works in 84% of
 * cases, which is the worst way a defect can present itself.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/liteapks"

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

    // --- Search ---------------------------------------------------------------------------------

    /** `?s=telegram`: seven results, a single page, a declared total that is **true**. */
    const val SEARCH: String = "search.html.gz"

    /**
     * `?s=game`, first page: eighteen results and a declared total that **saturates**.
     *
     * Together with [SEARCH_PAGE_2] and [SEARCH_LAST_PAGE] it is the proof that the plan was wrong on
     * two counts: the search does paginate, and it is not capped at nine results.
     */
    const val SEARCH_PAGE_1: String = "search-page1.html.gz"

    /** `?s=game&paged=2`: another eighteen, **different** from the first. */
    const val SEARCH_PAGE_2: String = "search-page2.html.gz"

    /** `?s=game&paged=4`: six, that is the last partial page. Beyond it the site answers 404. */
    const val SEARCH_LAST_PAGE: String = "search-last-page.html.gz"

    /**
     * A search with no results, which **is not an empty container**.
     *
     * `div#apps-grid` does not exist, and there is not a single `<article>` on the whole page. Seen by
     * the parser it is indistinguishable from a page whose row selector has died: what tells them
     * apart is `h1#search-title`, which is present here — without the count in brackets.
     */
    const val SEARCH_EMPTY: String = "search-empty.html.gz"

    // --- Listings ----------------------------------------------------------------------------

    /**
     * A **game**: real `packageName`, six screenshots, no "MOD Info" block.
     *
     * It is the contract test's reference listing because it is the only one of the two that satisfies
     * `providesScreenshots = true`. That is not a convenience: across 31 sampled listings the
     * screenshots are present on 20, and it is nearly always the games that have them.
     */
    const val GAME: String = "detail-game.html.gz"

    /**
     * An **app**: no `packageName`, no screenshots, with the "MOD Info" block.
     *
     * It is the fixture that makes the Google Play link defence testable. The page **does** have a
     * `play.google.com` — the `io.apkmody.sai` advert — and does not have the real one: reading "the
     * page's first link" would give another app's package here. That is 5 cases out of 31.
     */
    const val APP: String = "detail.html.gz"

    /**
     * A listing whose Play link carries **extra parameters**: `?id=org.telegram.plus&hl=en&gl=US`.
     *
     * That is 5 listings out of 31, and they are the reason the package is read as a query parameter
     * instead of by cutting the string after `id=`. A naive cut would give
     * `org.telegram.plus&hl=en&gl=US`, which matches no APK: step 4 of the pre-install pipeline would
     * block the installation, at the very last metre.
     */
    const val PLAY_PARAMS: String = "detail-play-params.html.gz"

    /** The site's real 404: 39 KB of complete page, menu and footer included. */
    const val NOT_FOUND: String = "not-found.html.gz"

    // --- File pages --------------------------------------------------------------------------

    /**
     * Six files in three groups, with the version **on the row**: `v1.26.10.4 Final - Mod 1`.
     *
     * The group headings here carry names rather than versions (`Minecraft - Official Versions`):
     * reading them first would give six files the same label.
     */
    const val DOWNLOAD_GAME: String = "download-game.html.gz"

    /**
     * Three files in two groups, with the version **on the heading**: `v12.10.1 - Mod`.
     *
     * It is the opposite case to [DOWNLOAD_GAME], and together they are the reason the parser looks in
     * both places: 22 rows out of 66 carry it on the row, 44 do not.
     */
    const val DOWNLOAD_APP: String = "download.html.gz"

    /**
     * A single file, and **without** `div#dl-versions`.
     *
     * The page has two markup shapes depending on whether there is one version or several: 17 pages out
     * of 31 have the card buttons, 14 do not. Anchoring the selector to the container would lose those
     * fourteen silently.
     */
    const val DOWNLOAD_SINGLE: String = "download-single.html.gz"

    /**
     * Four files, **two of them in the "Original file on Google Play" block**.
     *
     * The originals are the unmodified APK, on a different CDN and with no intermediate page. One
     * of the two is an `.xapk`, and one of the two is **dead** (`gp3.liteapks.com` answers 404):
     * they are the two reasons the adapter declares `supportsSplits` and implements `preflight`.
     */
    const val DOWNLOAD_ORIGINAL: String = "download-original.html.gz"

    /**
     * A file page whose `data-link` has **nothing to normalise**.
     *
     * `https://down.appsupload.com/Minecraft/minecraft-v1.26.10.4-final-mod1.apk`: no spaces, no
     * escapes. It is the normal path's fixture, and it is committed also to say what it does
     * **not** prove: the injection removing the condition from the normalisation leaves it green,
     * because on a URL like this encoding twice changes nothing.
     */
    const val SLOT: String = "download-slot.html.gz"

    /**
     * A file page whose `data-link` is **already percent-encoded**, `%2B` included.
     *
     * `…/Game%20Booster%204x%20Faster/Booster%2B%20v1.2.6%20%28Paid%29.apk`. It is the case that
     * makes the condition provable: re-encoding would turn `%2B` into `%252B` and `%20` into
     * `%2520`, and the worker would answer **404 `NoSuchKey`** — the file exists, the key does not.
     *
     * Added **after** the injection, not before: the first fixture chosen for this case contained
     * no escapes, so the test passed even with the condition removed. It was a caption, not a test.
     */
    const val SLOT_ENCODED: String = "download-slot-encoded.html.gz"

    /**
     * A file page whose `data-link` decodes to a URL **with raw spaces**.
     *
     * `https://download.liteapks.dev/Telegram/Telegram v12.10.1 (PREMIUM) Web.apk`. The same store
     * serves both forms, and that is why the normalisation has to be conditional: encoding twice
     * produces `%2520` and the worker answers 404 `NoSuchKey`.
     */
    const val SLOT_RAW_SPACES: String = "download-slot-raw-spaces.html.gz"

    // --- The game ------------------------------------------------------------------------------

    const val GAME_REF: String = "minecraft"
    const val GAME_TITLE: String = "Minecraft"
    const val GAME_DEVELOPER: String = "Mojang"
    const val GAME_PACKAGE: String = "com.mojang.minecraftpe"
    const val GAME_CATEGORY: String = "Arcade"
    const val GAME_VERSION: String = "1.26.50.26"
    const val GAME_RATING: Float = 3.9f
    const val GAME_RATING_COUNT: Int = 1884
    const val GAME_SCREENSHOTS: Int = 6
    const val GAME_STEM: String = "minecraft-11909"
    const val GAME_FILES: Int = 6

    /** The **first** file's version, which on Minecraft's row is written out in full. */
    const val GAME_FIRST_VERSION: String = "1.26.10.4 Final - Mod 1"

    /** What the first slot's `data-link` decodes to: already percent-encoded at source. */
    const val GAME_FILE_URL: String =
        "https://down.appsupload.com/Minecraft/minecraft-v1.26.10.4-final-mod1.apk"

    // --- The app -------------------------------------------------------------------------------

    const val APP_REF: String = "telegram"
    const val APP_TITLE: String = "Telegram"
    const val APP_DEVELOPER: String = "Telegram FZ-LLC"
    const val APP_CATEGORY: String = "Communication"
    const val APP_VERSION: String = "12.10.1"
    const val APP_MOD_TRAITS: String = "MOD: Premium, Lite, No ADS"
    const val APP_STEM: String = "telegram-810"
    const val APP_FILES: Int = 3

    /** Version from the group heading, variant from the row. See [DOWNLOAD_APP]. */
    const val APP_FIRST_VERSION: String = "12.10.1 - Mod (Premium/Web)"

    /** What the already-encoded `data-link` carries, and which must stay identical. */
    const val ENCODED_FILE_URL: String =
        "https://download-old.liteapks.dev/Game%20Booster%204x%20Faster/Booster%2B%20v1.2.6%20%28Paid%29.apk"

    /** With raw spaces, as the `data-link` writes it. */
    const val APP_FILE_URL_RAW: String =
        "https://download.liteapks.dev/Telegram/Telegram v12.10.1 (PREMIUM) Web.apk"

    /** The same, in the form the worker accepts. */
    const val APP_FILE_URL: String =
        "https://download.liteapks.dev/Telegram/Telegram%20v12.10.1%20(PREMIUM)%20Web.apk"

    /**
     * The advert that sits on **every** listing and that a naive read returns.
     *
     * Across 31 sampled listings the "XAPKS Installer" advert is present 31 times; on 5 it is the
     * **only** `play.google.com` on the page, and on those, taking the page's first link returns
     * this package instead of none. The value lives here because the tests use it **positively**:
     * it is not enough to check the right one comes out, it must be shown that the fallback really
     * produces it.
     */
    const val PLAY_ADVERT_PACKAGE: String = "io.apkmody.sai"

    // --- Search: the numbers --------------------------------------------------------------------

    /** Seven results and a declared total that matches. */
    const val QUERY_WITH_RESULTS: String = "telegram"
    const val QUERY_RESULTS: Int = 7

    /** Eighteen per page, four pages, and a total that saturates at sixty. */
    const val QUERY_PAGED: String = "game"
    const val PAGE_ROWS: Int = 18
    const val LAST_PAGE_ROWS: Int = 6
    const val DECLARED_CAP: Int = 60

    /** A query that genuinely finds nothing on liteapks. */
    const val QUERY_WITHOUT_RESULTS: String = "zzqxwvnbtklmj"
}
