package com.multistore.store.apkcombo

import com.multistore.core.model.AppVersion
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
import com.multistore.store.apkcombo.parser.ApkComboDetailParser
import com.multistore.store.apkcombo.parser.ApkComboDownloadParser
import com.multistore.store.apkcombo.parser.ApkComboFeedParser
import com.multistore.store.apkcombo.parser.ApkComboSearchParser
import com.multistore.store.apkcombo.parser.ApkComboVariant
import com.multistore.store.apkcombo.parser.ApkComboVersionsParser
import com.multistore.store.common.html.PageFetcher
import com.multistore.store.common.storeCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * The apkcombo adapter — **the second implementer of `StoreAdapter`**, which is its main job.
 *
 * Adding a store must require no change to the core; if it does, the contract is incomplete. With
 * a single implementer that was a hope. This module tests it against a store resembling F-Droid in
 * nothing: no index, no signature, no hash, HTML that can change tomorrow.
 *
 * ### The three differences from F-Droid, and what follows
 *
 * - **No published hash.** Step 2 of the pre-install pipeline has nothing to compare against and
 *   the result reads "hash not verified". The step that remains and is not negotiable is the
 *   **`packageName` match**, and here apkcombo is in a good position: the package is in the URL
 *   *and* in the information table, and the parser discards the listing if the two disagree.
 * - **Search does not paginate.** Measured: `?page=2` returns the same twenty results as page 1.
 *   The adapter declares no further pages, and later pages come back empty — see [search].
 * - **The listing costs two requests.** The detail and the list of installable variants are two
 *   different pages, and version selection needs the second: without version code, size and ABI it
 *   cannot choose anything.
 */
