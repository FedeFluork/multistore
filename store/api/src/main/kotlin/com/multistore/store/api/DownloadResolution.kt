package com.multistore.store.api

import com.multistore.core.model.ArtifactType
import com.multistore.core.model.Sha256
import kotlin.time.Instant

/**
 * How the file is actually reached, once the adapter has resolved the chain of hops.
 *
 * This is where the difference between the nine stores stops being visible to the rest of the
 * app: whether behind it lies a direct F-Droid URL or four an1 redirects, the downloader receives
 * the same thing.
 */
sealed interface DownloadResolution {

    /** The adapter resolved a programmatically downloadable URL. */
    data class Direct(
        val url: String,
        /** Referer, Cookie, UA: what *that* server answers 403 without. */
        val headers: Map<String, String>,
        val fileName: String,
        val artifactType: ArtifactType,
        val expectedSha256: Sha256?,
        /**
         * The expected size **to the byte**, or `null`.
         *
         * Not an approximate size: the downloader compares this number with the bytes received
         * and, finding fewer, concludes the connection dropped. A store publishing only a rounded
         * value — apkcombo writes `119 MB` for 124,351,530 bytes — must leave this `null` and put
         * the approximation in `AppVersion.sizeBytes`, which is for display and not verification.
         */
        val expectedSize: Long?,
        /**
         * When the URL stops being valid.
         *
         * Many stores sign the link with an expiring token: a resolved URL put in a cache comes
         * back 403 after a few minutes. Whoever caches it has to know.
         */
        val expiresAt: Instant? = null,
    ) : DownloadResolution

    /**
     * The store requires a human gesture: the download goes through `:feature:webviewdownload`.
     *
     * Actually executing what the site asks is allowed; pretending to have done so is not. This
     * case *is* the permitted form: the real page, the user's real tap, and then the identical
     * verification and installation flow as [Direct].
     */
    data class UserAssisted(
        val pageUrl: String,
        val hint: DownloadHint,
        val headers: Map<String, String> = emptyMap(),
        val expectedSha256: Sha256? = null,
        val expectedSize: Long? = null,
    ) : DownloadResolution
}

/**
 * What the user has to do on the page.
 *
 * An enum and not a string because `:store:api` is pure Kotlin and has no access to
 * `strings.xml`: the translation into the 5 languages lives in `:feature:webviewdownload`, which
 * maps each value onto a key. A text here would be a hardcoded string in a single language.
 */
enum class DownloadHint {
    TAP_DOWNLOAD_BUTTON,
    SOLVE_CAPTCHA,
    WAIT_FOR_COUNTDOWN,
    CHOOSE_A_MIRROR,
    ACCEPT_TERMS,
}
