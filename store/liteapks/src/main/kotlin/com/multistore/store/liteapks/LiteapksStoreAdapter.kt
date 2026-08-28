package com.multistore.store.liteapks

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
import com.multistore.store.common.html.PageFetcher
import com.multistore.store.common.html.Urls
import com.multistore.store.common.storeCall
import com.multistore.store.liteapks.parser.LiteapksDetailParser
import com.multistore.store.liteapks.parser.LiteapksDownloadParser
import com.multistore.store.liteapks.parser.LiteapksSearchParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The liteapks adapter — **the ninth store, and the one most `curl` findings got wrong**.
 *
 * `curl` described it as the limit case: a listing unreachable without a WebView, a search capped at
 * nine results on a single page, a download yet to be measured. Re-measured with the client the app
 * really ships — OkHttp with a Chrome mobile User-Agent, from an Italian consumer IP — none of it
 * survives:
 *
 * | with `curl` | measured on 25/08/2026 |
 * |---|---|
 * | listing `403`, a silent challenge resolver is needed | **200**, the whole listing |
 * | search: one page, ~9 results | **18 per page, four pages**, `paged` honoured |
 * | `?s=…&paged=2` -> 404 | 200 with different results; the 404 is **past** the last page |
 * | download to be measured | `DIRECT`: no captcha, no human gesture |
 *
 * All four came from `curl`, which here gets `403 cf-mitigated: challenge` almost everywhere. It is
 * the third occurrence, after apkmirror's HTTP/1.1 pin and an1's `x-ratelimit-*` headers, and the
 * rule is the same: **whoever verifies an endpoint with `curl` is measuring `curl`**.
 *
 * ### Why `DIRECT` and not `USER_ASSISTED_ONLY`
 *
 * This is the decision this adapter has to justify, because a transit permit resembles an obstacle
 * to be circumvented and here it is not.
 *
 * The worker in front of `download*.liteapks.dev` demands two things: a `Referer` from
 * `liteapks.com` and a `?token=`. The token is `btoa(btoa(expiry))` — a Unix timestamp in base64,
 * twice — and the theme writes it in the clear in its own `site.js`. Measured against what the
 * worker really checks: a future expiry passes (even at ten days), a past one does not, a
 * non-numeric value does not. **No signature, no key, no secret.** There is nothing to pretend,
 * because the token attests to nothing: it is a client-declared expiry.
 *
 * The line — really doing what the site asks is legitimate; pretending to have done it is not —
 * falls here on the permitted side, and it is the same case as apkcombo, where the rule is "decode
 * the query instead of following the redirect". Where the line would fall if this changed: an HMAC
 * with a server-side key. The full note is on `LiteapksRefs.downloadToken`.
 *
 * The five-second countdown the page shows is a `setTimeout` running **after** the link is already
 * in the document — the same thing already seen on apkmody, where it is cosmetic.
 *
 * ### Two kinds of file, and the second is not a luxury
 *
 * Besides its own modified APKs, every download page may offer an "Original file on Google Play"
 * block: the **unmodified** APK, on a different CDN and with no transit permit at all. They are 11
 * rows out of 66, and on one listing out of thirty-one (`minecraft-earth`) they are the only file
 * that exists.
 *
 * Of those 11, three do not download: one answers 404 and two sit on hosts that **do not resolve**
 * (`play.liteapks.com`, `gp.liteapks.com` are NXDOMAIN). It is the same situation as modyolo, and
 * the answer is the same: [preflight].
 */
