package com.multistore.store.modyolo

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.store.common.html.Urls

/**
 * How modyolo identifies an app and a variant, and how that is packed into opaque refs.
 *
 * A modyolo post has **two** identities and both are needed: the numeric **id**, the only input to
 * `/wp-json/v1/posts/{id}`, and the **slug**, without which `/download/{slug}-{id}` cannot be
 * composed — the page whose URL, passed as the `Referer`, is what tells the AJAX endpoint which
 * file to serve. The ref keeps them together in the form the site itself uses: `minecraft-19`.
 *
 * **It is read from the right**, and that is not a detail: `video-compressor-panda-2-585253` has a
 * slug ending in a number. Splitting at the first hyphen would give the slug `video` and the id
 * `compressor-panda-2-585253`.
 */
internal object ModyoloRefs {

    /** From a listing URL to the ref: `https://modyolo.com/minecraft.html` plus the id. */
    fun refOf(slug: String, id: Int): StoreAppRef? {
        if (!SLUG.matches(slug) || id <= 0) return null
        return StoreAppRef("$slug-$id")
    }

    /** The stem `minecraft-19`, or `null` if the ref does not have that shape. */
    fun stem(ref: StoreAppRef): String? = ref.value.trim('/').takeIf { STEM.matches(it) }

    /**
     * The numeric id.
     *
     * The validation is not schema pedantry: the contract test hands every adapter a ref like
     * `../../etc/passwd?<script>&%00`, and without this check it would end up concatenated into
     * a URL.
     */
    fun idOf(ref: StoreAppRef): String? = stem(ref)?.substringAfterLast('-')

    /** The slug on its own, to compose the download page's URL. */
    fun slugOf(ref: StoreAppRef): String? = stem(ref)?.substringBeforeLast('-')

    /** The variant is an index: `1` is the current one, the others are previous versions. */
    fun versionRef(variant: Int): VersionRef = VersionRef(variant.toString())

    /** The index from a version ref; [FIRST_VARIANT] if it is unusable. */
    fun variantOf(ref: VersionRef?): Int =
        ref?.value?.trim()?.toIntOrNull()?.takeIf { it in VARIANT_RANGE } ?: FIRST_VARIANT

    /** The index from the accordion anchor: `#version-3` -> `3`. */
    fun variantFromAnchor(anchor: String): Int? =
        anchor.removePrefix(ModyoloSelectors.VERSION_ANCHOR_PREFIX)
            .toIntOrNull()
            ?.takeIf { it in VARIANT_RANGE }

    /**
     * **Conditional** percent-encoding of a file URL's path.
     *
     * The implementation moved up into [Urls.normalizeFileUrl] once liteapks turned out to have
     * **exactly the same mixture** — old entries already encoded, new entries with raw spaces — on
     * its CDN. The method stays here because the measurement justifying it belongs to this store:
     * of forty binaries from the oldest layer, without normalisation twenty-eight looked
     * unreachable and appeared dead; with it, the genuinely dead ones are eleven.
     */
    fun normalizeFileUrl(url: String): String = Urls.normalizeFileUrl(url)

    /** The file name from the already-normalised URL. */
    fun fileNameOf(url: String): String = Urls.fileNameOf(url, FALLBACK_FILE_NAME)

    const val FIRST_VARIANT: Int = 1
    private const val FALLBACK_FILE_NAME = "modyolo.apk"

    /** Observed variants go up to three; the cap is a defence, not a measurement. */
    private val VARIANT_RANGE = 1..99

    private val SLUG = Regex("""[a-z0-9][a-z0-9-]*""")
    private val STEM = Regex("""[a-z0-9][a-z0-9-]*-\d+""")

}
