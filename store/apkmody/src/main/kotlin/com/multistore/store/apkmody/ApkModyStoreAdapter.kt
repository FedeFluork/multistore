package com.multistore.store.apkmody

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
import com.multistore.store.apkmody.parser.ApkModyDetailParser
import com.multistore.store.apkmody.parser.ApkModyDownloadParser
import com.multistore.store.apkmody.parser.ApkModyHistory
import com.multistore.store.apkmody.parser.ApkModyHistoryParser
import com.multistore.store.apkmody.parser.ApkModyPopularParser
import com.multistore.store.apkmody.parser.ApkModySearchParser
import com.multistore.store.common.html.PageFetcher
import com.multistore.store.common.storeCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The apkmody adapter — **the first store redistributing modified APKs**, and that fact is the only
 * genuinely new thing it brings.
 *
 * ### What changes when the file is not the developer's
 *
 * For sources redistributing modified APKs there is no original developer signature to compare
 * against: the pipeline protects against package substitution, not against tampering upstream.
 * Here that limitation stops being theoretical, and it is worth walking through:
 *
 *  - **hash** — not published: verification will say "not compared";
 *  - **signer** — not published, and comparing it with the original would make no sense: a modified
 *    build is signed by whoever modified it. On an **already installed** app the comparison with
 *    the installed signer remains and works, and that is what stops an official installation from
 *    silently becoming a modified one;
 *  - **`packageName`** — published, and it remains the control that cannot be switched off.
 *
 * The honest result is that apkmody is verifiable *as far as the store declares*, and the
 * verification card has to say so: package confirmed, hash not compared, signature with nothing to
 * compare against.
 *
 * ### Why the listing costs two requests
 *
 * The listing publishes everything except the version code, and the history publishes the version
 * code (inside the file name) but not the description. Both are needed, and they are the same two
 * that version selection and the anti-downgrade rule require.
 */
