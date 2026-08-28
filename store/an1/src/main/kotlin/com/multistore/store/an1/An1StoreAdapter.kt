package com.multistore.store.an1

import com.multistore.core.model.ContentKind
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.an1.parser.An1DetailParser
import com.multistore.store.an1.parser.An1DownloadParser
import com.multistore.store.an1.parser.An1SearchParser
import com.multistore.store.api.FilterCapability
import com.multistore.store.api.DownloadMode
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.HashAvailability
import com.multistore.store.api.NetworkTier
import com.multistore.store.api.PagedResult
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreCapabilities
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreMetadata
import com.multistore.store.api.StoreResult
import com.multistore.store.api.map
import com.multistore.store.common.html.PageFetcher
import com.multistore.store.common.storeCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The an1 adapter — **the store that never says which package it is, and publishes the hash
 * anyway**.
 *
 * ### The two new things it brings, and they are opposites
 *
 * **No `packageName`, anywhere on the site.** Not on this listing: on *none*. Eight pages sampled,
 * games and programs, modified and not, and in none of them a package name, a Google Play link or
 * a version code. The consequences are not theoretical and the adapter declares them rather than
 * hiding them:
 *
 *  - cross-store identity for an1 rests **only** on title and developer, so the matcher will never
 *    reach `0.85` on its own and the listing will land in the "possible match" section, where the
 *    user confirms. That is the intended behaviour: a wrong merge must be impossible by
 *    construction, not merely improbable;
 *  - step 4 of the pre-install pipeline — comparing the declared package with the APK's — **cannot
 *    be done**, and the UI must say "not contradicted", not "verified";
 *  - version selection answers "cannot be known" rather than "up to date". Without a version code
 *    that is the only true sentence.
 *
 * **But integrity is recoverable, and it is worth saying how far.** The file host is S3-compatible
 * object storage, and on some objects it publishes a checksum metadata header. It is not inferred
 * from the ETag — which there is multipart and **not** the content's MD5: the value was verified by
 * downloading 83,757,788 bytes of one APK and recomputing its SHA-256, which matches. Two of six
 * sampled files carry it, hence [HashAvailability.SOMETIMES].
 *
 * ### Why the hash comes from the download and not from the listing
 *
 * It sits in the header of a `HEAD` on the CDN, and making that `HEAD` requires having already
 * resolved the file's URL — i.e. having opened the download page. Putting it on the listing would
 * cost **two** extra requests on every open, for a value only the downloader needs. It therefore
 * lives in the download resolution, where the verification pipeline reads it.
 *
 * ### The rate-limit headers exist, and are not what they look like
 *
 * A response-driven rate limiter was planned, because an1 publishes rate-limit headers.
 * Re-measured, that requirement falls, and it is worth knowing why:
 *
 *  - the headers **do not exist on `an1.com`** — not on the root, not on search, not on the detail,
 *    not on the download page. They exist only on the file host, i.e. on the surface where we make
 *    **one** `HEAD` and **one** `GET` per download: the least talkative of all;
 *  - the counter **is not ours**. Three identical `HEAD`s in a row on the same object left the
 *    remaining count at 1355; three on another object saw it drop by three at a time while we made
 *    one. It is a shared budget that recharges (it went back *up* from 1346 to 1355 between two
 *    measurements). Reading it as "we have 1,346 requests left" would be false, and slowing our
 *    single user down because the world is downloading would be worse than useless.
 *
 * It is the same correction as apkmirror's HTTP/1.1: a real measurement generating a requirement,
 * and a more precise measurement removing it. The 429 with `Retry-After`, which is the real signal,
 * is already handled.
 */
