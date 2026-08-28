package com.multistore.store.modyolo

import com.multistore.core.model.AppVersion
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
import com.multistore.store.api.FilterCapability
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
import com.multistore.store.modyolo.parser.ModyoloDetailParser
import com.multistore.store.modyolo.parser.ModyoloDownloadParser
import com.multistore.store.modyolo.parser.ModyoloSearchParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The modyolo adapter — **the store where one file in four is gone**, and the first that can say
 * which of its contents are adult.
 *
 * ### The preflight, and why it is not a failing download resolution
 *
 * About a quarter of the catalogue is metadata-only: the listing is there, the link resolves, and
 * the file answers **HTTP 500**. Measured on 25/08/2026 across 120 posts stratified by age:
 *
 * | Layer | Alive | Dead |
 * |---|---|---|
 * | oldest | 29 | 11 |
 * | middle | 25 | 15 |
 * | most recent | 40 | 0 |
 *
 * The gradient is real but not the cliff first assumed: the earlier measurement put the old layers
 * at zero. That measurement was **skewed by URL encoding** — the CDN's paths contain raw spaces,
 * and a client that does not encode them never even makes the request. With conditional
 * normalisation the living go from 2 to 29 on the same sample.
 *
 * A quarter is still too many to show "Download" and hope, which is why the adapter implements
 * [preflight]. But the important choice is **how**: a `HEAD` answering 500 becomes
 * `Success(false)` — "this file is not there" — and **not** a failure. Were it a store error, the
 * circuit breaker would open and take with it the three quarters of the catalogue that works
 * perfectly.
 *
 * A `requiresPreflight` capability was also proposed, so the UI could decide whether to show the
 * button immediately or after the check. **It was not added**, because download resolution here is
 * already lazy: it runs when the user acts, not when they open the listing. The button is always
 * there, and the preflight turns a 500 mid-download into an "unavailable" before a single byte
 * leaves. A capability no screen reads would be a branch no configuration walks.
 *
 * ### The adult-content filter
 *
 * modyolo is the only implemented store that **labels** adult content: six WordPress categories,
 * one with 698 posts and five with 24 each, and its REST API accepts a category exclusion. The
 * filter is therefore **server-side**: twenty results are not downloaded to discard five, twenty
 * already-filtered ones are requested.
 *
 * What the filter does **not** do, and the setting says so in these words: guarantee nothing gets
 * through. On the day of the measurement the site's three most recent articles were adult visual
 * novels distributed via Patreon, all three filed under "Role Playing". No filter can know more
 * than the source, and guessing by keyword would remove a parental-control app whose name contains
 * the word.
 *
 * ### `recent` stays off, precisely because of that measurement
 *
 * modyolo publishes a chronological feed and would be a natural Home candidate. It is not
 * declared, because the surface where labelling proved absent is **exactly that one**: the most
 * recent. Offering "new" while knowing the new is the unlabelled part of the catalogue would make
 * the setting fail where it does most damage.
 */