@Singleton
class LiteapksStoreAdapter @Inject constructor(
    private val config: LiteapksConfig,
    clients: StoreHttpClients,
    private val clock: Clock = Clock.System,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.LITEAPKS,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private val searchParser = LiteapksSearchParser(config)
    private val detailParser = LiteapksDetailParser(config)
    private val downloadParser = LiteapksDownloadParser(config)

    override val id: StoreId = StoreId.LITEAPKS

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared against what the pages really contain, on 25/08/2026.
     *
     * `providesPackageName = false` despite the listing carrying it in **26 cases out of 31**: the
     * capability is a promise the contract test checks on **every** search result, and it is never
     * in the card. It is the same declaration as pdalife, modyolo and uptodown, for the same
     * reason. Where it is present, the listing publishes it and step 4 of the pre-install pipeline
     * uses it — which on a store redistributing modified APKs is the only defence that path has.
     *
     * `providesHash = NONE` rests on a search actually made: zero occurrences of `sha256`, `md5`
     * and `checksum` across listings, download pages and slot pages; on the CDN the only candidate
     * is the `ETag`, which however ends in `-2` — it is a **multipart** R2 digest, not the file's
     * MD5. It is the same trap already recorded on an1, with the difference that there a real
     * `x-amz-meta-checksum-sha256` sat next to it and here there is none.
     *
     * `versionHistory = true`: the file page lists the previous versions — 3 groups and 6 files on
     * Minecraft, 2 and 3 on Telegram.
     *
     * `providesScreenshots = true` on 20 listings out of 31: `#screenshotScroll` is on the games and
     * almost never on the apps. The capability is a promise **about existence**, not about
     * completeness, and the contract test fixes its meaning: it demands the reference listing has
     * some.
     *
     * `supportsSplits = true` and it is not declared caution as on an1 and pdalife: **here the
     * `.xapk`s are visible**, being 8 of the 11 rows of the "Original file" block.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        // The home does not use the results markup — no `article[aria-label]`, no `#apps-grid` —
        // so "most popular" and "recent" would want a second parser for pages no screen asks for
        // today.
        trending = false,
        recent = false,
        versionHistory = true,
        providesPackageName = false,
        providesRating = true,
        providesScreenshots = true,
        // The "MOD Info" block is on 10 listings out of 31 and lists **what has been modified**,
        // not what changed in this version. Calling it a changelog would put a text about something
        // else under "What's new". The short line — `MOD: Premium, Lite, No ADS` — goes into
        // `summary` instead, which is the only description the card carries.
        providesChangelog = false,
        providesHash = HashAvailability.NONE,
        providesSignerFingerprint = false,
        supportsSplits = true,
        downloadMode = DownloadMode.DIRECT,
        // Rung 0. Cloudflare **really does challenge** here — unlike pdalife, where it sits in
        // passive CDN mode — but it challenges whoever does not resemble a browser: with a Chrome
        // mobile UA and HTTP/2, OkHttp gets 200 on search, listings, download pages and slots. With
        // `curl/8.7.1` as the UA, the same OkHttp gets 403.
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        // liteapks does not label adult content: no category, no tag. What it publishes of that
        // kind sits in the normal categories — `Project QT (R18)` is in "Role Playing", exactly
        // like modyolo's visual novels. Declaring the capability would be promising a filter with
        // nothing to act on.
        supportedFilters = emptySet(),
        // Census of 26/08/2026: the rating is on 7 rows out of 7.
        clientFilters = setOf(FilterCapability.MIN_RATING),
        contentKinds = setOf(ContentKind.APP, ContentKind.GAME),
        listingTtl = config.listingTtl,
    )

    /**
     * The search, which **paginates**.
     *
     * A query that reduces to nothing does not become a request: `?s=` with no value answers with
     * the home page, i.e. a page that has no `h1#search-title` and would end in `ParseFailure`.
     */
    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (page < 0) return@storeCall StoreResult.Success(PagedResult.empty(page))
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.searchUrl(trimmed, page))) {
            is StoreResult.Success -> searchParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * This listing's page on liteapks, to open it in the browser.
     *
     * The same address the listing itself reads.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        LiteapksRefs.slug(ref)?.let(config::listingUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> =
        storeCall { listing(ref).map { it.detail } }

    /**
     * The versions, which live **on another page**.
     *
     * The listing shows only one — the current, inside the stats box — while the list lives on
     * `/download/{stem}`. That is two requests per listing, and there is no way to make it one: the
     * stem is not derivable from the slug (`/h-i-d-e.html` downloads from
     * `/download/hide-h-i-d-e-72683`) and the search card does not publish the numeric id.
     */
    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> =
        storeCall { listing(ref).map { it.detail.versions } }

    /**
     * The file, in one or two requests depending on the kind.
     *
     * An **original** file is already a URL: it is handed over as-is. A **store** file lives behind
     * the slot page, which has to be opened to read its `data-link`. Without a [version] we start
     * from the listing and take the first file offered, which is the one the page presents first.
     */
    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        val chosen = version ?: when (val first = firstVersionRef(ref)) {
            is StoreResult.Success -> first.value
            is StoreResult.Failure -> return@storeCall first
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        val slot = LiteapksRefs.slotOf(chosen)
        if (slot == null) {
            val direct = LiteapksRefs.directUrlOf(chosen)
                ?: return@storeCall StoreResult.Failure(StoreError.NotFound)
            return@storeCall StoreResult.Success(resolution(direct, referer = config.baseUrl))
        }

        val slotUrl = config.downloadSlotUrl(slot.stem, slot.index)
        when (val fetched = fetcher.get(slotUrl)) {
            is StoreResult.Success ->
                downloadParser.parseSlotLink(fetched.value.html, fetched.value.url)
                    .map { resolution(it, referer = fetched.value.url) }
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * "Is the file there?", and **not** "is the store answering?".
     *
     * Needed for the same reason as modyolo: of the sampled original files, one answers 404. A 404
     * is `Success(false)` and not a `Failure`, because it is an answer **about the file**; counting
     * it as a source fault would open the circuit breaker on a store that has just correctly served
     * the listing and the list.
     *
     * The same holds for the **429** from `down.appsupload.com`, which is the CDN for part of the
     * catalogue and answers `{"code":"too_many_requests"}` to everyone, root included — i.e.
     * somebody else's account budget, not ours. It is the same lesson as an1's `x-ratelimit-*`
     * headers: **a published number is not necessarily a number that concerns you.**
     *
     * ### Two cases that do stay `Failure`, and it is worth saying which
     *
     * Not everything preventing a download comes through here as `false`, because not everything is
     * an answer about the file:
     *
     *  - a **403** is caught by the escalation ladder **before** this method and becomes
     *    `StoreError.Blocked`. That is right: a 403 says the store is blocking *us*, not that the
     *    file is missing — and it is how the worker answers a URL without a transit permit;
     *  - a host that **does not resolve** — `play.liteapks.com` and `gp.liteapks.com` are NXDOMAIN,
     *    and they are two of the eleven sampled original rows — is a network error, therefore
     *    indistinguishable from DNS not answering for a moment. Treating it as "file absent" would
     *    hide a file that exists, forever.
     *
     * In both cases `ResolveDownloadUseCase` reads `getOrNull() ?: true`, i.e. tries anyway: the
     * fault shows up at the start of the transfer instead of before it, which is the cautious way
     * round of the two.
     */
    override suspend fun preflight(resolution: DownloadResolution): StoreResult<Boolean> = storeCall {
        val direct = resolution as? DownloadResolution.Direct
            ?: return@storeCall StoreResult.Success(true)
        fetcher.head(direct.url, direct.headers).map { it.isSuccessful }
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    /**
     * The listing **plus** the file list, which are two different pages.
     *
     * If the listing does not publish the download button — it did not happen on any of the 31
     * sampled, but the markup does not guarantee it — the listing comes back without versions
     * instead of failing: it is an incomplete listing, not an unreadable page.
     */
    private suspend fun listing(ref: StoreAppRef): StoreResult<LiteapksDetailParser.Parsed> {
        val slug = LiteapksRefs.slug(ref) ?: return StoreResult.Failure(StoreError.NotFound)
        val parsed = when (val fetched = fetcher.get(config.listingUrl(slug))) {
            is StoreResult.Success -> detailParser.parse(fetched.value.html, fetched.value.url, ref)
            is StoreResult.Failure -> return fetched
            StoreResult.Unsupported -> return StoreResult.Unsupported
        }
        val detail = (parsed as? StoreResult.Success)?.value ?: return parsed
        val stem = detail.downloadStem ?: return parsed

        return when (val files = filesOf(stem)) {
            is StoreResult.Success -> StoreResult.Success(
                detail.copy(detail = detail.detail.copy(versions = files.value.map(::versionOf))),
            )
            // The file list is another page and can be missing without the listing being wrong:
            // what was read is returned, and the listing will say there is nothing to download
            // instead of not existing at all.
            is StoreResult.Failure -> parsed
            StoreResult.Unsupported -> parsed
        }
    }

    private suspend fun filesOf(stem: String): StoreResult<List<LiteapksDownloadParser.File>> =
        when (val fetched = fetcher.get(config.downloadUrl(stem))) {
            is StoreResult.Success -> downloadParser.parseFiles(fetched.value.html, fetched.value.url)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }

    /**
     * The first file the listing offers.
     *
     * A listing with no files is `NotFound` and not an empty list: the page is there but has
     * nothing to download, and calling that case "resolved" would hand the download engine a URL
     * that does not exist.
     */
    private suspend fun firstVersionRef(ref: StoreAppRef): StoreResult<VersionRef> =
        listing(ref).map { parsed ->
            parsed.detail.versions.firstOrNull()?.ref
                ?: return StoreResult.Failure(StoreError.NotFound)
        }

    private fun versionOf(file: LiteapksDownloadParser.File): AppVersion = AppVersion(
        versionName = file.versionName,
        // No version code, anywhere: not in the card, not in the listing, not in the file name on
        // the CDN. `VersionSelection` will answer `UpToDate(comparable = false)`, which is the only
        // true sentence — the same situation as uptodown and an1.
        versionCode = null,
        ref = file.ref,
        artifactType = file.artifactType,
        sizeBytes = file.sizeBytes,
    )

    /**
     * The URL ready to hand over, with the transit permit **only where it is needed**.
     *
     * The site's theme adds it under the same condition — `WORKER_DOWNLOAD_HOSTS` — and the
     * condition is not cosmetic: `gp4.liteapks.com` and `down.appsupload.com` serve their files
     * without asking anything, while `download*.liteapks.dev` answers 403 "Access is not allowed"
     * to whoever does not carry both a token **and** a Referer.
     */
    private fun resolution(url: String, referer: String): DownloadResolution.Direct {
        val expiresAt = clock.now() + config.downloadTokenTtl
        val host = url.toHttpUrlOrNull()?.host
        val gated = host != null && host in config.tokenizedFileHosts
        val finalUrl = if (gated) {
            url.toHttpUrlOrNull()
                ?.newBuilder()
                ?.setQueryParameter(TOKEN_PARAM, LiteapksRefs.downloadToken(expiresAt))
                ?.build()
                ?.toString()
                ?: url
        } else {
            url
        }
        val fileName = LiteapksRefs.fileNameOf(finalUrl)
        return DownloadResolution.Direct(
            url = finalUrl,
            // The UA is set by the download engine; the Referer is not, and without it the worker
            // answers 403 even with the right token.
            headers = mapOf(REFERER to referer),
            fileName = fileName,
            artifactType = Urls.artifactTypeOf(fileName),
            // No published hash: see `providesHash` in the capabilities.
            expectedSha256 = null,
            // The page writes `800 MB`, rounded. As an expected value it would make a complete file
            // look truncated — the error apkcombo had already taught.
            expectedSize = null,
            // Only where the permit really expires. On a non-gated URL there is nothing to expire,
            // and declaring a fake expiry would make a good link be re-resolved.
            expiresAt = expiresAt.takeIf { gated },
        )
    }

    private companion object {
        const val REFERER = "Referer"
        const val TOKEN_PARAM = "token"
    }
}
