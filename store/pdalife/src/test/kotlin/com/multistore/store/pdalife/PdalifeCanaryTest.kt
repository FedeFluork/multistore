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
        page.items.forEach { assertThat(it.ref.value).contains("-android-a") }
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

    @Test
    fun `search still paginates, and declares it`() = runTest {
        val first = pdalife.search(QUERY, page = 0).orFail("search p.1")
        val second = pdalife.search(QUERY, page = 1).orFail("search p.2")

        assertThat(first.hasMore).isTrue()
        assertThat(second.items).isNotEmpty()
        assertThat(second.items.map { it.ref }).containsNoneIn(first.items.map { it.ref })
    }

    @Test
    fun `the listing still exposes its microdata, and the package is the app's`() = runTest {
        val detail = pdalife.getAppDetails(StoreAppRef(APP_REF)).orFail("detail")

        assertThat(detail.summary.title).isEqualTo(APP_TITLE)
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
        assertThat(assisted.pageUrl).startsWith("https://")
        assertThat(assisted.pageUrl).contains("/dwn/")
    }

    @Test
    fun `the RSS feed still exists and is Android only`() = runTest {
        val page = pdalife.getRecent().orFail("news feed")

        assertThat(page.items).isNotEmpty()
        // pdalife mixes iOS and PSP into every list on the site **except** this one. The real
        // defence is the ref — a stem without `-android-` cannot be built — and here we check it
        // holds against the real feed.
        page.items.forEach { assertThat(it.ref.value).contains("-android-a") }
        // The site's verb must not stay in the title.
        assertThat(page.items.filter { it.title.contains("скачать") }).isEmpty()
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
        assertThat(landing.html).contains(RECAPTCHA_KEY)
        // The real button starts disabled and stays that way until the token arrives.
        assertThat(landing.html).contains(INACTIVE_BUTTON)
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
            StoreError.NotFound -> error(
                "$what: **NotFound**. `$APP_REF` is no longer on pdalife, or the slug has " +
                    "changed. Note that a listing's URL contains the platform (`-android-`): if " +
                    "pdalife changed it, every ref would stop being valid.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "minecraft"
        const val EMPTY_QUERY = "zzqxwvnbtklmj"
        const val APP_REF = "telegram-android-a14523"
        const val APP_TITLE = "Telegram"
        const val APP_PACKAGE = "org.telegram.messenger"
        const val MIN_RESULTS = 5
        const val RECAPTCHA_KEY = "6Lceo_8UAAAAAGKPGkR-373630tIcnJuXBybKBGp"
        const val INACTIVE_BUTTON = "b-download__button_state_inactive"
    }
}
