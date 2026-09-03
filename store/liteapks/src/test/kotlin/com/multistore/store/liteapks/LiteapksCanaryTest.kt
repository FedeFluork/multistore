package com.multistore.store.liteapks

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.BlockKind
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.HtmlPage
import com.multistore.store.common.html.PageFetcher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **real** liteapks, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network; this is the module's only one that does, and it is excluded
 * from `test`. Run it with `./gradlew :store:liteapks:canaryTest`.
 *
 * A canary's value is in its message: "liteapks is unreachable", "it has changed markup" and "it is
 * rate-limiting us" lead to three different jobs, and whoever reads the issue at four in the
 * morning has to tell from the first line.
 *
 * ### Two tests here **hope to fail**, and that is not a figure of speech
 *
 * The first watches the transit permit: if `download.liteapks.dev` stopped demanding it, the
 * adapter would be adding a useless parameter to every URL, and nobody would notice because
 * everything would keep working. The second watches the "XAPKS Installer" advert: while it is
 * there, the `.app-stats` container defence has a reason to exist; the day it disappeared, the
 * advert could be dropped from the documentation instead of being cited as a 2026 measurement.
 *
 * **And they used to report that good news worst of all.** Both asserted a third party's identity
 * — a host name, an advertised package — with bare Truth assertions, so the day the hoped-for
 * change arrived the report would read `expected to contain …` under a heading about a broken
 * adapter, and the reader would be sent by this very docstring to remove a defence that was still
 * needed. Each is now split: the **invariant** (a gate exists; the container isolates one link)
 * fails, and the **premise** (which host, which advertiser) skips with a message saying the news
 * is good and what to rewrite. `canary.yml` describes these two tests in the old terms, and its
 * comment carries the same correction.
 */
