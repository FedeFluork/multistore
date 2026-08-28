package com.multistore.store.modyolo

import java.util.zip.GZIPInputStream

/**
 * modyolo's real responses, captured on 25/08/2026 and committed compressed.
 *
 * They are the server's bytes, untouched: the provenance of each is in the `README.md` next to the
 * files. To read one: `gzcat detail.json.gz | python3 -m json.tool | less`.
 *
 * The two search fixtures on the same query, with and without `categories_exclude`, are not a
 * duplicate: together they are **the proof that the adult-content filter really filters** — and,
 * looking at what survives, also the proof that it does not filter everything.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/modyolo"

    fun bytes(name: String): ByteArray {
        val stream = requireNotNull(Fixtures::class.java.classLoader.getResourceAsStream("$DIRECTORY/$name")) {
            "Missing fixture: $DIRECTORY/$name"
        }
        return stream.use { raw ->
            if (name.endsWith(GZIP_SUFFIX)) GZIPInputStream(raw).readBytes() else raw.readBytes()
        }
    }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    private const val GZIP_SUFFIX = ".gz"

    const val SEARCH: String = "search.json.gz"
    const val SEARCH_EMPTY: String = "search-empty.json.gz"
    const val SEARCH_NSFW: String = "search-nsfw.json.gz"
    const val SEARCH_NSFW_EXCLUDED: String = "search-nsfw-excluded.json.gz"
    const val DETAIL: String = "detail.json.gz"
    const val DETAIL_MISSING: String = "detail-missing.json.gz"
    const val DOWNLOAD_PAGE: String = "download-page.html.gz"
    const val DOWNLOAD_AJAX: String = "download-ajax.html.gz"

    /**
     * A post with **a single variant**: the page emits no accordion at all.
     *
     * It is the fixture of a fault found on the emulator: the listing said "This store publishes
     * no installable package for this app" in front of a file that downloads perfectly well.
     */
    const val DOWNLOAD_PAGE_SINGLE: String = "download-page-single.html.gz"

    /** The fragment from its AJAX call, and its URL has **raw spaces**. */
    const val DOWNLOAD_AJAX_SINGLE: String = "download-ajax-single.html.gz"
    const val DETAIL_SINGLE: String = "detail-single.json.gz"

    /** The listing the fixtures were taken from: three variants listed. */
    const val APP_REF: String = "minecraft-19"
    const val APP_ID: String = "19"
    const val APP_SLUG: String = "minecraft"
    const val APP_TITLE: String = "Minecraft"
    const val APP_PUBLISHER: String = "Mojang"
    const val APP_VERSION: String = "1.26.50.24"
    const val APP_FILE: String = "Minecraft_v1_26_50_24.apk"
    const val APP_VARIANTS: Int = 3

    /**
     * The package, deduced from the listing's Google Play link.
     *
     * **Verified against the real APKs** rather than inferred from the shape: eight files
     * downloaded from modyolo and read with `aapt2 dump packagename`, seven of them marked MOD in
     * their own listing — **eight matches out of eight**. Repackaging does not change the package
     * name.
     */
    const val APP_PACKAGE: String = "com.mojang.minecraftpe"

    /** The single-variant listing: `Toolbox for Minecraft: PE`. */
    const val SINGLE_REF: String = "toolbox-for-minecraft-pe-36062"
    const val SINGLE_ID: String = "36062"
    const val SINGLE_VERSION: String = "5.4.54 - Mod"
    const val SINGLE_FILE: String = "toolbox-for-minecraft-pe v5.4.54.apk"

    const val QUERY_WITH_RESULTS: String = "minecraft"

    /**
     * A query that genuinely finds nothing on modyolo.
     *
     * The search is WordPress full-text: `X-WP-Total: 0` and an empty array, with no suggested
     * cards in their place. It is the only store where "no results" is a clean answer rather than
     * a page to be told apart.
     */
    const val QUERY_WITHOUT_RESULTS: String = "qzxvnpwmkljhgfd"

    /** A query whose results include content modyolo labels as adult. */
    const val QUERY_WITH_NSFW: String = "lewd"
    const val NSFW_INCLUDED_RESULTS: Int = 15
    const val NSFW_EXCLUDED_RESULTS: Int = 3
}
