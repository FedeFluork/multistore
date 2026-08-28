package com.multistore.store.uptodown

import com.multistore.core.model.AppVersion
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.api.DownloadHint
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
import com.multistore.store.uptodown.parser.UptodownDetail
import com.multistore.store.uptodown.parser.UptodownDetailParser
import com.multistore.store.uptodown.parser.UptodownDownloadParser
import com.multistore.store.uptodown.parser.UptodownSearchParser
import com.multistore.store.uptodown.parser.UptodownTopParser
import com.multistore.store.uptodown.parser.UptodownTables
import com.multistore.store.uptodown.parser.UptodownVersionEntry
import com.multistore.store.uptodown.parser.UptodownVersionsParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The uptodown adapter — **the first user-assisted store**, and at the same time the one with the
 * best metadata after F-Droid.
 *
 * The two go together and look contradictory. uptodown publishes for each file the **SHA-256**, the
 * `packageName`, the size, the date, the type, the ABIs and the `minSdk`; and then puts the file
 * behind a Cloudflare Turnstile, i.e. behind the one thing a programmatic client cannot cross
 * without lying.
 *
 * ### What `USER_ASSISTED_ONLY` means here, concretely
 *
 * The download button **is not a link**: it is a `<button>` running the Turnstile and then posting
 * the token to `POST /ajax/app/{appID}/file/{fileID}/download-url`. That endpoint exists and can be
 * documented in three lines, but calling it without really having run the challenge would be
 * **pretending** to have solved it — and that is exactly where this project draws its line. So: the
 * real page, inside a WebView, and a person's tap.
 *
 * **The assisted path is not a less verified path.** This is the part that justifies the extra
 * request [getDownloadLink] makes: the download page publishes the SHA-256 of the file it is about
 * to serve, so what the user downloads with a tap reaches the pipeline with an expected value and
 * is compared like any direct download. Without that read, uptodown would be the only store where
 * the installed file is compared against nothing.
 *
 * ### What a silent challenge resolver would change
 *
 * The widget is mounted with `appearance: "interaction-only"`: it stays invisible until Cloudflare
 * really asks for a gesture. A `WebViewSilentResolver` that **executes** that JavaScript would
 * obtain the token by itself in most cases, and the tap would remain for the times Turnstile
 * escalates to interactive. That is really doing what the site asks, not simulating it: it sits on
 * the permitted side.
 */