@Tag("canary")
@DisplayName("Canary — liteapks (real network)")
class LiteapksCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var config: LiteapksConfig
    private lateinit var liteapks: LiteapksStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("liteapks-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(work, "cache")))
        config = LiteapksConfig()
        liteapks = LiteapksStoreAdapter(config = config, clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    /**
     * The search answers, **and paginates**.
     *
     * Pagination is the correction this adapter brings, and therefore the thing to watch: if
     * `paged` stopped being honoured, the second page's results would come back identical to the
     * first's and the search would look like it works.
     */
    @Test
    fun `search answers and really paginates`() = runTest {
        val first = liteapks.search(QUERY, page = 0).orFail("search p.1")
        val second = liteapks.search(QUERY, page = 1).orFail("search p.2")

        assertThat(first.items).isNotEmpty()
        assertThat(first.items.size).isAtLeast(MIN_RESULTS)
        assertThat(first.items.first().title).isNotEmpty()
        assertThat(first.items.first().rating).isNotNull()
        assertThat(first.hasMore).isTrue()
        assertThat(second.items).isNotEmpty()

        // **Not full disjointness, and the reason is a race we cannot win.** The two pages are
        // fetched about a second apart, and liteapks publishes continuously: one new matching post
        // landing in that window shifts the last row of page 1 onto page 2, and `containsNoneIn`
        // then fails with pagination working perfectly. That is the intermittent shape this
        // repository considers the expensive kind — green for weeks, red for one night, green again
        // by the time anyone looks.
        //
        // What is actually being defended against is `paged` not being honoured, whose symptom is
        // page 2 coming back **as** page 1 — an infinite scroll showing the same eighteen rows for
        // ever. One row of slippage does not resemble that, so a single overlap is tolerated and
        // an identical page still fails. Measured 03/09/2026: 18 rows per page, zero overlap.
        val overlap = second.items.map { it.ref }.toSet() intersect first.items.map { it.ref }.toSet()
        assertThat(overlap.size).isAtMost(MAX_PAGE_SLIPPAGE)
        assertThat(second.items.map { it.ref }).isNotEqualTo(first.items.map { it.ref })
    }

    /**
     * An empty search is empty, and **not** a `ParseFailure`.
     *
     * On this store the distinction is fragile by construction: the page with no results has no
     * `div#apps-grid`, so "zero results" and "dead selector" have the same shape. What separates
     * them is `h1#search-title`; if that id changed, every empty search would open the circuit
     * breaker on a perfectly healthy store.
     */
    @Test
    fun `the empty search is empty, not a ParseFailure`() = runTest {
        val page = liteapks.search(EMPTY_QUERY).orFail("empty search")

        assertThat(page.items).isEmpty()
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun `the listing still exposes its schema-org block, and the package is the app's`() = runTest {
        val detail = liteapks.getAppDetails(StoreAppRef(GAME_REF)).orFail("detail")

        // **The invariant is which element the title came from, not what it says.** The equality
        // that stood here pinned liteapks' display name; what the check is actually for is that
        // the title comes from the JSON-LD `SoftwareApplication` `name` and **not** from the
        // `<h1>`, which on this listing reads `Minecraft v1.26.50.26 MOD APK (Mega Menu, Auto
        // Attack)`. So the two things the `<h1>` would drag in are what get asserted: the version,
        // and the store's own "MOD APK" tail. Both are immune to liteapks renaming the app, and
        // both fail loudly if the parser ever falls back to the headline.
        assertThat(detail.summary.title).isNotEmpty()
        assertThat(detail.summary.title).doesNotContain(SEO_TAIL)
        detail.summary.latestVersionName?.takeIf { it.contains('.') }?.let { version ->
            assertThat(detail.summary.title).doesNotContain(version)
        }
        assertThat(detail.summary.developer).isNotEmpty()
        assertThat(detail.summary.rating).isNotNull()
        assertThat(detail.screenshots).isNotEmpty()
        assertThat(detail.versions).isNotEmpty()
        assertThat(detail.summary.lastUpdated).isNotNull()

        // **The check worth more than the others.** The package is read by step 4 of the
        // pre-install pipeline, and it is the only defence this store — which redistributes
        // modified APKs — offers on that path. That it is Minecraft's and not some other is what
        // separates "I read it" from "I read something": if the `.app-stats` container changed
        // name, the naive read would give the advert.
        //
        // The invariant and the premise are separated all the same, because they fail for
        // different reasons and only one of them is ours. liteapks publishes a real Play link on
        // 26 listings out of 31, so a listing losing its own link is a normal thing for this store
        // to do — and when that happens the package goes **null**, which is the container defence
        // working (the advert link is outside `.app-stats` and is correctly not read). A value
        // that is present but different is the one that would mean the container had been
        // breached, and that stays a hard failure.
        val packageName = detail.summary.packageName
        if (packageName == null) {
            abort<Nothing>(
                "detail: **the premise expired, and the defence held.** `$GAME_REF` no longer " +
                    "publishes a Google Play link inside `.app-stats`, so the package is null — " +
                    "which is the correct outcome, not a breach: the advert link " +
                    "(`$PLAY_ADVERT`) sits outside that container and was rightly not read. " +
                    "liteapks carries a real Play link on 26 listings of 31, so this happens. " +
                    "Re-anchor `GAME_REF` to a listing that has one, or this check stops " +
                    "exercising the container at all.",
            )
        }
        assertThat(packageName).isEqualTo(GAME_PACKAGE)
    }

    /**
     * The advert is still there, i.e. the container defence is still needed.
     *
     * This test **hopes to fail one day**. While it passes, "31 listings out of 31 carry a Play
     * link that is an advert" stays a fact and not a stale measurement; the day it disappeared, the
     * note in the parser should be rewritten rather than go on citing it.
     */
    @Test
    fun `the Play link outside the stats box is still an advert`() = runTest {
        val page = fetcher().get(config.listingUrl(GAME_REF)).orFail("raw listing")
        val document = HtmlPage.of(page.html, page.url)

        val all = document.all("a[href*=play.google.com]").mapNotNull { it.ownAbsUrlOrNull("href") }
        val inStats = document.all(config.selectors.detailPlayLink)
            .mapNotNull { it.ownAbsUrlOrNull("href") }

        // **The shape, not the advertiser.** What the container defence needs in order to be
        // necessary is that the page carries more than one Play link and that the one inside
        // `.app-stats` is not the one outside it. Which app is being promoted is liteapks' to
        // change at will, so pinning `io.apkmody.sai` made this test red the day they swap
        // installers — while the defence would be exactly as necessary as before, because some
        // other advert would have taken the slot.
        assertThat(all.size).isAtLeast(2)
        assertThat(inStats).hasSize(1)
        // The real point: the two are different links. If the container were breached, the read
        // would return whatever sits outside it.
        assertThat(all.toSet() - inStats.toSet()).isNotEmpty()

        // The advertiser is kept as a **premise**, because the measurement it supports — "31
        // listings out of 31 carry a Play link that is an advert" — is cited in the parser's own
        // notes and in the store table, and those should be rewritten rather than left standing
        // when it stops being true. This is the "hopes to fail" half of the test, and it now says
        // so instead of reporting `expected to contain …` and sending the reader to the wrong
        // conclusion.
        if (all.none { it.contains(PLAY_ADVERT) }) {
            abort<Nothing>(
                "raw listing: **the advert changed, and the defence is still needed.** The page " +
                    "still carries ${all.size} Play links with exactly one inside `.app-stats`, " +
                    "so nothing is broken and the container check keeps its reason to exist. " +
                    "What expired is the note: `$PLAY_ADVERT` is no longer the promoted " +
                    "installer. Update `PLAY_ADVERT`, and the \"31 of 31\" measurement in the " +
                    "parser notes and the store table, to whatever is there now — the links " +
                    "outside the container are: ${(all.toSet() - inStats.toSet()).joinToString()}.",
            )
        }
    }

    /**
     * The download is still **direct**, and the file is there.
     *
     * If a captcha appeared one day, `getDownloadLink` would go on returning a URL: what would say
     * so is `preflight`, which would receive a 403 from the challenge page instead of the file.
     * That is why the canary asks for both things.
     */
    @Test
    fun `the download is still direct and the file answers`() = runTest {
        val resolution = liteapks.getDownloadLink(StoreAppRef(GAME_REF)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("liteapks declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(direct.fileName).isNotEmpty()

        // A false `preflight` has two readings and only one of them is ours. See
        // [liteapksPreflightVerdict]: a 429 comes from a third party's storage quota — the adapter
        // documents `down.appsupload.com` as answering `too_many_requests` to everyone, root
        // included — and nothing here makes that request succeed. It is also the case the
        // `RateLimited` branch below was written for and could **never** reach, because 429 is not
        // one of the codes `ChallengeDetector` treats as a challenge, so it arrives as a
        // successful `HeadResult(429)` and `orFail` waves it through as a bare `false`.
        if (!liteapks.preflight(direct).orFail("preflight")) {
            val code = (fetcher().head(direct.url, direct.headers) as? StoreResult.Success)?.value?.code
            val where = hostOf(direct.url)
            when (liteapksPreflightVerdict(code)) {
                PreflightVerdict.SOMEONE_ELSES_QUOTA -> abort<Nothing>(
                    "preflight: **$where answered $SHARED_QUOTA_CODE, and that budget is not " +
                        "ours.** It is a third party's storage account serving part of liteapks' " +
                        "catalogue, and it answers `too_many_requests` to everybody — the same " +
                        "lesson as an1's `x-ratelimit-*` headers: a published number is not " +
                        "necessarily a number that concerns you. Lowering `permitsPerSecond` " +
                        "would slow every user down and change nothing here. **Skipped**, so the " +
                        "run's step summary is where this appears — and while it skips, the " +
                        "download resolution is going unverified.",
                )
                PreflightVerdict.WORTH_FAILING -> error(
                    "preflight: **the file did not answer** — `$where` said " +
                        "${code ?: "nothing readable"} for `${direct.fileName}` — and not in the " +
                        "one way that would be somebody else's quota ($SHARED_QUOTA_CODE). A 404 " +
                        "means the object has moved and `$GAME_REF` needs re-anchoring; a 5xx is " +
                        "that CDN erroring, worth a retry by hand first. Note the resolution " +
                        "itself succeeded, so this is **not** a markup change: the listing " +
                        "parsed and the slot page gave a link.",
                )
            }
        }
    }

    /**
     * **The transit permit is still needed, and this test proves it in both directions.**
     *
     * Checking only that the file answers 200 with the token would say nothing: it would answer 200
     * even if the gate had been removed. The test therefore also asks the opposite — **without** a
     * token the worker must answer 403 — which is the only way to say that parameter is still doing
     * something.
     *
     * If one day the 403 stopped arriving, the adapter would be adding a useless parameter to every
     * download URL: to be removed, along with half a page of documentation.
     */
    @Test
    fun `without a token the CDN still refuses, with one it accepts`() = runTest {
        val direct = liteapks.getDownloadLink(StoreAppRef(APP_REF)).orFail("download")
                as DownloadResolution.Direct
        // **The premise is that this reference still lands on a gated CDN**, and it is checked
        // against `config.tokenizedFileHosts` rather than one literal. Two reasons, both measured:
        // that set has **two** members — `download.liteapks.dev` and `download-old.liteapks.dev`
        // — so a literal match would call the second one ungated and fail while the adapter was
        // right; and which CDN a file lands on is liteapks' per-upload choice, with both outcomes
        // already visible in the committed fixtures (telegram gated, minecraft on
        // `down.appsupload.com` with no token at all). When the next Telegram upload lands
        // ungated, the two assertions that used to stand here would both fail **because the
        // adapter is correct** — it withholds the token precisely because the host is not gated —
        // and the standing invitation would be to delete a working defence.
        val host = hostOf(direct.url)
        if (host !in config.tokenizedFileHosts) {
            abort<Nothing>(
                "gate: **the premise expired, and the adapter is right.** `$APP_REF` now resolves " +
                    "to `$host`, which is not one of the CDNs that demand a transit permit " +
                    "(${config.tokenizedFileHosts.joinToString()}), so no token is attached — " +
                    "which is correct behaviour and not a fault. **Do not remove the token " +
                    "machinery on the strength of this**: that decision needs a reference that is " +
                    "still on a gated host. Re-anchor `APP_REF` to one, or if no file lands on " +
                    "those hosts any more, that is the news `canary.yml` describes and the " +
                    "removal can be made deliberately.",
            )
        }
        assertThat(direct.url).contains("token=")

        // **Removing the parameter, not truncating at it.** `substringBefore("?token=")` assumed
        // the file URL carries no query of its own, and got the dangerous direction wrong: with
        // any pre-existing parameter the "bare" URL would still carry the token, both requests
        // would be identical, the 403 would not arrive — and the test would report **that the gate
        // is gone**. That is the opposite of the truth, on the one test whose whole purpose is to
        // justify keeping the token. Measured 03/09/2026 the URL happens to carry `token` alone,
        // so the old form worked by luck rather than by construction.
        val bare = direct.url.toHttpUrlOrNull()
            ?.newBuilder()?.removeAllQueryParameters(TOKEN_PARAM)?.build()?.toString()
            ?: error("gate: the resolved URL is not parseable as a URL: ${direct.url}")
        assertThat(bare).doesNotContain(TOKEN_PARAM)
        val withoutToken = fetcher().head(bare, direct.headers)
        val withToken = fetcher().head(direct.url, direct.headers).orFail("HEAD con token")

        // The worker's 403 does not arrive here as `HeadResult(403)`: the escalation ladder
        // recognises it first as a block and translates it into `StoreError.Blocked`. That is the
        // right behaviour — a 403 is a block, not a missing file — and this test says so explicitly
        // because it is what separates "the gate is still there" from "the file is gone".
        val blocked = (withoutToken as? StoreResult.Failure)?.error
        assertThat(blocked).isInstanceOf(StoreError.Blocked::class.java)
        assertThat((blocked as StoreError.Blocked).kind).isEqualTo(BlockKind.FORBIDDEN)
        assertThat(withToken.isSuccessful).isTrue()
    }

    private fun fetcher(): PageFetcher = PageFetcher(
        clients.forStore(
            StoreId.LITEAPKS,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private suspend fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `LiteapksSelectors` needs " +
                    "updating and the matching fixture recapturing. Careful: half the listing is " +
                    "read from the schema.org `application/ld+json` block, not from the Tailwind " +
                    "classes — if that is the missing selector, the theme has stopped publishing " +
                    "it and the detail parser has to be rewritten, not a selector.",
            )
            is StoreError.Blocked -> error(
                "$what: **liteapks is blocking us** (${e.kind}). On this store the User-Agent " +
                    "alone decides between the page and a 403: check that " +
                    "`LiteapksConfig.DEFAULT_USER_AGENT` is still a plausible browser UA. If it " +
                    "is, Cloudflare has raised the threshold and tier 3 " +
                    "(`WebViewSilentResolver`) is needed, which no measured store has required " +
                    "so far. **And a third reading, which is not about us at all**: this " +
                    "pipeline runs from a datacentre while this project measures reachability " +
                    "from a consumer connection, and liteapks is the store of the nine most " +
                    "likely to refuse an egress that does not look like a browser — the whole " +
                    "reason `WebViewSilentResolver` exists is reports of real users on ordinary " +
                    "IPs getting \"forbidden\". So before changing anything, **repeat the same " +
                    "request from a consumer connection**. If it answers 200 there, nothing here " +
                    "is broken and the right action is none. Note this is why the check is not " +
                    "skipped the way uptodown's is: there the refusal wears a 404 and a root " +
                    "probe separates the cases, whereas here it wears a 403 and is " +
                    "indistinguishable from the block we would actually need to fix.",
            )
            is StoreError.RateLimited -> error(
                "$what: **liteapks is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: look at `permitsPerSecond`. Their " +
                    "`robots.txt` declares no `Crawl-delay`, so the value we use is a choice of " +
                    "ours. Careful: a 429 on a **file** URL can come from " +
                    "`down.appsupload.com`, which answers `too_many_requests` to everybody " +
                    "because it is their account's budget, not ours.",
            )
            // Six checks share this branch, and naming `GAME_REF` in all of them was wrong about
            // the **subject** most of the time — a 404 on `search p.2` is how liteapks answers a
            // request past the last page, and it would have reported that *minecraft* was gone.
            // The subject is what the reader acts on, so it is left to `$what` rather than
            // asserted.
            StoreError.NotFound -> error(
                "$what: **404, and which address it was decides the job.** For `search p.N`: this " +
                    "is how liteapks answers a page past the last one, so the question is whether " +
                    "`hasMore` should have stopped sooner — look at the `h1#search-title` total, " +
                    "which **saturates at 60** and is not the real count. For `detail` or `raw " +
                    "listing`: `$GAME_REF` has been re-slugged or has left the store. For " +
                    "`download`: mind that the listing's slug and the file page's slug **differ**, " +
                    "so if the file page is the missing one the \"Download APK\" button has " +
                    "changed shape rather than the app having gone. Note this is not the uptodown " +
                    "case: liteapks refuses an unwelcome egress with a 403, not a 404, so a 404 " +
                    "here really is about one address.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    private companion object {
        const val QUERY = "game"
        const val EMPTY_QUERY = "zzqxwvnbtklmj"
        const val MIN_RESULTS = 10

        const val GAME_REF = "minecraft"
        const val GAME_PACKAGE = "com.mojang.minecraftpe"

        /** Telegram: its files sit on the CDN that demands the transit permit. */
        const val APP_REF = "telegram"

        /**
         * The advertised installer, kept as a **premise** and not an invariant.
         *
         * Which app liteapks promotes is theirs to change; that the promotion exists at all is
         * what the container defence needs. See the advert test.
         */
        const val PLAY_ADVERT = "io.apkmody.sai"

        /** The store's SEO tail, which the JSON-LD name must not carry. */
        const val SEO_TAIL = "MOD APK"

        /** The query parameter carrying the transit permit. */
        const val TOKEN_PARAM = "token"

        /**
         * How much overlap between two pages is a publishing race rather than broken pagination.
         *
         * One row: the pages are fetched about a second apart and a new post shifts the boundary.
         * Two would begin to resemble a page that had not moved at all.
         */
        const val MAX_PAGE_SLIPPAGE = 1
    }
}
