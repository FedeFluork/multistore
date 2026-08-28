package com.multistore.store.an1

import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.store.common.html.Urls

/**
 * How an1 identifies an app, and how it is wrapped into an opaque ref.
 *
 * An an1 listing lives at `/{id}-{slug}.html`, at the root: no category directory, no subdomain.
 * The ref is therefore `2971-telegram`, i.e. the file name without `.html`.
 *
 * **The numeric id is not decorative, which is why it stays in the ref**: the page carrying the
 * file's link is `/file_{id}-dw.html`, and that id is not derivable from the slug. Keeping only
 * the slug would mean being unable to resolve any download without reopening the listing first.
 *
 * `StoreAppRef` is opaque to the core; that in here it *is* a piece of URL is a detail of this
 * store and does not leave this file.
 */
internal object An1Refs {

    /** From a listing URL to the ref: `https://an1.com/2971-telegram.html` -> `2971-telegram`. */
    fun refFromUrl(url: String): StoreAppRef? {
        val segments = Urls.segments(url)
        if (segments.size != PATH_SEGMENTS) return null
        val stem = segments[0].removeSuffix(HTML_SUFFIX)
        if (stem == segments[0]) return null
        return stem.takeIf { STEM.matches(it) }?.let(::StoreAppRef)
    }

    /**
     * The stem `2971-telegram`, or `null` if the ref does not have that shape.
     *
     * The validation is not schema pedantry: the contract test hands every adapter a ref like
     * `../../etc/passwd?<script>&%00`, and without this check it would end up concatenated into a
     * URL.
     */
    fun stem(ref: StoreAppRef): String? = ref.value.trim('/').takeIf { STEM.matches(it) }

    /** The numeric id, the only thing the download URL is built from. */
    fun idOf(ref: StoreAppRef): String? = stem(ref)?.substringBefore('-')

    /**
     * App or game, read from the category the listing declares.
     *
     * an1 splits its catalogue into games and programs, and the microdata's application category
     * carries the English label of that split. It is the only place on the listing that says so:
     * the slug does not distinguish them.
     */
    fun contentKindOf(category: String?): ContentKind = when (category?.trim()?.lowercase()) {
        GAMES_LABEL -> ContentKind.GAME
        PROGRAMS_LABEL -> ContentKind.APP
        else -> ContentKind.UNKNOWN
    }

    private const val HTML_SUFFIX = ".html"
    private const val PATH_SEGMENTS = 1
    private const val GAMES_LABEL = "games"
    private const val PROGRAMS_LABEL = "programs"

    /** `2971-telegram`: digits, a hyphen, and a slug. */
    private val STEM = Regex("""\d+-[a-z0-9][a-z0-9-]*""")
}
