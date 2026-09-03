package com.multistore.store.pdalife

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.PageFetcher
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **real** pdalife, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network; this is the module's only one that does, and it is excluded
 * from `test`. Run it with `./gradlew :store:pdalife:canaryTest`.
 *
 * A canary's value is in its message: "pdalife is unreachable", "it has changed markup" and "it is
 * rate-limiting us" lead to three different jobs, and whoever reads the issue at four in the
 * morning has to tell from the first line.
 *
 * ### There is a fourth case here, and no other store has it
 *
 * One test watches **the reCAPTCHA**, i.e. the reason this store is `USER_ASSISTED_ONLY`. If it
 * disappeared one day, that would not be a fault: it would be the news that the download can be
 * resolved automatically, and that the capability needs revisiting. A canary checking only what
 * must keep working would never say so.
 */
@Tag("canary")
@DisplayName("Canary — pdalife (real network)")
class PdalifeCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var config: PdalifeConfig
    private lateinit var pdalife: PdalifeStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("pdalife-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        config = PdalifeConfig()
        pdalife = PdalifeStoreAdapter(config = config, clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results, and Android only`() = runTest {
        val page = pdalife.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        assertThat(page.items.size).isAtLeast(MIN_RESULTS)
        assertThat(page.items.first().title).isNotEmpty()
        // The operating-system filter lives in the selector. If `a.color-android` changed name, the
        // result would not be an empty search — it would be a search returning **iOS and PSP too**,
        // i.e. listings from which nothing installable can be downloaded.
        page.items.forEach { assertThat(it.ref.value).contains(ANDROID_MARKER) }
    }

    @Test
    fun `the empty search is still empty, and not a ParseFailure`() = runTest {
        val page = pdalife.search(EMPTY_QUERY).orFail("empty search")

        // The page with no results contains a `li.catalog-item` all the same, with "Oops, maybe try
        // another request?". If the selector lost the second class, this call would fail with
        // ParseFailure **and would open the breaker on every search with no results**.
        assertThat(page.items).isEmpty()
        assertThat(page.hasMore).isFalse()
    }

    /**
     * The search paginates, and the two pages really are different pages.
     *
     * **Two of the three assertions here used to contradict each other about what pdalife is
     * allowed to publish.** `hasMore` comes from `data-max_page`, so requiring it is a claim about
     * their catalogue still holding more than twenty rows for this query — measured 03/09/2026:
     * page 1 gives 18 Android rows of 20, page 2 gives 12, page 3 is empty. And requiring page 2
     * to be non-empty is worse in kind: it forbids a page with **no Android rows at all**, which
     * this store does legitimately produce and which this very module has committed as a fixture
     * — `search-other-os.html.gz`, "20 results, none Android". A canary must not forbid the shape
     * its own fixture documents as normal.
     *
     * So both are premises now, checked separately and skipping rather than failing, and the
     * invariant that stays a hard assertion is the only one that is ours: **the second page is not
     * the first**. That is the defect worth catching — DLE-style engines answer page 1 for a
     * request missing a parameter, and the symptom is an infinite scroll showing the same rows
     * for ever, with no error anywhere.
     */
    @Test
    fun `search still paginates, and declares it`() = runTest {
        val first = pdalife.search(QUERY, page = 0).orFail("search p.1")

        if (!first.hasMore) {
            abort<Nothing>(
                "search p.1: **the premise expired, and nothing is broken.** pdalife declares the " +
                    "page count in `data-max_page` and now says there is only one page for " +
                    "`$QUERY`, so there is no second page to compare against and pagination went " +
                    "unverified. That is their catalogue, not our parser: re-anchor `QUERY` to a " +
                    "term with more than a page of Android results if it stays this way.",
            )
        }

        val second = pdalife.search(QUERY, page = 1).orFail("search p.2")
        if (second.items.isEmpty()) {
            abort<Nothing>(
                "search p.2: **an empty second page is legitimate here, so this proves nothing.** " +
                    "pdalife mixes iOS and PSP into the same result list and the adapter drops " +
                    "every row whose ref is not `-android-a…`; a full page of non-Android rows is " +
                    "real and committed as `search-other-os.html.gz` (20 results, none Android). " +
                    "Pagination therefore went unverified tonight rather than failing. If it " +
                    "recurs, re-anchor `QUERY`.",
            )
        }
        // The one claim that is about us: page 2 is a different page.
        assertThat(second.items.map { it.ref }).containsNoneIn(first.items.map { it.ref })
    }

    @Test
    fun `the listing still exposes its microdata, and the package is the app's`() = runTest {
        val detail = pdalife.getAppDetails(StoreAppRef(APP_REF)).orFail("detail")

        // **The invariant is that the page's own headline did not win, not that the name is
        // "Telegram".** The equality that stood here asserted pdalife's chosen display name, which
        // is theirs to change — their `<title>` already reads `Telegram v9.7.3 APK download for
        // Android` — and it bought nothing the rest of this test does not deliver harder: the
        // package equality below is the real identity check, and `document.text` raises a
        // `ParseFailure` naming the selector if the title element is missing, which is a better
        // message than any equality here could produce.
        //
        // What is checked instead is that the title does not carry the version, which is what
        // would happen if the parser ever fell back to the page `<title>`. It is immune to a
        // rename and it is not an assertion about their vocabulary.
        assertThat(detail.summary.title).isNotEmpty()
        detail.summary.latestVersionName?.takeIf { it.contains('.') }?.let { version ->
            assertThat(detail.summary.title).doesNotContain(version)
        }
        assertThat(detail.summary.developer).isNotEmpty()
        assertThat(detail.summary.rating).isNotNull()
        assertThat(detail.screenshots).isNotEmpty()
        assertThat(detail.versions).isNotEmpty()

        // **The check worth more than all the others put together.** The page's first Google Play
        // link is an advert, on every sampled listing: if the `.game-download__stores` container
        // changed name, the parser would not fail — the listing would come out with
        // `packageName = null`, and the assisted path's only defence would vanish silently. That it
        // is Telegram's and not some other is what separates "I read it" from "I read something".
        assertThat(detail.summary.packageName).isEqualTo(APP_PACKAGE)
    }

    @Test
    fun `the download is still assisted, and the first hop still leads away`() = runTest {
        val resolution = pdalife.getDownloadLink(StoreAppRef(APP_REF)).orFail("download")

        val assisted = resolution as? DownloadResolution.UserAssisted
            ?: error(
                "pdalife declares USER_ASSISTED_ONLY but returned " +
                    "${resolution::class.simpleName}",
            )
        // **Both of these are guaranteed by construction**, and are kept as documentation of the
        // shape rather than as defences: `PdalifeConfig.downloadUrl` composes
        // `{baseUrl}/dwn/{hash}.html`, so an https URL containing `/dwn/` is what that function
        // returns for any input at all. The live claim of this test is the cast above — that the
        // store is still `USER_ASSISTED_ONLY` — plus the assertion underneath, which is the only
        // one here that reads something off the page.
        assertThat(assisted.pageUrl).startsWith("https://")
        assertThat(assisted.pageUrl).contains("/dwn/")

        // The hash **was parsed from the page**. It is the one part of that URL pdalife supplies,
        // and the only way this call can produce something useless without failing outright: a
        // blank or malformed segment would give a first hop that resolves to nothing, which the
        // assisted screen would show the user as an error.
        val hash = assisted.pageUrl.substringAfterLast("/dwn/").removeSuffix(".html")
        assertThat(hash).matches("[0-9a-f]{6,}")
    }

    @Test
    fun `the RSS feed still exists and is Android only`() = runTest {
        val page = pdalife.getRecent().orFail("news feed")

        assertThat(page.items).isNotEmpty()
        // pdalife mixes iOS and PSP into every list on the site **except** this one. The real
        // defence is the ref — a stem without `-android-` cannot be built — and here we check it
        // holds against the real feed.
        // **This line is guaranteed by construction, and saying so is the point.** `PdalifeRefs`
        // only ever builds a ref that matches `STEM` — `[a-z0-9][a-z0-9-]*-android-a\d+` — so a
        // row from another platform is not dropped by *this* assertion, it never becomes an item
        // at all. It is kept as documentation of where the filter really lives, not as a defence;
        // the observable symptom of that filter degrading is a **thinner** feed, which is what
        // the count below is for.
        page.items.forEach { assertThat(it.ref.value).contains(ANDROID_MARKER) }
        assertThat(page.items.size).isAtLeast(MIN_FEED_ITEMS)

        // The site's verb must not stay in the title — **and case-insensitively**, which is what
        // makes this an assertion rather than a tautology. `PdalifeFeedParser.stripDownloadPhrase`
        // is `substringBefore(feedTitleVerb)`, so the result cannot contain the lower-case verb by
        // construction: the check as written could never fail. What it *could* miss is the real
        // hazard — `substringBefore` is case-sensitive, so a capitalised `Скачать` survives the
        // cut untouched, and pdalife writes that form in its own button labels. Reading the verb
        // from the config rather than from a literal also keeps this aligned with a `parsers.json`
        // that changes it, which is the whole reason it lives in the config.
        val verb = config.selectors.feedTitleVerb
        val unstripped = page.items.map { it.title }.filter { it.contains(verb, ignoreCase = true) }
        assertThat(unstripped).isEmpty()
    }

    /**
     * The reCAPTCHA is still there, i.e. the reason this store is assisted is still there.
     *
     * It is not a regression test: it is a test that **hopes to fail one day**. If `mobdisc.com`
     * stopped loading reCAPTCHA v3, the download would become resolvable with one request and
     * `DownloadMode` should go back to `DIRECT` — an improvement nobody would ever notice, because
     * everything would keep working as before.
     *
     * That the page loads **as well** is information: if the first hop stopped redirecting, or the
     * other domain disappeared, the assisted screen would take the user to an error and the canary
     * would say so first.
     */
    @Test
    fun `the second hop still loads reCAPTCHA v3`() = runTest {
        val resolution = pdalife.getDownloadLink(StoreAppRef(APP_REF)).orFail("download")
        val pageUrl = (resolution as DownloadResolution.UserAssisted).pageUrl

        val fetcher = PageFetcher(
            clients.forStore(
                com.multistore.core.model.StoreId.PDALIFE,
                com.multistore.core.network.http.StoreNetworkProfile(
                    userAgent = config.userAgent,
                    permitsPerSecond = config.permitsPerSecond,
                    burst = config.burst,
                ),
            ),
        )
        val landing = fetcher.get(pageUrl).orFail("second hop")

        // The first hop is a 301 towards a different domain: `PageFetcher` follows it, so the final
        // URL is no longer pdalife's.
        assertThat(landing.url).doesNotContain(PdalifeConfig.HOST)

        // **The gate, not its identity.** What makes this store `USER_ASSISTED_ONLY` is that the
        // landing page runs reCAPTCHA at all, and that is the one thing asserted hard. The two
        // lines that used to sit here pinned a **site key** and a **CSS class** on `mobdisc.com` —
        // a third party whose HTML no production code reads, since `getDownloadLink` returns
        // pdalife's own `/dwn/{hash}.html` and the WebView intercepts whatever download the page
        // starts. A rotated reCAPTCHA registration, a restyled button, or pdalife switching
        // download provider would each flip them while changing nothing about the capability, and
        // the class doc above would then send the reader to set `downloadMode` back to `DIRECT` —
        // i.e. to make the download automatic on a page that still has a human gate.
        assertThat(landing.html).contains(RECAPTCHA_SCRIPT)

        // The two premises, kept apart and reported as premises. They are worth watching — the key
        // is what a future implementation would need, and the disabled button is how the page
        // signals that the token has not arrived — but neither is the reason the capability
        // exists, so neither may fail the run.
        if (!landing.html.contains(RECAPTCHA_KEY)) {
            abort<Nothing>(
                "second hop: **the gate is still there and the site key changed.** " +
                    "`$RECAPTCHA_SCRIPT` is still being loaded from `${hostOf(landing.url)}`, so " +
                    "this store stays `USER_ASSISTED_ONLY` and **nothing needs fixing in the " +
                    "adapter**. What expired is a note: `RECAPTCHA_KEY` no longer appears, which " +
                    "means the registration was rotated or the provider changed. Update the " +
                    "constant and the measurement in the store table.",
            )
        }
        if (!landing.html.contains(INACTIVE_BUTTON)) {
            abort<Nothing>(
                "second hop: **the gate is still there and the button markup changed.** The real " +
                    "button used to start disabled (`$INACTIVE_BUTTON`) until the token arrived. " +
                    "That class is gone, which is a fact about `mobdisc.com`'s theme and not " +
                    "about us: no production code reads it. It matters only to whoever next reads " +
                    "the note about two of the three download buttons being adverts — recapture " +
                    "the `download.html.gz` fixture and update that note.",
            )
        }
    }

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `PdalifeSelectors` needs " +
                    "updating and the matching fixture recapturing. Careful: on pdalife " +
                    "positional selectors are forbidden — the order of the advert slots is " +
                    "randomised server-side.",
            )
            is StoreError.Blocked -> error(
                "$what: **pdalife is blocking us** (${e.kind}). This had never happened: " +
                    "Cloudflare is there but in passive CDN mode, and okhttp, curl and no UA at " +
                    "all all received 200. Check whether it has switched on a challenge for " +
                    "reads and reassess `networkTier` and the risk column in the store table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **pdalife is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: look at `permitsPerSecond`. Their " +
                    "`robots.txt` declares no `Crawl-delay`, so the value we use is a choice of " +
                    "ours and can be lowered.",
            )
            // Six checks share this branch and only two of them ever ask for a listing, so the
            // sentence that stood here — "`telegram-android-a14523` is no longer on pdalife" —
            // was wrong about the **subject** four times out of six. The subject is what the
            // reader acts on: it sent them to `PdalifeRefs` when the thing to change was
            // `slugify`, `searchUrl` or `RECENT_FEED_PATH`. pdalife's engine 404s aggressively by
            // design, so this branch is reached often enough for that to matter.
            StoreError.NotFound -> error(
                "$what: **404, and which address it was decides the job.** For `search`, `empty " +
                    "search` or `search p.N`: nothing about a listing is involved — the query is " +
                    "slugified into the **path** (`/search/{slug}/`), so a 404 means `slugify` " +
                    "or the search URL shape, and note that a query that cannot be slugified " +
                    "404s by design (`c++` does). For `news feed`: `RECENT_FEED_PATH` " +
                    "(`${PdalifeConfig.RECENT_FEED_PATH}`) has moved. For `detail` or " +
                    "`download`: `$APP_REF` is gone or has been re-slugged — a listing's URL " +
                    "carries the platform (`$ANDROID_MARKER`), and if pdalife changed that every " +
                    "ref would stop being valid at once, which would show up as **all six** " +
                    "checks failing rather than one. This is not the uptodown case: pdalife's " +
                    "Cloudflare is a passive CDN and a refusal would arrive as 403 or 451 " +
                    "(`Blocked`), so nothing here is skipped.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    private companion object {
        const val QUERY = "minecraft"
        const val EMPTY_QUERY = "zzqxwvnbtklmj"
        const val APP_REF = "telegram-android-a14523"
        const val APP_PACKAGE = "org.telegram.messenger"
        const val MIN_RESULTS = 5

        /** What `PdalifeRefs.STEM` requires, and therefore what every valid ref contains. */
        const val ANDROID_MARKER = "-android-a"

        /**
         * The feed is a hundred entries deep, so a thin one means the platform filter is losing
         * rows. Measured 03/09/2026: 100.
         */
        const val MIN_FEED_ITEMS = 40

        /** The gate itself: this is what makes the store `USER_ASSISTED_ONLY`. */
        const val RECAPTCHA_SCRIPT = "recaptcha/api.js"

        /** A premise, not an invariant: see the second-hop test. */
        const val RECAPTCHA_KEY = "6Lceo_8UAAAAAGKPGkR-373630tIcnJuXBybKBGp"

        /** A premise, not an invariant: `mobdisc.com`'s theme, which no production code reads. */
        const val INACTIVE_BUTTON = "b-download__button_state_inactive"
    }
}
