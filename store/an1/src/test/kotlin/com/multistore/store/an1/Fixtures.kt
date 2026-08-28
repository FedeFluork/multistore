package com.multistore.store.an1

import java.util.zip.GZIPInputStream

/**
 * an1's real pages, captured on 25/08/2026 and committed compressed.
 *
 * They are the server's bytes, untouched: each one's provenance is in the `README.md` next to the
 * files. To read one: `gzcat detail.html.gz | less`.
 *
 * They are not trimmed, and on this store the reason is concrete: the search page contains ten
 * results **and** a related-apps bar, the detail contains two links to an `.apk` that is not the
 * app's, and the download page contains a third. A parser tested on a fragment chosen by whoever
 * wrote it meets none of the three.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/an1"

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
    const val SEARCH_PAGE_2: String = "search-page2.html.gz"
    const val SEARCH_EMPTY: String = "search-empty.html.gz"
    const val DETAIL: String = "detail.html.gz"
    const val DETAIL_GAME: String = "detail-game.html.gz"
    const val DOWNLOAD: String = "download.html.gz"

    /**
     * The download page of a **large** app, whose file sits on the **second** host.
     *
     * This is the failure's fixture. With a single host declared, one app answered "The store
     * answered in an unexpected format" on the device. Of twelve sampled listings two are like
     * this, and both are the large ones.
     */
    const val DOWNLOAD_SECOND_HOST: String = "download-second-host.html.gz"

    /**
     * The download page of an app whose file an1 **no longer hosts**.
     *
     * The anchor leads to a link shortener, which redirects to Google Drive. Two listings out of
     * twelve are like this. It is not followed: see the note in `An1DownloadParser`.
     */
    const val DOWNLOAD_OFFSITE: String = "download-offsite.html.gz"
    const val NOT_FOUND: String = "not-found.html.gz"

    /** The listing the fixtures are taken from: a **program**. */
    const val APP_REF: String = "2971-telegram"
    const val APP_ID: String = "2971"
    const val APP_TITLE: String = "Telegram"
    const val APP_DEVELOPER: String = "Telegram FZ-LLC"
    const val APP_VERSION: String = "12.4.3"
    const val APP_FILE: String = "telegram_12.4.3-an1.com.apk"

    /** The second listing: a **game**, to prove the category tells the two apart. */
    const val GAME_REF: String = "7112-blockman-go"
    const val GAME_ID: String = "7112"
    const val GAME_TITLE: String = "Blockman Go"
    const val GAME_FILE: String = "blockman-go_3.26.1-an1.com.apk"

    /** The listing whose file ended up outside an1's hosts. */
    const val OFFSITE_REF: String = "3854-toolbox-for-minecraft-pe"
    const val OFFSITE_ID: String = "3854"

    /**
     * The SHA-256 the file host publishes for [APP_FILE].
     *
     * **Verified against the real bytes**, not taken on trust: 83,757,788 bytes downloaded and
     * recomputed. It is an uploader-defined metadata header — the ETag next to it is multipart and
     * **not** the content's MD5, so it would not have been usable.
     */
    const val APP_SHA256: String = "c62171f089a1eef035642eb7d92388f451307bef9d345e2d70766ee72ea20a3d"
    const val APP_SIZE_BYTES: Long = 83_757_788L

    /** Ten results on the first page, four on the second, no overlap. */
    const val QUERY_WITH_RESULTS: String = "minecraft"
    const val PAGE_1_RESULTS: Int = 10
    const val PAGE_2_RESULTS: Int = 4

    /**
     * A query that genuinely finds nothing on an1.
     *
     * Unlike apkmody — where a non-Latin alphabet is needed because the search is fuzzy — here an
     * absurd string is enough: an1 searches by substring and this one produces **zero** rows, with
     * no suggested cards in their place.
     */
    const val QUERY_WITHOUT_RESULTS: String = "qzxvnpwmkljhgfd"
}
