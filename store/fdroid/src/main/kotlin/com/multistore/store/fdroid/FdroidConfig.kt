package com.multistore.store.fdroid

import com.multistore.core.model.Sha256
import com.multistore.core.model.Sha256Serializer
import com.multistore.store.api.StoreMetadata
import kotlinx.serialization.Serializable

/**
 * The F-Droid adapter's compiled defaults.
 *
 * The compiled defaults must always exist and stay current: the remote config is an override, never
 * the only source. These values were **measured** on 23/08/2026 against `f-droid.org` from an
 * Italian consumer IP, not deduced from the documentation.
 *
 * ### `@Serializable` arrived late, and its absence was invisible
 *
 * The "adding a new store" checklist asks for it. This class, written before there was a second
 * adapter, did not have it — and nothing could say so: the code compiled, the tests passed, the
 * adapter worked. Only the remote override channel noticed, which genuinely needs a serializer, and
 * **only at runtime**, because the API's first version resolved the serializer reflectively. Hence
 * the current shape of `RemoteParsers.override`, which demands an explicit `KSerializer`: the same
 * omission today does not compile.
 */
@Serializable
data class FdroidConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val repoPath: String = DEFAULT_REPO_PATH,
    /**
     * The certificate the repository signs the index with.
     *
     * Verified end to end: `entry.jar` -> digest in the MANIFEST -> `CIARANG.RSA` block ->
     * self-signed certificate `CN=Ciaran Gultnieks`, whose SHA-256 is this.
     *
     * The certificate is **self-signed and SHA1withRSA-signed**, valid from 2010 to 2037: PKIX
     * validation fails, and that is the expected outcome, because no CA ever issued it. The trust
     * rests entirely on the comparison with the value below — if one day the pin does not match, the
     * index must be **discarded**, not accepted "with a warning".
     */
    @Serializable(with = Sha256Serializer::class)
    val signerFingerprint: Sha256 = PINNED_SIGNER,
    val userAgent: String = DEFAULT_USER_AGENT,
    val permitsPerSecond: Double = DEFAULT_PERMITS_PER_SECOND,
    val burst: Int = DEFAULT_BURST,
    /**
     * The "App Search API", which lives on a host of **its own**.
     *
     * It is not under `f-droid.org/api/v1/`: looking for it there leads to a 404 and to the wrong
     * conclusion that it does not exist. It is a field of its own also because a separate service
     * can fall over on its own, and because that way the remote config can move it without touching
     * the repo.
     */
    val searchApiUrl: String = DEFAULT_SEARCH_API_URL,
    /** Mirrors to try if the primary host does not answer. HTTPS only: the .onions are for Tor. */
    val mirrors: List<String> = DEFAULT_MIRRORS,
) {
    val repoUrl: String get() = baseUrl.trimEnd('/') + repoPath

    fun repoFile(path: String): String = repoUrl + "/" + path.trimStart('/')

    val entryJarUrl: String get() = repoFile("entry.jar")

    val signerIndexUrl: String get() = repoFile("signer-index.json")

    fun packageApiUrl(packageName: String): String =
        baseUrl.trimEnd('/') + "/api/v1/packages/" + packageName

    /**
     * A package's web page, which on this store is not used to read anything.
     *
     * F-Droid is a local-index store: listing, versions and categories are already in Room, and no
     * request ever goes through here. It exists for the "open the original page" button alone — to
     * the user that page is the store's listing as on the other eight, even though to the adapter it
     * is not a source.
     */
    fun webListingUrl(packageName: String): String =
        baseUrl.trimEnd('/') + "/packages/" + packageName + "/"

    val metadata: StoreMetadata
        get() = StoreMetadata(
            displayName = DISPLAY_NAME,
            baseUrl = baseUrl,
            // F-Droid listings are written in American English: it is the index's dominant key
            // (12,634 occurrences of `en-US` against 42 of `en`).
            listingLanguage = "en-US",
            host = HOST,
        )

    companion object {
        const val DISPLAY_NAME: String = "F-Droid"
        const val HOST: String = "f-droid.org"
        const val DEFAULT_BASE_URL: String = "https://f-droid.org"
        const val DEFAULT_REPO_PATH: String = "/repo"

        val PINNED_SIGNER: Sha256 = requireNotNull(
            Sha256.parseOrNull("43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"),
        ) { "F-Droid's pinned fingerprint is not a valid SHA-256" }

        /**
         * An honest UA: we say who we are.
         *
         * F-Droid serves the index and the APKs **even with an empty UA** — verified — so there is
         * nothing to circumvent here and no reason to dress up as a browser. The field stays
         * mandatory because on other stores it is anything but optional.
         */
        const val DEFAULT_USER_AGENT: String = "MultiStore/1.0 (Android; +https://f-droid.org)"

        /**
         * No `Crawl-delay` in the robots.txt and no rate limit observed: 2 requests per second stay
         * comfortably below what the official client does.
         */
        const val DEFAULT_PERMITS_PER_SECOND: Double = 2.0
        const val DEFAULT_BURST: Int = 5

        const val DEFAULT_SEARCH_API_URL: String = "https://search.f-droid.org/api/search_apps"

        val DEFAULT_MIRRORS: List<String> = listOf(
            "https://f-droid.org/repo",
        )
    }
}

/** Shared values that do not belong to a single store. */
object FdroidPaths {
    const val ENTRY_JSON_ENTRY: String = "entry.json"
}