@Singleton
class An1StoreAdapter @Inject constructor(
    private val config: An1Config,
    clients: StoreHttpClients,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.AN1,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private val searchParser = An1SearchParser(config)
    private val detailParser = An1DetailParser(config)
    private val downloadParser = An1DownloadParser(config)

    override val id: StoreId = StoreId.AN1

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared against what the pages actually contain.
     *
     * `providesScreenshots = false` is not a concession: an1 **publishes none**. Verified on a
     * program listing and on a game listing, where they would naturally be.
     *
     * `versionHistory = false` for the same reason: one listing, one file. There is no list of
     * previous versions anywhere.
     *
     * `supportsSplits = true` is instead declared caution: the fixtures serve `.apk` files, but an1
     * also distributes games with expansion data through a second hop, and the type is decided by
     * the file name's suffix. Declaring `false` would be a promise the first container would break
     * by handing splits to `PackageInstaller`.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        trending = false,
        recent = false,
        versionHistory = false,
        providesPackageName = false,
        providesRating = true,
        providesScreenshots = false,
        providesChangelog = false,
        providesHash = HashAvailability.SOMETIMES,
        providesSignerFingerprint = false,
        supportsSplits = true,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        // an1 does not label adult content: 33 categories across games and programs, none adult,
        // and the probe queries come back empty. Not declaring the capability is therefore the
        // exact answer — declaring it would promise a filter on a label this store does not
        // write.
        supportedFilters = emptySet(),
        // Census: the rating is on 10 rows out of 10. Not the kind, not the categories — an1 does
        // not publish those in results.
        clientFilters = setOf(FilterCapability.MIN_RATING),
        contentKinds = setOf(ContentKind.APP, ContentKind.GAME),
        listingTtl = config.listingTtl,
    )

    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (query.isBlank()) return@storeCall StoreResult.Success(PagedResult.empty(page))
        if (page < 0) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.searchUrl(query, page))) {
            is StoreResult.Success -> searchParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * This listing's page on an1, to open in a browser.
     *
     * The same address `getAppDetails` goes to read: here there is no difference between the page
     * we query and the one the user would open.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        An1Refs.stem(ref)?.let(config::listingUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> = storeCall {
        val stem = An1Refs.stem(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        when (val fetched = fetcher.get(config.listingUrl(stem))) {
            is StoreResult.Success -> detailParser.parse(fetched.value.html, fetched.value.url, ref)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * Two requests: the page carrying the link, and a `HEAD` on the file.
     *
     * The second is not a luxury and is the only opportunity an1 gives us. It returns three things
     * the listing does not have: the **final** URL after redirects, the exact `Content-Length` —
     * the listing writes `79.9Mb`, rounded to fifty thousand bytes — and, on some objects, the
     * checksum metadata header.
     *
     * If the `HEAD` fails the download **goes ahead anyway**, without a hash and without an
     * expected size. Giving up a reachable file because we could not read its metadata would make
     * working depend on an optional improvement.
     */
    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        val id = An1Refs.idOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)

        val page = when (val fetched = fetcher.get(config.downloadUrl(id))) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        val file = when (val parsed = downloadParser.parse(page.html, page.url)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        val head = (fetcher.head(file.url) as? StoreResult.Success)?.value?.takeIf { it.isSuccessful }

        StoreResult.Success(
            DownloadResolution.Direct(
                // The **final** URL: an1 redirects large apps onto a second CDN, and the redirect
                // renames the slug. Not even the second-to-last URL is the real one.
                url = head?.url ?: file.url,
                // The CDN asks for no Referer: the `HEAD` answers 200 with only the User-Agent.
                headers = emptyMap(),
                fileName = file.fileName,
                artifactType = file.artifactType,
                expectedSha256 = Sha256.parseOrNull(head?.header(CHECKSUM_HEADER)),
                expectedSize = head?.contentLength,
                // No signature in the URL and no expiry: the cache headers are long-lived. A
                // cached resolution stays valid.
                expiresAt = null,
            ),
        )
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    private companion object {
        /**
         * The S3 metadata header carrying the content's SHA-256.
         *
         * `x-amz-meta-*` is metadata **defined by whoever uploaded**, not computed by the service:
         * which is why it was verified against the real bytes rather than taken on trust. See the
         * note at the top of this class.
         */
        const val CHECKSUM_HEADER = "x-amz-meta-checksum-sha256"
    }
}