@Singleton
class ModyoloStoreAdapter @Inject constructor(
    private val config: ModyoloConfig,
    clients: StoreHttpClients,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.MODYOLO,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private val searchParser = ModyoloSearchParser(config)
    private val detailParser = ModyoloDetailParser()
    private val downloadParser = ModyoloDownloadParser(config)

    override val id: StoreId = StoreId.MODYOLO

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared against what the APIs actually return, on 25/08/2026.
     *
     * `providesPackageName = false` despite the listing publishing it nearly always: the package is
     * inferred from the Google Play link, and that link is missing exactly where the catalogue is
     * most distinctive — the visual novels distributed via Patreon, and the apps that were never on
     * Play. Search results never carry it.
     *
     * `providesRating = false`: the rating exists but only inside the HTML page's structured data,
     * which weighs 120 KB. Not worth a second request for one star.
     *
     * `providesScreenshots = true` with a caveat written in the parser: modyolo has no gallery, it
     * has a cover and a banner. They are the only preview it publishes, and the listing shows them
     * for what they are.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        trending = false,
        recent = false,
        versionHistory = true,
        providesPackageName = false,
        providesRating = false,
        providesScreenshots = true,
        providesChangelog = true,
        providesHash = HashAvailability.NONE,
        providesSignerFingerprint = false,
        // The observed files are all `.apk`, but their FAQ explains how to install split
        // containers, and the type is read from the file name. Declaring `false` would be a promise
        // the first container would break.
        supportsSplits = true,
        downloadMode = DownloadMode.DIRECT,
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        supportedFilters = setOf(FilterCapability.NSFW_CONTENT),
        contentKinds = setOf(ContentKind.UNKNOWN),
        listingTtl = config.listingTtl,
    )

    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (query.isBlank()) return@storeCall StoreResult.Success(PagedResult.empty(page))
        if (page < 0) return@storeCall StoreResult.Success(PagedResult.empty(page))

        val url = config.searchUrl(query, page, filters.includeNsfw)
        when (val fetched = fetcher.get(url)) {
            is StoreResult.Success ->
                searchParser.parse(fetched.value.html, page, query)

            is StoreResult.Failure ->
                // **Past the last page WordPress answers 400**, not with an empty list. It is the
                // only one of the stores to do so, and propagating it would trip the circuit
                // breaker on a healthy source every time someone reaches the end of a scroll — the
                // contract test gets there by asking for page 9999. It only holds from the second
                // page on: a 400 on the first is not "you asked for too much", and stays an error.
                if (page > FIRST_PAGE && fetched.error.isInvalidPage()) {
                    StoreResult.Success(PagedResult.empty(page))
                } else {
                    fetched
                }

            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * This listing's page on modyolo, to open in a browser.
     *
     * Not the address the adapter queries: that is the JSON endpoint. What a browser opens is
     * `<root>/<slug>.html`, and the ref holds the slug.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        ModyoloRefs.slugOf(ref)?.let(config::webListingUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> = storeCall {
        val id = ModyoloRefs.idOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)

        val detail = when (val fetched = fetcher.get(config.detailUrl(id))) {
            is StoreResult.Success -> detailParser.parse(fetched.value.html, ref)
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        val listing = when (detail) {
            is StoreResult.Success -> detail.value
            is StoreResult.Failure -> return@storeCall detail
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        // A second request, and a failure **propagates**. A listing with no versions would say
        // "nothing downloads from here", while the truth may be "the download page changed": two
        // different diagnoses, and the user needs theirs. The same choice already made on apkmody.
        val versions = when (val parsed = versions(ref)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        StoreResult.Success(listing.copy(versions = versions))
    }

    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> =
        storeCall { versions(ref) }

    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        val stem = ModyoloRefs.stem(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        val variant = ModyoloRefs.variantOf(version)
        val referer = config.downloadVariantUrl(stem, variant)

        // The `Referer` **is** the parameter: the POST carries neither the id nor the variant, and
        // without that header modyolo answers 200 with an empty body. Sending it is not a disguise
        // — it is exactly what the browser sends opening that page.
        val fragment = when (val fetched = fetcher.post(config.ajaxUrl, config.ajaxForm, refererOf(referer))) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        val file = when (val parsed = downloadParser.parseFile(fragment.html, referer)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        StoreResult.Success(
            DownloadResolution.Direct(
                url = file.url,
                // The CDN asks for no Referer: the `HEAD` answers 200 with only the User-Agent.
                headers = emptyMap(),
                fileName = file.fileName,
                artifactType = file.artifactType,
                // No hash published anywhere: not in the JSON, not in the HTML, not in the CDN's
                // headers — which here, unlike an1's, is not S3 object storage.
                expectedSha256 = null,
                // **No expected size.** The declared figure is rounded to the binary megabyte: up
                // to half a megabyte of discrepancy, and the download engine concludes the
                // connection dropped in front of a complete file. It happened on apkcombo. The
                // approximate size stays on the version, where it is for display.
                expectedSize = null,
                // The CDN URL is not signed and does not expire.
                expiresAt = null,
            ),
        )
    }

    /**
     * The `HEAD` that decides whether the button makes sense. See the note atop this class.
     *
     * A failed outcome is **`Success(false)`**, not an error: "this file is not there" is not "this
     * store is not answering". A real failure — dropped connection, block — stays a failure, and
     * the caller treats it as "I do not know" and assumes available.
     */
    override suspend fun preflight(resolution: DownloadResolution): StoreResult<Boolean> = storeCall {
        val direct = resolution as? DownloadResolution.Direct
            ?: return@storeCall StoreResult.Success(true)
        fetcher.head(direct.url).map { it.isSuccessful }
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    private suspend fun versions(ref: StoreAppRef): StoreResult<List<AppVersion>> {
        val stem = ModyoloRefs.stem(ref) ?: return StoreResult.Failure(StoreError.NotFound)
        val page = config.downloadVariantUrl(stem, ModyoloRefs.FIRST_VARIANT)
        return when (val fetched = fetcher.get(page)) {
            is StoreResult.Success -> downloadParser.parseVersions(fetched.value.html, fetched.value.url)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    private fun refererOf(url: String): Map<String, String> = mapOf(REFERER to url)

    private fun StoreError.isInvalidPage(): Boolean =
        this is StoreError.Network && httpCode == HTTP_BAD_REQUEST

    private companion object {
        const val FIRST_PAGE = 0
        const val HTTP_BAD_REQUEST = 400
        const val REFERER = "Referer"
    }
}