@Singleton
class ApkComboStoreAdapter @Inject constructor(
    private val config: ApkComboConfig,
    clients: StoreHttpClients,
    /**
     * Serves one question: **is this date in the future?**
     *
     * A feed's `<pubDate>` is written by the publisher and verified by nobody — on pdalife 5
     * entries out of 100 are dated as far ahead as 2029. It has never happened here, but the check
     * belongs to the format rather than the store, and an injected clock is the only way to test it.
     */
    private val clock: Clock = Clock.System,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.APKCOMBO,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private val searchParser = ApkComboSearchParser(config)
    private val detailParser = ApkComboDetailParser(config)
    private val downloadParser = ApkComboDownloadParser(config)
    private val versionsParser = ApkComboVersionsParser(config)
    private val feedParser = ApkComboFeedParser(config)

    override val id: StoreId = StoreId.APKCOMBO

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared against what the pages actually contain, on 24/08/2026.
     *
     * `providesRating = true` with an asymmetry worth knowing: the rating appears **only** in
     * search results, not on the listing. Declaring it `false` would hide a datum the store
     * publishes; declaring it `true` means the listing may lack it, and the UI must already handle
     * a null rating.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        // The site's charts **exist as headings and not as content**: those pages answer 200 and
        // contain not one link to a listing, because JavaScript writes the list. See
        // `ApkComboFeedParser`.
        trending = false,
        // The plan pointed at the sitemaps; the measurement found better, and the site declares it
        // itself: a latest-updates feed, 98 entries, with the `packageName` inside each URL.
        recent = true,
        versionHistory = true,
        providesPackageName = true,
        providesRating = true,
        providesScreenshots = true,
        providesChangelog = false,
        providesHash = HashAvailability.NONE,
        providesSignerFingerprint = false,
        // A quarter of one app's variants are XAPK: split containers, not single APKs.
        supportsSplits = true,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        supportedFilters = emptySet(),
        // Census of 26/08/2026: the category is on 20 rows out of 20, **the rating on 19**. That
        // nineteenth is why the criterion is "always" and not "almost": declaring MIN_RATING would
        // make a rating-filtered search discard it without having judged it, and no line of the
        // screen would say so.
        clientFilters = setOf(FilterCapability.CATEGORY),
        contentKinds = setOf(ContentKind.APP, ContentKind.GAME, ContentKind.UNKNOWN),
        listingTtl = config.listingTtl,
    )

    /**
     * Search. **Page 1 and later are empty by construction.**
     *
     * Not a limitation of the adapter but of the store, measured on 24/08/2026: `?page=2` and
     * `?page=3` return the same twenty results as the first page. Asking for them and returning
     * them would produce an infinite scroll repeating the same apps; returning them empty costs
     * **zero requests** and tells the truth.
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
     * The apps that have just published a new version.
     *
     * One request, to the RSS feed, and no listing pages: the entries already carry title, ref and
     * `packageName`. It is the cheapest of the three measured sources — 83 KB for 98 apps — and the
     * only one that does not cost a request per row.
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
     * This listing's page on apkcombo, to open in a browser.
     *
     * The same address `getAppDetails` goes to read.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        ApkComboRefs.pathOf(ref)?.let(config::listingUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> = storeCall {
        val path = ApkComboRefs.pathOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
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

        // A second request, and it is needed: version selection chooses on version code, ABI and
        // minimum SDK, and the listing publishes none of the three.
        //
        // **A failure here propagates.** The first version swallowed it and returned the listing
        // with no versions, i.e. a screen saying "no versions" when the truth might be "the store
        // blocked us" or "the markup changed". Those lead to different jobs, and the user deserves
        // the first, not the third.
        val variantsPage = when (
            val fetched = fetcher.get(config.downloadUrl(path, ApkComboConfig.LATEST_VERSION_SEGMENT))
        ) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        val variants = when (
            val parsed = downloadParser.parse(variantsPage.html, variantsPage.url, detail.summary.title)
        ) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        val versions = if (variants.isNotEmpty()) {
            variants.map { it.toAppVersion(LATEST) }
        } else {
            versionsOnly(variantsPage)
        }
        StoreResult.Success(detail.copy(versions = versions))
    }

    /**
     * The versions a variants page names when it offers **no file**.
     *
     * On some apps the latest-version segment publishes no downloadable variant at all — measured on
     * `com.iMe.android`, whose `/download/apk` has zero `/r2?` links — and the listing used to arrive
     * with an empty version list. The screen then said "this store publishes no installable package
     * for this app", which is a dead end with nothing saying why: opening the version-history section
     * made an Install button appear, because on that store the files live **only** under the
     * per-version segments.
     *
     * It costs **no extra request**, and that is the reason it reads this page rather than
     * `/old-versions/`: the page that just proved there is nothing to install carries the version
     * list itself, in the same `ul.list-versions a.ver-item` markup — three rows on the measured app
     * against that page's thirty-one. Fetching the longer list here would buy 28 rows nobody has
     * asked for; the history section fetches them when it is opened.
     *
     * A failure to parse it is **not** propagated, and that asymmetry is deliberate: the variants
     * page has already been read successfully, so what is being answered here is "are there older
     * releases named on it?". "No" is a legitimate answer — plenty of apps have exactly one release —
     * and turning it into a store error would make a fault out of an app's ordinary shape.
     */
    private fun versionsOnly(page: PageFetcher.Page): List<AppVersion> =
        when (val parsed = versionsParser.parse(page.html, page.url)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure, StoreResult.Unsupported -> emptyList()
        }

    /**
     * The older releases.
     *
     * They carry **only** name and date: apkcombo publishes neither version code nor size on this
     * page. They stay useful because each has its own version ref, and resolving one's download
     * opens its variants page, which has everything. The list is a menu; the resolution is what
     * counts.
     */
    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> = storeCall {
        val path = ApkComboRefs.pathOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        when (val fetched = fetcher.get(config.oldVersionsUrl(path))) {
            is StoreResult.Success -> versionsParser.parse(fetched.value.html, fetched.value.url)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        val path = ApkComboRefs.pathOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        val segment = ApkComboRefs.pageSegmentOf(version)
        val wanted = ApkComboRefs.objectKeyOf(version)

        val variants = when (val parsed = variantsAt(path, segment, appTitle = null)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        val chosen = variants.firstOrNull { wanted != null && it.objectKey == wanted }
            ?: variants.firstOrNull { it.recommended }
            ?: variants.firstOrNull()
            ?: return@storeCall StoreResult.Failure(StoreError.NotFound)

        StoreResult.Success(
            DownloadResolution.Direct(
                url = chosen.url,
                // The signed URL points at Cloudflare R2, not at apkcombo: sending it the site's
                // Referer achieves nothing and widens the surface. The client's interceptor
                // already sets the UA.
                headers = emptyMap(),
                fileName = chosen.fileName,
                artifactType = chosen.artifactType,
                // apkcombo publishes no hash. Declaring it null here is what makes the
                // verification card say "hash not verified" instead of implying a comparison that
                // never happened.
                expectedSha256 = null,
                // **Nor the size**, and finding that out cost a whole download. apkcombo writes
                // `119 MB`, rounded to the megabyte: 124,780,544 bytes against a real
                // 124,351,530. The download engine compares the **exact** size and, finding
                // fewer, rightly concludes the connection dropped — the finished file was
                // declared incomplete and the user read "no connection" in front of 125 MB
                // downloaded.
                //
                // The approximate size stays where it does no harm: on the version, which the
                // listing shows before the transfer starts. An inexact expectation is worse than
                // no expectation.
                expectedSize = null,
                expiresAt = chosen.expiresAt,
            ),
        )
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    private suspend fun variantsAt(
        path: String,
        segment: String,
        appTitle: String?,
    ): StoreResult<List<ApkComboVariant>> =
        when (val fetched = fetcher.get(config.downloadUrl(path, segment))) {
            is StoreResult.Success -> downloadParser.parse(fetched.value.html, fetched.value.url, appTitle)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }

    private fun ApkComboVariant.toAppVersion(pageSegment: String): AppVersion = AppVersion(
        versionName = versionName,
        versionCode = versionCode,
        ref = ApkComboRefs.versionRef(pageSegment, objectKey),
        artifactType = artifactType,
        sizeBytes = sizeBytes,
        minSdk = minSdk,
        abis = abis,
    )

    private companion object {
        const val FIRST_PAGE = 0
        const val LATEST = ApkComboConfig.LATEST_VERSION_SEGMENT
    }
}
