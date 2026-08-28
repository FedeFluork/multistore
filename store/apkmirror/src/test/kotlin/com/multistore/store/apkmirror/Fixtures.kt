package com.multistore.store.apkmirror

import java.util.zip.GZIPInputStream

/**
 * apkmirror's real pages, captured on 24/08/2026 and committed compressed.
 *
 * Uncompressed they are 2.6 MB — every page carries six sidebar widgets with hundreds of rows —
 * and the bytes are the server's, untrimmed. Each one's provenance, with the response code and the
 * protocol used, is in the `README.md` next to the files.
 *
 * Keeping them whole is not laziness: the most important fixture is the "no results" one, which
 * has **38 sidebar rows** with the results' markup and zero results. A fragment trimmed around the
 * results table would never have exposed that trap.
 */
object Fixtures {

    private const val DIRECTORY = "fixtures/apkmirror"

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
    const val SEARCH_EMPTY: String = "search-empty.html.gz"
    const val APP: String = "app.html.gz"

    /** `/feed/`, 25/08/2026: ten entries, with name, version and developer in the title. */
    const val RECENT_FEED: String = "recent-feed.xml.gz"
    const val RELEASE: String = "release.html.gz"
    const val VARIANT_APK: String = "variant-apk.html.gz"
    const val VARIANT_BUNDLE: String = "variant-bundle.html.gz"
    const val INTERSTITIAL: String = "interstitial.html.gz"
    const val NOT_FOUND: String = "not-found.html.gz"

    /** The challenge page: a listing requested **over HTTP/2**. */
    const val CHALLENGE: String = "challenge.html.gz"

    /** The app the full chain is captured from. */
    const val APP_PATH: String = "mozilla/firefox"
    const val APP_PACKAGE: String = "org.mozilla.firefox"
    const val APP_TITLE: String = "Firefox Fast & Private Browser"
    const val RELEASE_PATH: String = "$APP_PATH/firefox-fast-private-browser-154-0-release"
    const val VARIANT_APK_PATH: String = "$RELEASE_PATH/firefox-fast-private-browser-154-0-6-android-apk-download"
    const val VARIANT_BUNDLE_PATH: String = "$RELEASE_PATH/firefox-fast-private-browser-154-0-5-android-apk-download"

    /** The release's version code: the same for the bundle and the single APK. */
    const val VERSION_CODE: Long = 2_016_178_287L

    /** The APK variant's exact size: `595.68 MB (624,620,840 bytes)`. */
    const val FILE_SIZE_BYTES: Long = 624_620_840L

    /** The SHA-256 of the APK variant's **file**. */
    const val FILE_SHA256: String = "6e137ae46aba12c6b6f4233d76bb7cb1d2cd2116539a05c0f7aeaa0164e07672"

    /** The SHA-256 of the signing **certificate**: the same on APK and bundle. */
    const val SIGNER_SHA256: String = "a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04"

    const val QUERY_WITH_RESULTS: String = "telegram"
    const val QUERY_WITHOUT_RESULTS: String = "zzqxwvkjhgfdsapoiuytrewq"
}
