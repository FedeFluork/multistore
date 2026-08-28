package com.multistore.store.uptodown

import java.util.zip.GZIPInputStream

/**
 * uptodown's real pages, captured on 24/08/2026 and committed compressed.
 *
 * **The bytes are the server's, untouched** — provenance in the `README.md` next to the files. To
 * read one: `gzcat detail.html.gz | less`.
 *
 * Why they are not trimmed: this store's "no results" fixture is the clearest example in the whole
 * project. When uptodown finds nothing it **does not emit** `#content-list` and puts twelve "Apps
 * you're gonna love" cards in its place, with markup identical to the results'. A hand-picked
 * fragment would not contain that section, and the parser would pass the test while lying in
 * production.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/uptodown"

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

    /** `/android/top`: ten entries, the rank written inside the title. */
    const val TOP: String = "top.html.gz"

    /** `/android/latest-updates`: the same `#content-list` as search. */
    const val LATEST_UPDATES: String = "latest-updates.html.gz"
    const val SEARCH_EMPTY: String = "search-empty.html.gz"
    const val DETAIL: String = "detail.html.gz"
    const val VERSIONS: String = "versions.html.gz"
    const val DOWNLOAD: String = "download.html.gz"
    const val DOWNLOAD_OLD: String = "download-old.html.gz"
    const val NOT_FOUND: String = "not-found.html.gz"

    /** The app all the fixtures were taken from. */
    const val APP_SLUG: String = "telegram"
    const val APP_TITLE: String = "Telegram"

    /**
     * The package is **not** `org.telegram.messenger`.
     *
     * uptodown redistributes the `….web` build, and the difference is not a detail: it is what
     * step 4 of the pipeline compares. Two stores can call the same listing "Telegram" and serve
     * two different packages, which Android treats as two apps.
     */
    const val APP_PACKAGE: String = "org.telegram.messenger.web"

    const val CURRENT_VERSION: String = "12.9.2"
    const val CURRENT_FILE_ID: String = "1195732851"
    const val CURRENT_SHA256: String = "193ad551e2cbb745387f26370369f9cd0cf0353ecbc318398ada087ac2bf945e"

    const val OLD_VERSION_ID: String = "1191373665"
    const val OLD_VERSION_NAME: String = "12.9.1"
    const val OLD_SHA256: String = "a7b9f37f59ce758fea480e3d80dab1f668e82b4a1572885a1adb5405106f3b6c"

    /** The listing's "Certificate signature": 32 characters, i.e. **MD5**, not SHA-256. */
    const val CERTIFICATE_MD5: String = "26babc62540ef0c20bfc6bacf3d3b1f5"

    const val QUERY_WITH_RESULTS: String = "telegram"

    /**
     * The query that finds nothing on uptodown — and still returns twelve cards.
     *
     * Those twelve are not results: they sit under "Apps you're gonna love", outside
     * `#content-list`, and they are always the same. Telegram is among them.
     */
    const val QUERY_WITHOUT_RESULTS: String = "qzxvnpwmkljhgfd"
}
