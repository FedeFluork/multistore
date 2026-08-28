package com.multistore.store.pdalife

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
import com.multistore.store.pdalife.parser.PdalifeDetailParser
import com.multistore.store.pdalife.parser.PdalifeFeedParser
import com.multistore.store.pdalife.parser.PdalifeSearchParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * The pdalife adapter — **the second user-assisted store**, and the first where the assisted path
 * is dangerous in itself.
 *
 * ### Why `USER_ASSISTED_ONLY`, concretely
 *
 * The download takes two hops. The first, `/dwn/{hash}.html`, is a 301 towards
 * `https://mobdisc.com/dw{hash}/download.html` — a different domain, served by nginx without
 * Cloudflare. On that page the button starts **disabled**
 * (`b-download__button_state_inactive`) with a fake `href` (`#/download/Telegram-9.7.3.apk`), and
 * the real address arrives only after `/js/wp.js` has run
 * `grecaptcha.execute(GRCV3_KEY, {action:'get_key'})` and posted the token to `POST /get_key/`.
 *
 * reCAPTCHA v3 is **invisible**: there is no puzzle to solve, there is a score Google assigns to
 * whoever is browsing. Calling `/get_key/` with a token taken elsewhere, or without one, would be
 * **pretending** to have done what the site asks — and that is exactly where this project draws
 * its line. So: the real page, inside a WebView, and a person's tap.
 *
 * ### What a silent challenge resolver would change
 *
 * A `WebViewSilentResolver` that **executes** that JavaScript would obtain a real token, and that
 * is authentic execution, not simulation: it would sit on the permitted side exactly as for
 * uptodown. Worth writing down because it differs from uptodown's Turnstile: there the widget can
 * escalate to interactive, here **there is nothing interactive to escalate to**.
 *
 * ### Two of that page's three buttons are adverts, and one is an `.apk`
 *
 * The most important fact about this store, and it concerns not the adapter but the user the
 * assisted screen puts in front of it:
 *
 * ```
 * <a class="b-download__button b-download__button_state_inactive js-dwn-btn"
 *    href="#/download/real-gangster-v6-3-5-mod.apk" data-dwn="6d2d7bca">   <- the real one
 * <a class="b-download__button" href="https://mq.omenpenial.com/…"       > <- advert
 * <a class="b-download__button" href="https://api.monstervpn.cc/media/apk_versions/monsterVPN-2.4.3.apk">
 * ```
 *
 * The third is **a real APK of another app**, looking just like the right button, and
 * `:feature:webviewdownload` intercepts whatever download the page starts. What stops it being
 * installed is step 4 of the pre-install pipeline, and that step works **only if the listing
 * declares a `packageName`** — which is the main reason this adapter declares it despite not having
 * been able to verify it against the bytes. See the note in `PdalifeDetailParser`.
 */
