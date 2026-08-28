package com.multistore.store.apkmirror

import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.ContentKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
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
import com.multistore.store.apkmirror.parser.ApkMirrorAppParser
import com.multistore.store.apkmirror.parser.ApkMirrorInterstitialParser
import com.multistore.store.apkmirror.parser.ApkMirrorReleaseParser
import com.multistore.store.apkmirror.parser.ApkMirrorFeedParser
import com.multistore.store.apkmirror.parser.ApkMirrorSearchParser
import com.multistore.store.apkmirror.parser.ApkMirrorVariant
import com.multistore.store.apkmirror.parser.ApkMirrorVariantDetail
import com.multistore.store.apkmirror.parser.ApkMirrorVariantParser
import com.multistore.store.common.html.PageFetcher
import com.multistore.store.common.html.Urls
import com.multistore.store.common.storeCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * The apkmirror adapter — after F-Droid, **the store with the best data of the nine**.
 *
 * For each artifact it publishes: `packageName`, `versionCode`, size to the byte, `minSdk`,
 * `targetSdk`, the file's SHA-256 and the signing certificate's SHA-256. Five of the seven
 * pre-install steps find their expected value here, which makes this adapter the most useful to
 * have early and the most expensive to have wrong.
 *
 * ### The two conditions without which it does not work at all
 *
 * 1. **The User-Agent.** `okhttp/4.12.0` gets `403` with a 153-byte body.
 * 2. **The rate limit.** apkmirror declares `Crawl-delay: 3` and enforces it: it answers **429**
 *    to too dense a run of probes. This adapter's network profile is the only one below one
 *    permit per second.
 *
 * A third condition seemed to exist and **does not**: that HTTP/1.1 was required. See the note
 * atop [ApkMirrorConfig] — that was a measurement taken with curl, and with OkHttp the opposite
 * holds.
 *
 * ### Why the listing costs three requests
 *
 * apkmirror's chain has three levels — app, release, variant — and the data that matters is
 * spread across all three:
 *
 * | Page | What it adds |
 * |---|---|
 * | `/apk/{dev}/{app}/` | title, developer, `packageName`, icon, screenshots, release list |
 * | `…/{release}/` | per variant: name, **`versionCode`**, type, ABI, `minSdk`, date |
 * | `…/{variant}/` | **file SHA-256**, **signer SHA-256**, size to the byte |
 *
 * Stopping at the second level would give a complete listing with no hash to verify the download
 * against, i.e. giving up the main reason this store is worth having. The three requests fit
 * inside the rate limiter's burst, so they go out together.
 *
 * **Only one variant is hydrated**, the one that will be offered: hydrating all nine would mean
 * nine requests to open a listing. What propagates and what does not has to be distinguished
 * carefully, because the first version of this comment got it wrong:
 *
 * - the **signer** belongs to the app and holds for every variant (the same certificate on bundle
 *   and single APK), so it goes into the listing's preferred signer;
 * - the **`versionCode` does not**: nine variants of one release can carry **three** different
 *   ones, because the publisher encodes the ABI into it. The release table reads it row by row,
 *   and it is never propagated;
 * - the **file's SHA-256** is by definition the file's, and stays only on the hydrated variant.
 */
