package com.multistore.store.apkcombo

import java.util.zip.GZIPInputStream

/**
 * apkcombo's real pages, captured on 24/08/2026 and committed compressed.
 *
 * Gzipped rather than plain HTML for size, not secrecy: uncompressed they are 530 KB of markup of
 * which 60% is advertising and sidebar. **The bytes are the server's, untouched** — each one's
 * provenance is in the `README.md` next to the files. To read one: `gzcat search.html.gz | less`.
 *
 * Why they are not trimmed: a parser tested against a fragment chosen by whoever wrote it proves
 * it can read what that person expected. Tested against the whole page it has to live with the
 * twenty category links sharing the results' class and with the lazy-loaded icons — i.e. with the
 * two things that would really break it.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/apkcombo"

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

    /** The latest-updates feed, 25/08/2026: 98 entries, all dated that day. */
    const val RECENT_FEED: String = "recent-feed.xml.gz"
    const val SEARCH_EMPTY: String = "search-empty.html.gz"
    const val DETAIL: String = "detail.html.gz"
    const val DOWNLOAD: String = "download.html.gz"
    const val DOWNLOAD_OLD: String = "download-old.html.gz"

    /**
     * A variants page with **no variant**, and the version list still on it.
     *
     * It is another app on purpose — see the README: the fixture app has variants on its latest
     * page, so the dead end cannot be photographed from it.
     */
    const val DOWNLOAD_NO_VARIANTS: String = "download-no-variants.html.gz"
    const val OLD_VERSIONS: String = "old-versions.html.gz"
    const val NOT_FOUND: String = "not-found.html.gz"

    /** The app all the fixtures are taken from. */
    const val APP_PATH: String = "telegram/org.telegram.messenger"
    const val APP_PACKAGE: String = "org.telegram.messenger"
    const val APP_TITLE: String = "Telegram"

    /**
     * The query that finds **genuinely** nothing on apkcombo.
     *
     * apkcombo searches by substring: obvious nonsense strings still return dozens of apps because
     * they contain a real word. What is needed is a token that is a substring of no real title.
     */
    const val QUERY_WITHOUT_RESULTS: String = "zzqxwvkjhgfdsapoiuytrewq"
    const val QUERY_WITH_RESULTS: String = "telegram"
}
