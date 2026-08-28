package com.multistore.store.apkmody

import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.store.common.html.Urls

/**
 * How apkmody identifies an app and a version, and how those are wrapped into opaque refs.
 *
 * A listing lives at `/apps/{slug}` or `/games/{slug}`, and **the first segment is not
 * decorative**: it is the only place the store says whether the entry is an app or a game, and it
 * is not derivable from the slug. The ref keeps both.
 *
 * `StoreAppRef` is opaque to the core; that in here it *is* a path is a detail of this store and
 * does not leave this file.
 */
internal object ApkModyRefs {

    /** From a URL or a relative href to the ref: `/apps/spotify-pro` -> `apps/spotify-pro`. */
    fun refFromUrl(url: String): StoreAppRef? {
        val segments = Urls.segments(url)
        if (segments.size != PATH_SEGMENTS) return null
        val kind = segments[0].lowercase()
        if (kind !in KINDS) return null
        if (!SLUG.matches(segments[1])) return null
        return StoreAppRef("$kind/${segments[1]}")
    }

    /**
     * The `apps/{slug}` path to use in URLs, or `null` if the ref does not have that shape.
     *
     * The validation is not schema pedantry: the contract test hands every adapter a ref like
     * `../../etc/passwd?<script>&%00`, and without this check it would end up concatenated into a
     * URL.
     */
    fun appPath(ref: StoreAppRef): String? {
        val value = ref.value.trim('/')
        val parts = value.split('/')
        if (parts.size != PATH_SEGMENTS) return null
        if (parts[0].lowercase() !in KINDS) return null
        if (!SLUG.matches(parts[1])) return null
        return "${parts[0].lowercase()}/${parts[1]}"
    }

    /** App or game: written in the first segment, and no second place says it. */
    fun contentKindOf(ref: StoreAppRef): ContentKind = when (appPath(ref)?.substringBefore('/')) {
        GAMES -> ContentKind.GAME
        APPS -> ContentKind.APP
        else -> ContentKind.UNKNOWN
    }

    /**
     * The reference to a downloadable version: **the path fragment that serves it**.
     *
     * A history path for a past version, the download path for the current one when the history
     * does not list it. Not the version code and not the file name: apkcombo's lesson is that a
     * version ref must identify **a file** and stay resolvable without inventing a URL —
     * `app_versions` has a unique constraint on `(listing_id, version_ref)`, and a non-unique
     * discriminator silently makes every version but the last written disappear.
     *
     * Here the correspondence is exact by construction: every history page serves a single file,
     * and apkmody assigns the id.
     */
    fun versionRef(segment: String): VersionRef = VersionRef(segment)

    /** The path to open to resolve [ref]; the download path (current) if it is unusable. */
    fun versionSegment(ref: VersionRef?): String {
        val value = ref?.value?.trim('/').orEmpty()
        if (value == ApkModyConfig.DOWNLOAD_SEGMENT) return value
        val parts = value.split('/')
        if (parts.size == HISTORY_SEGMENTS &&
            parts[0] == ApkModyConfig.HISTORY_SEGMENT &&
            HISTORY_ID.matches(parts[1])
        ) {
            return value
        }
        return ApkModyConfig.DOWNLOAD_SEGMENT
    }

    /**
     * From the CDN path to the `packageName`.
     *
     * It is **verified against the APK**, not inferred from the shape: one file downloaded from a
     * package path declares that same package in its manifest.
     */
    fun packageNameFromDownloadUrl(url: String): String? {
        val segments = Urls.segments(url)
        val index = segments.indexOf(PACKAGES_SEGMENT)
        if (index < 0 || index + 1 >= segments.size) return null
        return segments[index + 1].takeIf { PACKAGE_NAME.matches(it) }
    }

    /**
     * The version code from the file name.
     *
     * Read **from the right**, because the app's name can contain underscores while the last three
     * fields are always the same three: version name, version code, six characters of hash.
     * Verified against the real APK — see [packageNameFromDownloadUrl].
     */
    fun versionCodeFromFileName(fileName: String): Long? {
        val stem = fileName.substringBeforeLast('.')
        val parts = stem.split('_')
        if (parts.size < FILE_NAME_FIELDS) return null
        return parts[parts.size - 2].toLongOrNull()
    }

    /** The version name from the same file name, read from the right in the same way. */
    fun versionNameFromFileName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.')
        val parts = stem.split('_')
        if (parts.size < FILE_NAME_FIELDS) return null
        // If the penultimate field is not a number the name does not have the expected shape, and
        // guessing the version name from a position would give the wrong fragment.
        if (parts[parts.size - 2].toLongOrNull() == null) return null
        return parts[parts.size - 3].takeIf { it.isNotBlank() }
    }

    private const val APPS = "apps"
    private const val GAMES = "games"
    private const val PACKAGES_SEGMENT = "packages"
    private const val PATH_SEGMENTS = 2
    private const val HISTORY_SEGMENTS = 2

    /** Name, version, version code, hash: below four fields the name lacks this shape. */
    private const val FILE_NAME_FIELDS = 4

    private val KINDS = setOf(APPS, GAMES)
    private val SLUG = Regex("""[a-z0-9][a-z0-9.-]*""")
    private val HISTORY_ID = Regex("""[A-Za-z0-9_-]+""")
    private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
}
