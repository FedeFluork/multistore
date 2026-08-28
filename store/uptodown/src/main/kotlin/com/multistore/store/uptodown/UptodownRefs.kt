package com.multistore.store.uptodown

import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.store.common.html.Urls

/**
 * How uptodown identifies an app and a version.
 *
 * The app **is a subdomain**: `https://telegram.en.uptodown.com/android`. The ref is therefore just
 * the slug — `telegram` — and [UptodownConfig.appUrl] recomposes the URL. It is the only store
 * where identity does not live in the path, and the practical consequence is that the URL template
 * must be configurable: a fake local server cannot have subdomains.
 *
 * The version is the `data-version-id`, an integer uptodown assigns to the **file**, not the
 * release: `1195732851` for 12.9.2, `1191373665` for 12.9.1. It is exactly what
 * `UNIQUE(listing_id, version_ref)` needs, and the same figure reappears as `data-file-id` in the
 * download button on its page — that is, the list and the page confirm each other.
 */
internal class UptodownRefs(private val config: UptodownConfig) {

    /**
     * From a listing URL to the ref: `https://telegram.en.uptodown.com/android` -> `telegram`.
     *
     * Recognition goes through [UptodownConfig.appHostSuffix] and not through the URL template,
     * because the fixtures contain uptodown's real URLs even when the adapter is pointed at a
     * local server. The path must be **exactly** `/android`: without that constraint,
     * `https://en.uptodown.com/android/search?query=x` would become an app named "en".
     */
    fun refFromUrl(url: String): StoreAppRef? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        if (!host.endsWith(config.appHostSuffix)) return null
        val slug = host.removeSuffix(config.appHostSuffix)
        if (!SLUG.matches(slug)) return null
        if (Urls.segments(url) != listOf(UptodownConfig.PLATFORM)) return null
        return StoreAppRef(slug)
    }

    /**
     * The usable slug, or `null`.
     *
     * The contract test hands every adapter a ref like `../../etc/passwd?<script>&%00`: without
     * this validation it would end up inside a **hostname**, which is worse than inside a path.
     */
    fun slugOf(ref: StoreAppRef): String? = ref.value.trim().lowercase().takeIf { SLUG.matches(it) }

    private companion object {
        /**
         * A DNS label: letters, digits and hyphens, no dots.
         *
         * The dot is excluded on purpose and not out of pedantry: a slug with a dot would add a
         * subdomain level, and `evil.example.com` as a ref would produce a request to
         * `evil.example.com.en.uptodown.com` — or, with a domain registered for the purpose,
         * elsewhere.
         */
        val SLUG = Regex("""[a-z0-9][a-z0-9-]{0,62}""")
    }
}

/** A version's ref: the `data-version-id`, which identifies **a file**. */
internal fun versionRefOf(versionId: String): VersionRef = VersionRef(versionId)

/** The numeric id inside a [VersionRef], or `null` if it does not have that shape. */
internal fun versionIdOf(ref: VersionRef?): String? =
    ref?.value?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