@Singleton
class UptodownStoreAdapter @Inject constructor(
    private val config: UptodownConfig,
    clients: StoreHttpClients,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.UPTODOWN,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private val refs = UptodownRefs(config)
    private val tables = UptodownTables(config)
    private val searchParser = UptodownSearchParser(config, refs)
    private val topParser = UptodownTopParser(config, refs)
    private val detailParser = UptodownDetailParser(config, tables)
    private val versionsParser = UptodownVersionsParser(config, tables)
    private val downloadParser = UptodownDownloadParser(config, tables)

    override val id: StoreId = StoreId.UPTODOWN

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared against what the pages really contain, on 24/08/2026.
     *
     * `providesHash = SOMETIMES` is the declaration needing explanation, because uptodown
     * **always** publishes the hash: it publishes it, though, on *that* file's page, one per
     * version. The listing carries only one, the current, and asking for the other twenty would
     * cost twenty requests to fill a table almost nobody will look at. `ALWAYS` would force that —
     * the contract test demands a hash on **every** version — and it would be the kind of honesty
     * paid for in latency without buying anything: the version the app offers by default is the
     * current one, and it is the only one that needs verifying.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        // `/android/top`, ten entries with the rank declared. It is one of only two readable
        // charts among the nine stores: apkcombo renders its in JavaScript, an1 serves the homepage
        // in place of `/popular/`, and what pdalife titles "Popular on Android" are category links
        // in the footer.
        trending = true,
        // `/android/latest-updates`, which uses **the same** `#content-list` as search.
        recent = true,
        versionHistory = true,
        // The package is in the listing, never in the search results.
        providesPackageName = false,
        providesRating = true,
        providesScreenshots = true,
        providesChangelog = false,
        providesHash = HashAvailability.SOMETIMES,
        // "Certificate signature" is **MD5**, not SHA-256: 32 hex characters. There is nothing to
        // put in `signerSha256`, and the type would prevent it anyway.
        providesSignerFingerprint = false,
        supportsSplits = true,
        downloadMode = DownloadMode.USER_ASSISTED_ONLY,
        // The **pages** are fetched with OkHttp with no obstacle at all: the Turnstile is only on
        // the file. Declaring `WEBVIEW` here would force search and listing through a browser
        // engine for a limit that does not concern them.
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        supportedFilters = emptySet(),
        listingTtl = config.listingTtl,
    )

    /**
     * Search. **Pages after the first are empty by construction.**
     *
     * Measured: `?page=2` returns the **same 36 apps** as the first page, in a different order —
     * identical set of hrefs. The order is randomised server-side among equally scored results, so
     * paginating would not even give stable results to compare.
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
     * The downloads chart.
     *
     * A single page: the `Top downloads` tab declares no others and does not accept `?page=` —
     * tried, `?page=2` returns the same bytes, as this store's search already does.
     */
    override suspend fun getTrending(page: Int): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (page > FIRST_PAGE) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.topUrl())) {
            is StoreResult.Success -> topParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * Recently updated apps.
     *
     * The parser is **the search one**, not a second: `/android/latest-updates` emits
     * `#content-list` with the same rows. Reusing it is not laziness — it means the container
     * defence, the one that stops the twelve "Apps you're gonna love" cards being mistaken for
     * results, holds here too without being rewritten.
     */
    override suspend fun getRecent(page: Int): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (page > FIRST_PAGE) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.recentUrl())) {
            is StoreResult.Success -> searchParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * This listing's page on uptodown, to open it in the browser.
     *
     * The language subdomain, as everywhere in this adapter: `www` would serve the page in Spanish,
     * which is nobody's chosen language here.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        refs.slugOf(ref)?.let(config::appUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> = storeCall {
        val slug = refs.slugOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        listing(slug, ref).map { (detail, versions) -> detail.listing.copy(versions = versions) }
    }

    /**
     * Listing and version list, which on this store are two pages and one thing.
     *
     * The list at the foot of the listing shows **6** and **skips the current one**; `/versions`
     * carries **20**, current included. The listing, in exchange, is the only one publishing the
     * current file's SHA-256. Neither is enough on its own, and keeping them together in one place
     * is what stops `getAppDetails` and `getVersions` answering different things.
     *
     * **A failure propagates.** A listing with an empty `versions` would say "this store publishes
     * nothing installable" when the truth may be "we have been blocked".
     */
    private suspend fun listing(
        slug: String,
        ref: StoreAppRef,
    ): StoreResult<Pair<UptodownDetail, List<AppVersion>>> {
        val page = when (val fetched = fetcher.get(config.appUrl(slug))) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return fetched
            StoreResult.Unsupported -> return StoreResult.Unsupported
        }
        val detail = when (val parsed = detailParser.parse(page.html, page.url, ref)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return parsed
            StoreResult.Unsupported -> return StoreResult.Unsupported
        }
        val entries = when (val parsed = versions(slug)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return parsed
            StoreResult.Unsupported -> return StoreResult.Unsupported
        }
        return StoreResult.Success(detail to versionsOf(entries, detail))
    }

    /**
     * The versions, **the same ones** the listing carries: two requests, not one.
     *
     * `/versions` alone would have given the list, at half the cost. It is not enough because the
     * current version's hash is on the **listing**, and a `getVersions` that left it out would give
     * two callers two different answers about the same thing: the listing would say "this version
     * has a published hash", the list would say no. Of the two, the one lying is always the second,
     * and it would be the easier to believe because it is the "complete" list.
     */
    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> = storeCall {
        val slug = refs.slugOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        listing(slug, ref).map { it.second }
    }

    /**
     * Where the user has to go, and what the file coming back will be compared against.
     *
     * The request is **only** for the SHA-256: the page contains no link to the file. It is worth
     * making because it is the difference between a verified assisted download and an unverified
     * one.
     */
    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        val slug = refs.slugOf(ref) ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
        val url = config.downloadUrl(slug, versionIdOf(version))

        val page = when (val fetched = fetcher.get(url)) {
            is StoreResult.Success -> fetched.value
            is StoreResult.Failure -> return@storeCall fetched
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }
        val download = when (val parsed = downloadParser.parse(page.html, page.url)) {
            is StoreResult.Success -> parsed.value
            is StoreResult.Failure -> return@storeCall parsed
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        StoreResult.Success(
            DownloadResolution.UserAssisted(
                pageUrl = page.url,
                // Not `SOLVE_CAPTCHA`, and the choice is measured: the widget is mounted
                // `interaction-only`, i.e. it stays invisible until Cloudflare really asks for a
                // gesture — and it almost never does. The instruction the user reads must describe
                // what they will have to do in the normal case, which is pressing "Download" and
                // nothing else. Announcing a captcha that does not appear teaches people to ignore
                // the instructions.
                hint = DownloadHint.TAP_DOWNLOAD_BUTTON,
                expectedSha256 = download.info.sha256,
                // uptodown writes `78.85 MB`, rounded: as on apkcombo and apkmody, an inexact
                // expected size is worse than no expectation at all.
                expectedSize = null,
            ),
        )
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    private suspend fun versions(slug: String): StoreResult<List<UptodownVersionEntry>> =
        when (val fetched = fetcher.get(config.versionsUrl(slug))) {
            is StoreResult.Success -> versionsParser.parse(fetched.value.html, fetched.value.url)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }

    /**
     * The versions, with the current one's hash attached to its row.
     *
     * The attachment is by **file identifier**, not by version name: `data-version-id` in the list
     * and `data-file-id` in the listing are the same figure, and uptodown assigns it to the file.
     * Attaching by `versionName` would look equivalent and is not — two files can carry the same
     * version name, and giving one the other's hash would produce a verification that fails on a
     * legitimate file.
     */
    private fun versionsOf(
        entries: List<UptodownVersionEntry>,
        detail: UptodownDetail,
    ): List<AppVersion> {
        if (entries.isEmpty()) {
            // Not observed — `/versions` always lists at least the current one — but if it
            // happened, a listing with no versions would be indistinguishable from a broken store.
            val id = detail.currentFileId ?: return emptyList()
            return listOf(
                AppVersion(
                    versionName = detail.listing.summary.latestVersionName ?: id,
                    versionCode = null,
                    ref = versionRefOf(id),
                    artifactType = detail.currentFile.artifactType,
                    sizeBytes = detail.currentFile.sizeBytes,
                    abis = detail.currentFile.abis,
                    sha256 = detail.currentFile.sha256,
                    publishedAt = detail.currentFile.publishedAt,
                ),
            )
        }
        return entries.map { entry ->
            val isCurrent = entry.versionId == detail.currentFileId
            entry.toAppVersion(
                sizeBytes = detail.currentFile.sizeBytes.takeIf { isCurrent },
                abis = if (isCurrent) detail.currentFile.abis else emptyList(),
                sha256 = detail.currentFile.sha256.takeIf { isCurrent },
            )
        }
    }

    private fun UptodownVersionEntry.toAppVersion(
        sizeBytes: Long? = null,
        abis: List<String> = emptyList(),
        sha256: com.multistore.core.model.Sha256? = null,
    ): AppVersion = AppVersion(
        versionName = versionName,
        // **uptodown does not publish the version code anywhere.** `data-version-id` is not one:
        // it is the file's identifier in uptodown's archive and grows over time across all apps
        // together. Writing it there would give an anti-downgrade rule comparing 1,195,732,851
        // with the installed app's real version code.
        versionCode = null,
        ref = versionRefOf(versionId),
        artifactType = artifactType,
        sizeBytes = sizeBytes,
        minSdk = minSdk,
        abis = abis,
        sha256 = sha256,
        publishedAt = publishedAt,
    )

    private companion object {
        const val FIRST_PAGE = 0
    }
}