@Singleton
class ApkModyStoreAdapter @Inject constructor(
    private val config: ApkModyConfig,
    clients: StoreHttpClients,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.APKMODY,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private val searchParser = ApkModySearchParser(config)
    private val popularParser = ApkModyPopularParser(config)
    private val detailParser = ApkModyDetailParser(config)
    private val downloadParser = ApkModyDownloadParser(config)
    private val historyParser = ApkModyHistoryParser(config, downloadParser)

    override val id: StoreId = StoreId.APKMODY

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared against what the pages actually contain, on 24/08/2026.
     *
     * `providesPackageName = false` is the costliest declaration and needs explaining: the
     * **listing** always publishes the package, the **search results** never do. The capability
     * speaks of what the UI can count on everywhere, and a list of twenty results without a package
     * is exactly the case where cross-store identity cannot rest on it.
     *
     * `providesRating = false` is not a concession: the listing's stars read four out of five on
     * **every app measured**, i.e. decoration.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        // A chart page with twelve entries carrying their position in the structured data. It is
        // one of only two readable charts among the nine stores — see `ApkModyPopularParser`.
        trending = true,
        // None: the feed path answers **404** and the homepage has no new-releases section that
        // is not editorial. Declaring it true and returning Unsupported would fail the contract
        // test, rightly.
        recent = false,
        versionHistory = true,
        providesPackageName = false,
        providesRating = false,
        providesScreenshots = true,
        providesChangelog = false,
        providesHash = HashAvailability.NONE,
        providesSignerFingerprint = false,
        // On the observed pages apkmody serves only `.apk`, but its installation guide names
        // split-container formats: the type is read from the file name, and declaring `false`
        // would be a promise the first container would break.
        supportsSplits = true,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        supportedFilters = emptySet(),
        // Census of 26/08/2026 on the search fixture: 20 rows out of 20 declare "App" or "Game",
        // and it is the only one of the nine to do so in a list. We apply the filter; the store
        // cannot filter anything.
        clientFilters = setOf(FilterCapability.CONTENT_KIND),
        contentKinds = setOf(ContentKind.APP, ContentKind.GAME),
        listingTtl = config.listingTtl,
    )

    /**
     * Search. **Pages after the first are empty by construction.**
     *
     * Measured: a page parameter returns the same bytes as the first page, and the path form
     * answers 404. An empty page costs zero requests and tells the truth; returning the first
     * twenty results again would give an infinite scroll.
     */
    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (query.isBlank()) return@storeCall StoreResult.Success(PagedResult.empty(page))
        if (page > FIRST_PAGE) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.searchUrl(query))) {
            is StoreResult.Success -> searchParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }


    /**
     * The chart of most-downloaded apps.
     *
     * One page and no pagination: a page parameter returns the same bytes. The entries carry title
     * and ref, not the icon — the structured data does not declare it, and taking it from the cards
     * would mean reading the same page twice under two criteria, i.e. the possibility that the two
     * lists disagree.
     */
    override suspend fun getTrending(page: Int): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (page > FIRST_PAGE) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.popularUrl())) {
            is StoreResult.Success -> popularParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * This listing's page on apkmody, to open in a browser.
     *
     * The same address `getAppDetails` goes to read.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        ApkModyRefs.appPath(ref)?.let(config::listingUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> = storeCall {
        val path = ApkModyRefs.appPath(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)

        val page = when (val fetched = fetcher.get(config.listingUrl(path))) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        val detail = when (val parsed = detailParser.parse(page.html, page.url, ref)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        // A second request, and a failure **propagates**. Returning the listing with no versions
        // would say "this store publishes nothing installable" when the truth may be "we were
        // blocked" or "the markup changed": three different diagnoses, and the user needs theirs.
        val history = when (val parsed = history(path)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        // **The page contradicts itself about the package.** The listing declares one in its
        // table; the file on the CDN lives under a path containing another. If the two disagree,
        // the file this page offers is not the app's it describes — exactly the substitution the
        // hard block at step 4 of the pipeline exists for. That block would fire anyway, but
        // **after** the download: here it costs nothing and arrives first.
        val declared = detail.summary.packageName
        val fileOwner = history.latest?.packageName
        if (declared != null && fileOwner != null && declared != fileOwner) {
            return@storeCall StoreResult.Failure(StoreError.NotFound)
        }

        StoreResult.Success(
            detail.copy(
                summary = detail.summary.copy(
                    packageName = declared ?: fileOwner,
                    latestVersionCode = history.latest?.versionCode,
                ),
                versions = versionsOf(history),
            ),
        )
    }

    /**
     * The history, which is also the only version list apkmody publishes.
     *
     * The rows carry name, date and size but **not** the version code: that lives in the file name,
     * and the file name is only visible by opening the version's page. The current row is the
     * exception, because its file is linked at the top of the same page.
     */
    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> = storeCall {
        val path = ApkModyRefs.appPath(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        history(path).map(::versionsOf)
    }

    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        val path = ApkModyRefs.appPath(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        val segment = ApkModyRefs.versionSegment(version)

        val file = when (val parsed = fileAt(path, segment)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        StoreResult.Success(
            DownloadResolution.Direct(
                url = file.url,
                // The file sits on a CDN, not on apkmody: verified with a HEAD answering 200 with
                // only the User-Agent. Sending it the site's Referer would achieve nothing and
                // widen the surface.
                headers = emptyMap(),
                fileName = file.fileName,
                artifactType = file.artifactType,
                // No hash published, anywhere on the site.
                expectedSha256 = null,
                // **No expected size**, and that is not laziness: apkmody writes a figure rounded
                // to two binary decimals, i.e. 158,314,004 bytes against the 158,310,989 the CDN
                // actually delivers. Three thousand bytes of discrepancy are enough for the
                // download engine to declare a finished connection interrupted — it happened on
                // apkcombo, and the diagnosis was "no connection" in front of a complete file. The
                // approximate size stays on the version, for display rather than verification.
                expectedSize = null,
                // The CDN URL is not signed and does not expire: no expiry parameter, no session.
                // A cached resolution stays valid.
                expiresAt = null,
            ),
        )
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    private suspend fun history(path: String): StoreResult<ApkModyHistory> =
        when (val fetched = fetcher.get(config.historyUrl(path))) {
            is StoreResult.Success -> historyParser.parse(fetched.value.html, fetched.value.url)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }

    private suspend fun fileAt(path: String, segment: String) =
        when (val fetched = fetcher.get(config.versionUrl(path, segment))) {
            is StoreResult.Success -> downloadParser.parse(fetched.value.html, fetched.value.url)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }

    /**
     * The history rows as versions, with the current file attached to its own.
     *
     * The attachment is by version name because that is the only field the two halves share: the
     * row writes the version, the file name contains it. Only that row gets a version code, and
     * that is fine: it is the version an update decision is made on, and the others get theirs when
     * resolved.
     *
     * If the history is empty but the current file exists — not observed, but a just-published app
     * could be in that state — a single version remains, the one the button offers.
     */
    private fun versionsOf(history: ApkModyHistory): List<AppVersion> {
        val latest = history.latest
        if (history.entries.isEmpty()) {
            return latest?.let {
                listOf(
                    AppVersion(
                        versionName = it.versionName ?: it.fileName,
                        versionCode = it.versionCode,
                        ref = ApkModyRefs.versionRef(ApkModyConfig.DOWNLOAD_SEGMENT),
                        artifactType = it.artifactType,
                    ),
                )
            }.orEmpty()
        }
        return history.entries.map { entry ->
            val isLatest = latest?.versionName == entry.versionName
            AppVersion(
                versionName = entry.versionName,
                versionCode = latest?.versionCode?.takeIf { isLatest },
                ref = ApkModyRefs.versionRef(entry.segment),
                artifactType = if (isLatest) latest.artifactType else ArtifactType.APK,
                sizeBytes = entry.sizeBytes,
                publishedAt = entry.publishedAt,
            )
        }
    }

    private companion object {
        const val FIRST_PAGE = 0
    }
}