@Singleton
class ApkMirrorStoreAdapter @Inject constructor(
    private val config: ApkMirrorConfig,
    clients: StoreHttpClients,
    /** Used to discard feed dates falling in the future. See `ApkMirrorFeedParser`. */
    private val clock: Clock = Clock.System,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.APKMIRROR,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
                pageCacheTtl = config.pageCacheTtl,
            ),
        ),
    )

    private val searchParser = ApkMirrorSearchParser(config)
    private val feedParser = ApkMirrorFeedParser(config)
    private val appParser = ApkMirrorAppParser(config)
    private val releaseParser = ApkMirrorReleaseParser(config)
    private val variantParser = ApkMirrorVariantParser(config)
    private val interstitialParser = ApkMirrorInterstitialParser(config)

    override val id: StoreId = StoreId.APKMIRROR

    override val metadata: StoreMetadata = config.metadata

    /**
     * Two declarations deserve explaining, because they look more timid than they should.
     *
     * **`providesPackageName = false`** even though apkmirror does publish the package: it writes
     * it on the **listing**, in the Play Store link, and **never in search results**. The
     * capability means "every listing I return has it", and search listings do not. Declaring it
     * true would fail the contract test, rightly.
     *
     * **`providesSignerFingerprint = false`** even though the signer is there, for the same
     * reason of degree: it lives on the individual variant's page, and the capability means "on
     * **every** version I list". The value still arrives where it really matters — on the
     * listing's preferred signer and on the hydrated version — i.e. at the point step 5 of the
     * pre-install pipeline reads.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        // The "Popular In Last 30 Days" widget exists and is readable, but ranks **releases** and
        // not apps: ten rows that are five apps, with one app appearing four times. To be worth
        // anything it would have to be deduplicated per app inside the adapter, and what it adds
        // to the other two measured charts is a single app another store already lists. We do not
        // declare what we do not serve.
        trending = false,
        // The feed carries ten entries with name, version, developer and date. It is the only one
        // of the four measured new-release sources that publishes the developer.
        recent = true,
        versionHistory = true,
        providesPackageName = false,
        providesRating = false,
        providesScreenshots = true,
        providesChangelog = false,
        // ALWAYS on single APKs, never on bundles: there is no single file to hash.
        providesHash = HashAvailability.SOMETIMES,
        providesSignerFingerprint = false,
        supportsSplits = true,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        supportedFilters = emptySet(),
        contentKinds = setOf(ContentKind.UNKNOWN),
        listingTtl = config.listingTtl,
    )

    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (query.isBlank()) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.searchUrl(query, page))) {
            is StoreResult.Success -> searchParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }


    /**
     * The most recently published releases.
     *
     * One request to the feed, and none to the listings: the entries already carry name, developer,
     * version and date. The window is narrow — ten entries, a few hours — and cannot be widened:
     * the feed accepts no parameters.
     */
    override suspend fun getRecent(page: Int): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (page > FIRST_PAGE) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.recentFeedUrl())) {
            is StoreResult.Success ->
                feedParser.parse(fetched.value.html, fetched.value.url, page, clock.now())
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * This listing's page on apkmirror, to open in a browser.
     *
     * The app's listing, not the release: the ref has two segments and the three-segment page is a
     * specific file, which is not what "open the original page" promises.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        ApkMirrorRefs.appPath(ref)?.let(config::appUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> = storeCall {
        val path = ApkMirrorRefs.appPath(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        val app = when (val fetched = fetcher.get(config.appUrl(path))) {
            is StoreResult.Success -> when (val parsed = appParser.parse(fetched.value.html, fetched.value.url, ref)) {
                is StoreResult.Success -> parsed.value
                is StoreResult.Failure -> return@storeCall parsed
                StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
            }
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        val latest = app.releases.firstOrNull() ?: return@storeCall StoreResult.Success(app.detail)

        // **A release-page failure propagates rather than being swallowed.** The first version
        // returned the listing with no versions, and the result was an apparently healthy screen
        // saying "no versions available" when the truth was "the store blocked us". The nightly
        // canary found it exactly that way: two red tests with the wrong message, because the real
        // error had been thrown away three calls earlier.
        val variants = when (val parsed = variantsOf(latest.path)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        if (variants.isEmpty()) return@storeCall StoreResult.Success(app.detail)

        val best = preferredVariant(variants)
        // Hydration stays tolerant, and the difference is in the consequences: without the release
        // page there is nothing to install, without the variant page the hash is missing — the
        // listing stays usable and pre-install verification will say "hash not verified" instead of
        // verified. Degrading is not failing.
        val hydrated = best?.let { variantDetail(it.path).getOrNull() }

        StoreResult.Success(
            app.detail.copy(
                summary = app.detail.summary.copy(
                    latestVersionName = best?.versionName ?: latest.label,
                    latestVersionCode = best?.versionCode ?: hydrated?.versionCode,
                    lastUpdated = latest.publishedAt,
                    packageName = app.detail.summary.packageName ?: hydrated?.packageName,
                ),
                versions = variants.map { variant ->
                    variant.toAppVersion(hydratedFor = if (variant == best) hydrated else null)
                },
                preferredSignerSha256 = hydrated?.signerSha256,
            ),
        )
    }

    /**
     * The installable versions: the variants of the most recent release.
     *
     * Not "history" in the sense of past releases — apkmirror lists ten on the listing and they
     * stay reachable — but the installable forms of what is being looked at, which is the set
     * version selection has to choose from. Exposing earlier releases too would need a distinction
     * between "release" and "variant" that the UI does not have: better not to have it than to
     * have it half-made.
     */
    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> =
        getAppDetails(ref).map { it.versions }

    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        // Each of the three branches can fail for a different reason — blocked, rate limited,
        // markup changed — and **none of these is "not found"**. The first version reduced them all
        // to `null` and therefore to not-found: the nightly canary spent a whole run saying
        // "network failure" while apkmirror was answering 429.
        val variantPath = when (val resolved = variantPathFor(ref, version)) {
            is StoreResult.Success -> resolved.value
            is StoreResult.Failure -> return@storeCall resolved
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        val variant = when (val parsed = variantDetail(variantPath)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        val interstitialUrl = variant.downloadUrl?.takeIf(::isOwnHost)
            ?: return@storeCall StoreResult.Failure(StoreError.NotFound)

        // The last hop really has to be opened: the interstitial's key and the download
        // endpoint's differ, so the final URL cannot be composed.
        val finalUrl = when (val fetched = fetcher.get(interstitialUrl, mapOf(REFERER to config.pageUrl(variantPath)))) {
            is StoreResult.Success -> when (val parsed = interstitialParser.parse(fetched.value.html, fetched.value.url)) {
                is StoreResult.Success -> parsed.value
                is StoreResult.Failure -> return@storeCall parsed
                StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
            }
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        if (!isOwnHost(finalUrl)) return@storeCall StoreResult.Failure(StoreError.NotFound)

        val fileName = Urls.fileNameOf(finalUrl, fallbackFileName(variant))
        StoreResult.Success(
            DownloadResolution.Direct(
                url = finalUrl,
                // The download endpoint demands the interstitial's Referer. The client's
                // interceptor sets the UA.
                headers = mapOf(REFERER to interstitialUrl),
                fileName = fileName,
                // The type is decided by the **resolved file's name**, not by the row we started
                // from. It is the only source that cannot be wrong, because it is the file itself
                // — and getting it wrong would mean handing a split container to
                // `PackageInstaller`.
                artifactType = artifactTypeOf(fileName),
                expectedSha256 = variant.fileSha256,
                expectedSize = variant.sizeBytes,
            ),
        )
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    // --- intermediate hops ---------------------------------------------------------------

    private suspend fun variantsOf(releasePath: String): StoreResult<List<ApkMirrorVariant>> =
        when (val fetched = fetcher.get(config.pageUrl(releasePath))) {
            is StoreResult.Success -> releaseParser.parse(fetched.value.html, fetched.value.url, releasePath)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }

    private suspend fun variantDetail(variantPath: String): StoreResult<ApkMirrorVariantDetail> =
        when (val fetched = fetcher.get(config.pageUrl(variantPath))) {
            is StoreResult.Success -> variantParser.parse(fetched.value.html, fetched.value.url)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }

    /**
     * The path of the variant to download, whatever the caller is holding.
     *
     * Three cases: a variant reference is already the answer; a release reference requires opening
     * its table and choosing; no reference requires starting from the listing. It returns a result
     * and not a nullable string because the ways of not reaching a variant are four and only one
     * of them is "it does not exist".
     */
    private suspend fun variantPathFor(ref: StoreAppRef, version: VersionRef?): StoreResult<String> {
        if (version != null && ApkMirrorRefs.isVariant(version)) {
            return ApkMirrorRefs.versionPath(version)?.let { StoreResult.Success(it) }
                ?: StoreResult.Failure(StoreError.NotFound)
        }
        val releasePath = when {
            version != null -> ApkMirrorRefs.versionPath(version)
                ?: return StoreResult.Failure(StoreError.NotFound)
            else -> when (val latest = latestReleasePath(ref)) {
                is StoreResult.Success -> latest.value
                is StoreResult.Failure -> return latest
                StoreResult.Unsupported -> return StoreResult.Unsupported
            }
        }
        return when (val variants = variantsOf(releasePath)) {
            is StoreResult.Success -> preferredVariant(variants.value)?.path
                ?.let { StoreResult.Success(it) }
                ?: StoreResult.Failure(StoreError.NotFound)
            is StoreResult.Failure -> variants
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    private suspend fun latestReleasePath(ref: StoreAppRef): StoreResult<String> {
        val path = ApkMirrorRefs.appPath(ref) ?: return StoreResult.Failure(StoreError.NotFound)
        val page = when (val fetched = fetcher.get(config.appUrl(path))) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return fetched
            StoreResult.Unsupported -> return StoreResult.Unsupported
        }
        return when (val parsed = appParser.parse(page.html, page.url, ref)) {
            is StoreResult.Success -> parsed.value.releases.firstOrNull()?.path
                ?.let { StoreResult.Success(it) }
                ?: StoreResult.Failure(StoreError.NotFound)
            is StoreResult.Failure -> parsed
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * Which variant to offer when the user has not chosen one.
     *
     * The first single APK, and only failing that the first bundle. The reason is that a bundle
     * does not install with `PackageInstaller` as it is: offering it first would give a download
     * that arrives and then cannot be installed, which is worse than one that never starts. The
     * fine choice — ABI, dpi — is made by version selection with the device profile, which the
     * adapter does not know and must not.
     */
    private fun preferredVariant(variants: List<ApkMirrorVariant>): ApkMirrorVariant? =
        variants.firstOrNull { it.artifactType == ArtifactType.APK } ?: variants.firstOrNull()

    private fun artifactTypeOf(fileName: String): ArtifactType = when {
        fileName.endsWith(APKM_SUFFIX, ignoreCase = true) -> ArtifactType.APKM
        else -> ArtifactType.APK
    }

    /**
     * A URL that is not apkmirror's in the middle of the chain is a hijack, not a mirror.
     *
     * The comparison is with [ApkMirrorConfig.baseUrl]'s host and not with a constant: the base URL
     * is what the signed document can move if the store changes domain, and a check anchored to a
     * constant would block exactly the migration that mechanism exists to allow.
     */
    private fun isOwnHost(url: String): Boolean {
        val expected = runCatching { java.net.URI(config.baseUrl).host }.getOrNull() ?: return false
        val actual = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return actual == expected || actual.endsWith(".$expected")
    }

    private fun fallbackFileName(variant: ApkMirrorVariantDetail): String {
        val pkg = variant.packageName ?: FALLBACK_NAME
        val code = variant.versionCode?.toString() ?: variant.versionName.orEmpty()
        return listOf(pkg, code).filter { it.isNotBlank() }.joinToString("_") + APK_SUFFIX
    }

    private fun ApkMirrorVariant.toAppVersion(hydratedFor: ApkMirrorVariantDetail?): AppVersion =
        AppVersion(
            versionName = versionName,
            versionCode = versionCode ?: hydratedFor?.versionCode,
            ref = ApkMirrorRefs.variantRef(path),
            artifactType = artifactType,
            sizeBytes = hydratedFor?.sizeBytes,
            minSdk = minSdk ?: hydratedFor?.minSdk,
            targetSdk = hydratedFor?.targetSdk,
            abis = abis,
            sha256 = hydratedFor?.fileSha256,
            signerSha256 = hydratedFor?.signerSha256,
            publishedAt = publishedAt,
        )

    private companion object {
        /** apkmirror paginates search but not the feed: that is a single window. */
        const val FIRST_PAGE = 0
        const val REFERER = "Referer"
        const val APK_SUFFIX = ".apk"
        const val APKM_SUFFIX = ".apkm"
        const val FALLBACK_NAME = "apkmirror"
    }
}
