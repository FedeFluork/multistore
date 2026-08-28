package com.multistore.store.apkmirror

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.store.common.html.Urls

/**
 * How apkmirror identifies apps, releases and variants — and the three levels really are three.
 *
 * All content sits under `/apk/`, and the **number of segments** says what it is:
 *
 * | Path | What it is |
 * |---|---|
 * | `/apk/mozilla/firefox/` | the app's listing |
 * | `/apk/mozilla/firefox/{release}/` | one release, containing its variants |
 * | `/apk/mozilla/firefox/{release}/{variant}/` | a downloadable file: one ABI, one dpi |
 *
 * Not a cosmetic detail: the **same search page** mixes three- and four-segment links — the first
 * ten rows are apps, the later ones releases of apps containing the search term — and returning a
 * release as though it were an app would give a listing with a single version and a title
 * containing the number.
 */
internal object ApkMirrorRefs {

    /** From a listing URL to the ref: `/apk/mozilla/firefox/` -> `mozilla/firefox`. */
    fun appRefFromUrl(url: String): StoreAppRef? {
        val parts = contentSegments(url) ?: return null
        if (parts.size != APP_SEGMENTS) return null
        return StoreAppRef(parts.joinToString("/"))
    }

    /**
     * The **listing's** ref starting from a release URL.
     *
     * That is what the RSS feed needs, where every entry points at a file and not at the app.
     *
     * It is not [appRefFromUrl] with an extra truncation because the two callers want different
     * things: that one demands **exactly** two segments — a release URL passed where a listing is
     * expected is a defect and must return `null` rather than be accommodated — while here three
     * segments are the normal shape.
     */
    fun appRefFromReleaseUrl(url: String): StoreAppRef? {
        val parts = contentSegments(url) ?: return null
        if (parts.size < APP_SEGMENTS) return null
        return StoreAppRef(parts.take(APP_SEGMENTS).joinToString("/"))
    }

    /**
     * The listing's path.
     *
     * The name is not `pathOf` as in the apkcombo adapter, for a compiler reason worth knowing:
     * [StoreAppRef] and [VersionRef] are two value classes both erasing to `String`, so two
     * overloads differing only in that parameter have the **same JVM signature** and do not
     * compile.
     */
    fun appPath(ref: StoreAppRef): String? =
        ref.value.trim('/').takeIf { SAFE_PATH.matches(it) && it.count { c -> c == '/' } == 1 }

    /**
     * A reference to a **variant**, i.e. to a file.
     *
     * The value is the full path under `/apk/`, prefixed by the kind. The prefix is needed because
     * a version ref can also point at a whole release — see [releaseRef] — and the two resolve
     * differently: from a variant the file is two hops away, from a release a variant has to be
     * chosen first.
     */
    fun variantRef(path: String): VersionRef = VersionRef("$VARIANT_PREFIX$path")

    /** A reference to a **release**: we will pick the variant when resolving. */
    fun releaseRef(path: String): VersionRef = VersionRef("$RELEASE_PREFIX$path")

    fun isVariant(ref: VersionRef): Boolean = ref.value.startsWith(VARIANT_PREFIX)

    fun versionPath(ref: VersionRef): String? = ref.value
        .removePrefix(VARIANT_PREFIX)
        .removePrefix(RELEASE_PREFIX)
        .trim('/')
        .takeIf { SAFE_PATH.matches(it) }

    /** The segments after `/apk/`, or `null` if the URL is not this store's. */
    fun contentSegments(url: String): List<String>? {
        val segments = Urls.segments(url)
        if (segments.firstOrNull() != ApkMirrorConfig.APK_PREFIX) return null
        val rest = segments.drop(1)
        return rest.takeIf { it.isNotEmpty() && it.all(::isSafeSegment) }
    }

    /** The relative path under `/apk/`, for any link in the chain. */
    fun contentPath(url: String): String? = contentSegments(url)?.joinToString("/")

    private fun isSafeSegment(value: String): Boolean = SAFE_SEGMENT.matches(value)

    private const val APP_SEGMENTS = 2
    private const val VARIANT_PREFIX = "v:"
    private const val RELEASE_PREFIX = "r:"
    private val SAFE_SEGMENT = Regex("""[A-Za-z0-9._-]+""")
    private val SAFE_PATH = Regex("""[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*""")
}
