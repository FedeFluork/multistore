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
import org.junit.jupiter.api.AfterEach
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
        assertThat(second.items.map { it.ref }).containsNoneIn(first.items.map { it.ref })
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

        assertThat(detail.summary.title).isEqualTo(GAME_TITLE)
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
        assertThat(detail.summary.packageName).isEqualTo(GAME_PACKAGE)
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

        assertThat(all.size).isAtLeast(2)
        assertThat(all.any { it.contains(PLAY_ADVERT) }).isTrue()
        // Inside the container there is only the real one, and the advert never gets in.
        assertThat(inStats).hasSize(1)
        assertThat(inStats.single()).doesNotContain(PLAY_ADVERT)
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

        val reachable = liteapks.preflight(direct).orFail("preflight")
        assertThat(reachable).isTrue()
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
        // The reference app sits on the CDN that asks for the permit; if it changed host, the test
        // would no longer prove what it claims to prove, and this line declares that.
        assertThat(direct.url).contains(GATED_HOST)
        assertThat(direct.url).contains("token=")

        val bare = direct.url.substringBefore("?token=")
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

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
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
                    "so far.",
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
            StoreError.NotFound -> error(
                "$what: **NotFound**. `$GAME_REF` is no longer on liteapks, or the slug has " +
                    "changed. Note that the listing's slug and the file page's slug differ: if " +
                    "the second is the missing one, the \"Download APK\" button has changed " +
                    "shape.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "game"
        const val EMPTY_QUERY = "zzqxwvnbtklmj"
        const val MIN_RESULTS = 10

        const val GAME_REF = "minecraft"
        const val GAME_TITLE = "Minecraft"
        const val GAME_PACKAGE = "com.mojang.minecraftpe"

        /** Telegram: its files sit on the CDN that demands the transit permit. */
        const val APP_REF = "telegram"
        const val GATED_HOST = "download.liteapks.dev"

        const val PLAY_ADVERT = "io.apkmody.sai"
    }
}