@Singleton
class PdalifeStoreAdapter @Inject constructor(
    private val config: PdalifeConfig,
    clients: StoreHttpClients,
    /**
     * Used to discard feed dates that fall in the future.
     *
     * On this store that is not hypothetical: **5 entries out of 100**, the furthest at 2029. See
     * `PdalifeFeedParser`.
     */
    private val clock: Clock = Clock.System,
) : StoreAdapter {

    private val fetcher = PageFetcher(
        clients.forStore(
            StoreId.PDALIFE,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private val searchParser = PdalifeSearchParser(config)
    private val feedParser = PdalifeFeedParser(config)
    private val detailParser = PdalifeDetailParser(config)

    override val id: StoreId = StoreId.PDALIFE

    override val metadata: StoreMetadata = config.metadata

    /**
     * Declared against what the pages really contain, on 25/08/2026.
     *
     * `providesPackageName = false` despite the listing carrying it in 12 cases out of 17: the
     * capability is a promise the contract test checks on **every** search result, and there it is
     * never present. It is the same declaration as uptodown and modyolo, for the same reason.
     *
     * `providesHash = NONE` is a strong declaration and it is worth saying what it rests on: zero
     * occurrences of `sha256`, `md5` and `checksum` on the listing, and the file cannot be reached
     * without the user's tap — so not even a `Content-Length` or a CDN header. On this store the
     * pre-install verification will have the hash "computed but not compared", and that is what the
     * UI must say.
     *
     * `supportsSplits = true` is declared caution, as for an1: every observed download is a single
     * `.apk` — even the 2.44 GB of GTA San Andreas — but the real type is decided by the file name
     * when the assisted download returns, and there `:feature:webviewdownload` recognises `.xapk`,
     * `.apkm` and `.apks`. Declaring `false` would be a promise this adapter is in no position to
     * keep, because it never sees the file.
     */
    override val capabilities: StoreCapabilities = StoreCapabilities(
        search = true,
        searchSource = SearchSource.REMOTE,
        // What the site titles "Popular on Android" are **category links in the footer** — "Games
        // on Android", "Programs on Android", "PlayMarket" — not an app chart. `/top/` answers 404
        // and `/android/` is byte-for-byte the homepage.
        trending = false,
        // `/rss/`: a hundred entries, all Android, with date and category. It is the only surface
        // on this store that does not mix iOS and PSP.
        recent = true,
        versionHistory = true,
        providesPackageName = false,
        providesRating = true,
        providesScreenshots = true,
        // Every version's panel carries a line of text, but on every sampled version it is
        // "Changes not specified.": it is a placeholder in their template, not a changelog. It is
        // kept where present and not promised.
        providesChangelog = false,
        providesHash = HashAvailability.NONE,
        providesSignerFingerprint = false,
        supportsSplits = true,
        downloadMode = DownloadMode.USER_ASSISTED_ONLY,
        // Search and listings are fetched with OkHttp with no obstacle at all: Cloudflare is there
        // (`server: cloudflare`, `cf-ray`) but in passive CDN mode, no challenge on any read. The
        // reCAPTCHA is only on the download's second hop, which is on another domain and this
        // client never touches it.
        networkTier = NetworkTier.OKHTTP,
        userAgent = config.userAgent,
        // pdalife **does have** an adult label — the `/tag/android-18-plus/` tag — and it is still
        // not declared, on two measurements. The first: they are **five apps** across the whole
        // site. The second, which is the deciding one: the tags are on the **listing**, not in the
        // search results, so filtering on them would cost one detail request per result. Declaring
        // `NSFW_CONTENT` would mean promising a filter that cannot be applied where it is needed.
        // The rule holds: if a source does not label **where we look**, the honest answer is not to
        // declare the capability.
        supportedFilters = emptySet(),
        // Census of 26/08/2026: rating and category on 18 rows out of 18. It is the store that
        // populates the most list fields of the nine, despite also being among the most hostile.
        clientFilters = setOf(FilterCapability.MIN_RATING, FilterCapability.CATEGORY),
        contentKinds = setOf(ContentKind.APP, ContentKind.GAME),
        listingTtl = config.listingTtl,
    )

    /**
     * Searches the HTML page, not `/suggest/`. The reason is at the head of [PdalifeConfig].
     *
     * The query is slugified before being sent: without that, the first page costs two redirects
     * and `c++` answers 404. A query that reduces to nothing — `+++`, `///` — does not become a
     * request: it would be `/search//`, which is a different page.
     */
    override suspend fun search(
        query: String,
        filters: SearchFilters,
        page: Int,
    ): StoreResult<PagedResult<StoreListingSummary>> = storeCall {
        if (page < 0) return@storeCall StoreResult.Success(PagedResult.empty(page))
        val slug = config.slugify(query)
        if (slug.isBlank()) return@storeCall StoreResult.Success(PagedResult.empty(page))
        when (val fetched = fetcher.get(config.searchUrl(slug, page))) {
            is StoreResult.Success -> searchParser.parse(fetched.value.html, fetched.value.url, page)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }


    /**
     * The most recently published apps.
     *
     * A single request, and the cheapest on this store: 130 KB against the homepage's 104, but with
     * a hundred usable entries instead of a mixed list of iOS, PSP and Android to be filtered row
     * by row.
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
     * This listing's page on pdalife, to open it in the browser.
     *
     * The same address the listing itself reads.
     */
    override fun listingUrl(ref: StoreAppRef): String? =
        PdalifeRefs.stem(ref)?.let(config::listingUrl)

    override suspend fun getAppDetails(ref: StoreAppRef): StoreResult<StoreListingDetail> =
        storeCall { listing(ref) }

    /**
     * The versions, which live **on the same page** as the listing.
     *
     * No second request: pdalife lists the versions inside the listing, in an accordion. It would
     * be the natural occasion to use `POST /app/moreVersions/` and fetch more; we do not, and the
     * reason is in the note on `versionsOf` in `PdalifeDetailParser` — on some apps that endpoint
     * returns forever the same versions the page already has.
     */
    override suspend fun getVersions(ref: StoreAppRef): StoreResult<List<AppVersion>> =
        storeCall { listing(ref).map { it.versions } }

    /**
     * Where the user has to go.
     *
     * With a [version] already in hand no request is needed: a version's ref **is** the octet
     * composing the first hop's URL. Without one, the listing is read and the most recent version
     * taken — which is the same one the listing offers.
     *
     * `expectedSha256` and `expectedSize` stay null, and that is the difference from uptodown:
     * there the extra request bought the SHA-256 and turned an assisted download into a verified
     * one. Here there is nothing to buy — no hash on the site, and the size is written rounded
     * (`68.83 Mb`), so as an expected value it would make a complete file look truncated. It goes
     * into `AppVersion.sizeBytes`, which is for display.
     */
    override suspend fun getDownloadLink(
        ref: StoreAppRef,
        version: VersionRef?,
    ): StoreResult<DownloadResolution> = storeCall {
        val hash = PdalifeRefs.downloadHash(version) ?: when (val resolved = newestHash(ref)) {
            is StoreResult.Success -> resolved.value
            is StoreResult.Failure -> return@storeCall resolved
            StoreResult.Unsupported -> return@storeCall StoreResult.Unsupported
        }

        StoreResult.Success(
            DownloadResolution.UserAssisted(
                pageUrl = config.downloadUrl(hash),
                // **`CHOOSE_A_MIRROR`, and the choice comes from a test on the device.**
                //
                // The first draft said `TAP_DOWNLOAD_BUTTON` — "tap the download button" — and on
                // the real page that advice walks the user straight into an advert. Tested on
                // 25/08/2026 on the emulator: of the four buttons the page shows after the
                // reCAPTCHA, **the one labelled "Скачать сейчас" (Download now), with the download
                // icon, is advertising** and leads to AliExpress via `avaqi.com`; the fourth is
                // "Скачать MonsterVPN". The two real ones are "Скачать с t.me" and "Зеркало #1",
                // and they are recognisable because they carry **the file's size** — they are the
                // ones that receive the `href` from `/get_key/`'s response.
                //
                // "Choose one of the mirrors the page offers" describes that screen; "tap the
                // download button" describes a screen that does not exist, and points at the wrong
                // one. Not `SOLVE_CAPTCHA`: reCAPTCHA v3 is invisible and the user is not asked to
                // solve anything. Not `WAIT_FOR_COUNTDOWN`: the timer exists in their JavaScript
                // (`js-timer-countdown`) but none of the sampled pages contains it, so `rq_key`
                // fires immediately.
                hint = DownloadHint.CHOOSE_A_MIRROR,
                expectedSha256 = null,
                expectedSize = null,
            ),
        )
    }

    override suspend fun healthCheck(): StoreResult<Unit> = storeCall {
        fetcher.resolveRedirect(config.baseUrl).map { }
    }

    private suspend fun listing(ref: StoreAppRef): StoreResult<StoreListingDetail> {
        val stem = PdalifeRefs.stem(ref) ?: return StoreResult.Failure(StoreError.NotFound)
        return when (val fetched = fetcher.get(config.listingUrl(stem))) {
            is StoreResult.Success -> detailParser.parse(fetched.value.html, fetched.value.url, ref)
            is StoreResult.Failure -> fetched
            StoreResult.Unsupported -> StoreResult.Unsupported
        }
    }

    /**
     * The most recent version's octet.
     *
     * A listing with no versions is `NotFound` and not an empty list: it means the page is there
     * but offers nothing to download, and calling that case "resolved" would send the user to a
     * WebView opened on a URL that does not exist.
     */
    private suspend fun newestHash(ref: StoreAppRef): StoreResult<String> =
        listing(ref).map { detail ->
            detail.versions.firstOrNull()?.ref?.value
                ?: return StoreResult.Failure(StoreError.NotFound)
        }

    private companion object {
        /** The feed is a single window: it accepts no parameters and does not paginate. */
        const val FIRST_PAGE = 0
    }
}
