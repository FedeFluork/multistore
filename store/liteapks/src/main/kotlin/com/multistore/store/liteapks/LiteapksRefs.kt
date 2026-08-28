package com.multistore.store.liteapks

import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.store.common.html.Urls
import java.util.Base64
import kotlin.time.Instant

/**
 * How liteapks identifies an app and a file, and how that is packed into opaque refs.
 *
 * A listing lives at `/{slug}.html`, at the root: no category folder, no id in the path. The ref is
 * therefore the slug — `telegram`, `minecraft`, `h-i-d-e`.
 *
 * ### The version ref carries the id, because the slug is not enough to find it
 *
 * The file page is `/download/{download-slug}-{postId}`, and **the download slug is not the
 * listing's**: `/h-i-d-e.html` downloads from `/download/hide-h-i-d-e-72683`, `/minicraft.html`
 * from `/download/minicraft-blocky-craft-12165`, `/ime-ai-messenger-for-telegram.html` from
 * `/download/ime-538265`. Nor is the numeric id derivable: the search card does not publish it.
 *
 * Hence the shape of the [VersionRef], which has **two** variants because the store serves two
 * kinds of file:
 *
 *  - `minecraft-11909/2` — a **slot**: the page `/download/{stem}/{n}`, where the file liteapks has
 *    modified lives. It has to be opened to read its `data-link`;
 *  - `https://gp4.liteapks.com/…/Minecraft%20Earth-0.33.0.apk` — a direct URL, i.e. the "Original
 *    file on Google Play" block, which is the **unmodified** APK and has no intermediate page. On
 *    one sampled listing out of thirty-one (`minecraft-earth`) it is the only file that exists, so
 *    discarding it would mean a listing that offers nothing.
 *
 * The two are told apart without prefixes: the second starts with `https://`, which cannot appear
 * in the first.
 *
 * `StoreAppRef` is opaque to the core: only the adapter interprets it. That a ref here *is* a piece
 * of a URL is a detail of this store and does not leave this file.
 */
internal object LiteapksRefs {

    /** From a listing URL to the ref: `https://liteapks.com/telegram.html` -> `telegram`. */
    fun refFromUrl(url: String): StoreAppRef? {
        val segments = Urls.segments(url)
        if (segments.size != PATH_SEGMENTS) return null
        val slug = segments[0].removeSuffix(HTML_SUFFIX)
        if (slug == segments[0]) return null
        return slug.takeIf { SLUG.matches(it) }?.let(::StoreAppRef)
    }

    /**
     * The slug, or `null` if the ref does not have that shape.
     *
     * The validation is not schema pedantry: the contract test hands every adapter a ref like
     * `../../etc/passwd?<script>&%00`, and without this check it would end up concatenated into a
     * URL.
     */
    fun slug(ref: StoreAppRef): String? = ref.value.trim('/').takeIf { SLUG.matches(it) }

    /** The download page's stem — `minecraft-11909` — from a URL the listing publishes. */
    fun downloadStemFromUrl(url: String): String? {
        val segments = Urls.segments(url)
        if (segments.size < DOWNLOAD_SEGMENTS || segments[0] != DOWNLOAD_SEGMENT) return null
        return segments[1].takeIf { STEM.matches(it) }
    }

    /** A slot's ref: `minecraft-11909` + `2` -> `minecraft-11909/2`. */
    fun slotRef(stem: String, slot: Int): VersionRef = VersionRef("$stem/$slot")

    /** An original file's ref, which is the URL itself. */
    fun directRef(url: String): VersionRef = VersionRef(url)

    /** The slot [ref] points at, or `null` if it points at a direct URL or nothing valid. */
    fun slotOf(ref: VersionRef?): Slot? {
        val value = ref?.value?.trim() ?: return null
        if (!SLOT.matches(value)) return null
        val stem = value.substringBefore('/')
        val slot = value.substringAfter('/').toIntOrNull() ?: return null
        return Slot(stem, slot).takeIf { slot in SLOT_RANGE }
    }

    /** The direct URL [ref] points at, or `null` if it points at a slot. */
    fun directUrlOf(ref: VersionRef?): String? =
        ref?.value?.trim()?.takeIf { Urls.isSecureOrLoopback(it) }

    /** A slot: a post's file page, and which row of that page. */
    data class Slot(val stem: String, val index: Int)

    /**
     * The transit permit `download*.liteapks.dev` demands, and what it **is not**.
     *
     * The worker in front of that domain answers `403 "Access is not allowed."` to anyone not
     * carrying a `?token=` **and** a `Referer` from `liteapks.com`; with both it answers 200. The
     * token is `btoa(btoa(expiry))` — a Unix timestamp in base64, twice — and the theme writes it
     * in the clear in its own `site.js`, where it computes it at click time.
     *
     * **Measured against what the worker really checks**, because whether computing it is
     * legitimate depends on that:
     *
     * | token | outcome |
     * |---|---|
     * | expiry in 3 hours | 200 |
     * | expiry in 10 days | **200** |
     * | expiry already past | 403 |
     * | non-numeric text | 403 |
     * | single base64 | 403 |
     *
     * There is no signature, no key, no secret: it is a **client-declared expiry**, which the
     * worker merely compares with the clock. There is nothing to pretend, because there is nothing
     * attesting to anything.
     *
     * That is the line — really doing what the site asks is legitimate; pretending to have done it
     * is not — seen from the permitted side, and it is the same case as apkcombo, where the rule is
     * "decode the query instead of following the redirect". The five-second countdown the page
     * shows is a `setTimeout` running **after** the link is already in the document: there is no
     * gesture to wait for.
     *
     * **Where the line would fall if this changed:** if the token became an HMAC with a server-side
     * key, computing it would require either extracting that key or running their JavaScript. The
     * first is forgery; for the second there are the WebView rungs of the escalation ladder. In
     * neither case would the right answer be a cleverer parser.
     */
    fun downloadToken(expiresAt: Instant): String {
        val seconds = expiresAt.epochSeconds.toString().toByteArray()
        return Base64.getEncoder().encodeToString(Base64.getEncoder().encode(seconds))
    }

    /** The file name from the already-normalised URL. */
    fun fileNameOf(url: String): String = Urls.fileNameOf(url, FALLBACK_FILE_NAME)

    /**
     * App or game, read from the **second** breadcrumb.
     *
     * `/apps` or `/games`, and they are the only two observed across thirty-one listings. The href
     * is used and not the label because the label is theme text; the href is the catalogue's
     * division.
     */
    fun contentKindOf(breadcrumbHref: String?): ContentKind =
        when (breadcrumbHref?.let { Urls.segments(it).firstOrNull() }) {
            GAMES_SEGMENT -> ContentKind.GAME
            APPS_SEGMENT -> ContentKind.APP
            else -> ContentKind.UNKNOWN
        }

    private const val FALLBACK_FILE_NAME = "liteapks.apk"
    private const val HTML_SUFFIX = ".html"
    private const val PATH_SEGMENTS = 1
    private const val DOWNLOAD_SEGMENT = "download"
    private const val DOWNLOAD_SEGMENTS = 2
    private const val APPS_SEGMENT = "apps"
    private const val GAMES_SEGMENT = "games"

    /** Observed slots go up to six; the cap is a defence, not a measurement. */
    private val SLOT_RANGE = 1..99

    private val SLUG = Regex("""[a-z0-9][a-z0-9-]*""")
    private val STEM = Regex("""[a-z0-9][a-z0-9-]*-\d+""")
    private val SLOT = Regex("""[a-z0-9][a-z0-9-]*-\d+/\d+""")
}
