package com.multistore.store.pdalife

import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.store.common.html.Urls

/**
 * How pdalife identifies an app and a file, and how they are packed into opaque refs.
 *
 * A listing lives at `/{alias}-{os}-a{id}.html`, at the root. The ref is therefore
 * `telegram-android-a14523`, i.e. the file name without `.html`.
 *
 * ### The `os` in the middle is not decorative: it is the filter
 *
 * pdalife publishes the same catalogue for Android, iOS and PSP, with the **same** markup and in
 * the **same** result list: `/telegram1-ios-a26129.html` sits next to `/telegram-android-a14523.html`,
 * and `/-psp-a34978.html` — with an empty alias — is on the second page of "minecraft". [STEM]
 * requires `-android-a{digits}`, so a ref from another platform is not a valid ref and never gets
 * as far as building a URL.
 *
 * ### The numeric id stays in the ref because it is the only key the internal APIs accept
 *
 * `POST /app/moreVersions/` wants `id={id}`, and that id cannot be derived from the alias.
 *
 * `StoreAppRef` is opaque to the core: only the adapter interprets it. That the ref here *is* a
 * piece of a URL is a detail of this store and does not leave this file.
 */
internal object PdalifeRefs {

    /** From a listing URL to the ref, **only if it is an Android listing**. */
    fun refFromUrl(url: String): StoreAppRef? {
        val segments = Urls.segments(url)
        if (segments.size != PATH_SEGMENTS) return null
        val stem = segments[0].removeSuffix(HTML_SUFFIX)
        if (stem == segments[0]) return null
        return stem.takeIf { STEM.matches(it) }?.let(::StoreAppRef)
    }

    /**
     * The stem `telegram-android-a14523`, or `null` if the ref does not have that shape.
     *
     * The validation is not schema pedantry: the contract test hands every adapter a ref like
     * `../../etc/passwd?<script>&%00`, and without this check it would end up concatenated into a
     * URL.
     */
    fun stem(ref: StoreAppRef): String? = ref.value.trim('/').takeIf { STEM.matches(it) }

    /** The numeric id, which is the key pdalife's own APIs use to talk about this app. */
    fun idOf(ref: StoreAppRef): String? = stem(ref)?.substringAfterLast(ID_MARKER)

    /**
     * A file's ref, which is the octet in the path `/dwn/{hash}.html`.
     *
     * **It is not hexadecimal**, although it looks it: among the twelve observed are `33n84e18`
     * and `9n420705`. A `[0-9a-f]{8}` pattern would discard exactly those, and would do it
     * silently — the download would come out "not found" on a file that exists.
     */
    fun versionRef(hash: String): VersionRef = VersionRef(hash)

    /** The opposite direction, with the same validation: a `VersionRef` comes from the core. */
    fun downloadHash(version: VersionRef?): String? =
        version?.value?.takeIf { DOWNLOAD_HASH.matches(it) }

    /** From `/dwn/fe8bc99d.html?lang=en` to the octet. */
    fun hashFromDownloadUrl(url: String): String? {
        val segments = Urls.segments(url)
        if (segments.size != DOWNLOAD_SEGMENTS || segments[0] != DOWNLOAD_DIRECTORY) return null
        val hash = segments[1].removeSuffix(HTML_SUFFIX)
        return hash.takeIf { DOWNLOAD_HASH.matches(it) }
    }

    /**
     * App or game, read from the breadcrumbs and not from the label.
     *
     * pdalife splits the catalogue into `/android/games/` and `/android/programmy/`. The text next
     * to it is translated by the server ("Programs on Android" from an Italian IP, Russian
     * elsewhere); the href is not.
     */
    fun contentKindOf(breadcrumbHrefs: List<String>): ContentKind = when {
        breadcrumbHrefs.any { GAMES_PATH in it } -> ContentKind.GAME
        breadcrumbHrefs.any { PROGRAMS_PATH in it } -> ContentKind.APP
        else -> ContentKind.UNKNOWN
    }

    private const val HTML_SUFFIX = ".html"
    private const val PATH_SEGMENTS = 1
    private const val DOWNLOAD_SEGMENTS = 2
    private const val DOWNLOAD_DIRECTORY = "dwn"
    private const val ID_MARKER = "-a"
    private const val GAMES_PATH = "/android/games"
    private const val PROGRAMS_PATH = "/android/programmy"

    /** `telegram-android-a14523`: an alias, the platform, and the id. */
    private val STEM = Regex("""[a-z0-9][a-z0-9-]*-android-a\d+""")

    /** Eight lowercase alphanumeric characters. See the note on `33n84e18` in [versionRef]. */
    private val DOWNLOAD_HASH = Regex("""[a-z0-9]{8}""")
}
