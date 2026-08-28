package com.multistore.store.apkmody

import java.util.zip.GZIPInputStream

/**
 * apkmody's real pages, captured on 24/08/2026 and committed compressed.
 *
 * Gzipped rather than plain HTML for size: uncompressed they are 1.6 MB, most of it inline CSS,
 * JavaScript and ad slots. **The bytes are the server's, untouched** — each one's provenance is in
 * the `README.md` next to the files.
 *
 * Why they are not trimmed: a parser tested against a fragment chosen by whoever wrote it proves it
 * can read what that person expected. Tested against the whole page it has to live with the "Fast
 * Download" button pointing at apkmody's installer, with the same anchors repeated inside HTML
 * comments, and with the footer listing trending apps — i.e. with the three things that would
 * really break it.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/apkmody"

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

    /** `/popular`, 25/08/2026: twelve entries in the structured-data list block. */
    const val POPULAR: String = "popular.html.gz"
    const val SEARCH_EMPTY: String = "search-empty.html.gz"
    const val DETAIL: String = "detail.html.gz"
    const val DOWNLOAD: String = "download.html.gz"
    const val HISTORY: String = "history.html.gz"
    const val HISTORY_VERSION: String = "history-version.html.gz"
    const val NOT_FOUND: String = "not-found.html.gz"

    /**
     * **Another** app's history, to exercise the package contradiction.
     *
     * It is a real page whose file lives under a different package path. Served in place of the
     * expected one, it builds the case that matters out of two authentic pages: the listing declares
     * one package, the file declares another.
     */
    const val HISTORY_OTHER_APP: String = "history-other-app.html.gz"

    /** The app all the fixtures are taken from: four versions in its history. */
    const val APP_PATH: String = "apps/spotify-pro"
    const val APP_PACKAGE: String = "com.spotify.music"
    const val APP_TITLE: String = "Spotify Pro"
    const val APP_LATEST_VERSION: String = "9.1.36.1948"
    const val APP_LATEST_VERSION_CODE: Long = 151061948L
    const val APP_LATEST_FILE: String = "Spotify_Pro_9.1.36.1948_151061948_eca7c8.apk"

    /** The history version whose page fixture exists. */
    const val OLD_VERSION_SEGMENT: String = "history/xyTAa4R6VE"
    const val OLD_VERSION_NAME: String = "9.1.34.2060"
    const val OLD_VERSION_CODE: Long = 151042060L

    const val QUERY_WITH_RESULTS: String = "spotify"

    /**
     * The query that finds **genuinely** nothing on apkmody.
     *
     * apkmody searches fuzzily, not by substring, and the difference is measured: nonsense Latin
     * strings return twenty results, another returns exactly one. No string of Latin letters,
     * however absurd, produces zero results: an alphabet absent from the titles is needed. Hence
     * the Georgian one.
     */
    const val QUERY_WITHOUT_RESULTS: String = "ღყშ"
}
