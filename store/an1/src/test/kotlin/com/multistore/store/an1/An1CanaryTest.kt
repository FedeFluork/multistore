package com.multistore.store.an1

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * an1 for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network. This is the only one in the module that does, and it is
 * excluded from `test`. Run it with `./gradlew :store:an1:canaryTest`.
 *
 * A canary's value is in its message: "an1 is unreachable", "it changed markup" and "it is rate
 * limiting us" lead to three different jobs, and whoever reads the issue at four in the morning
 * should be able to tell from the first line.
 */
@Tag("canary")
@DisplayName("Canary — an1 (real network)")
class An1CanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var an1: An1StoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("an1-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        an1 = An1StoreAdapter(config = An1Config(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results`() = runTest {
        val page = an1.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        assertThat(page.items.first().title).isNotEmpty()
        // Modified entries carry an extra class and are half the results: if the selector became
        // an exact attribute comparison, search would keep working while **silently losing the
        // modified half** of the catalogue — which on this store is precisely why people use
        // it.
        assertThat(page.items.size).isAtLeast(MIN_RESULTS)
    }

    @Test
    fun `search still paginates`() = runTest {
        val first = an1.search(QUERY, page = 0).orFail("search p.1")
        val second = an1.search(QUERY, page = 1).orFail("search p.2")

        // DLE wants **two** parameters, and sending only one returns the first page. The way this
        // breaks is insidious: no error, just an infinite scroll always showing the same ten
        // apps.
        assertThat(second.items.map { it.ref }).containsNoneIn(first.items.map { it.ref })
    }

    @Test
    fun `the listing still exposes its microdata`() = runTest {
        val detail = an1.getAppDetails(StoreAppRef(APP_REF)).orFail("detail")

        assertThat(detail.summary.title).isEqualTo(APP_TITLE)
        // The title comes from the microdata name, not from the `<h1>` that reads "Download
        // Telegram 12.4.3 free on android". If the microdata disappeared, the fallback would
        // produce that sentence as the app's name in "My apps" and in notifications.
        assertThat(detail.summary.developer).isNotEmpty()
        assertThat(detail.versions).hasSize(1)
        assertThat(detail.versions.single().versionName).isNotEmpty()
    }

    @Test
    fun `the download is still the app's file, and still carries the hash`() = runTest {
        val resolution = an1.getDownloadLink(StoreAppRef(APP_REF)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("an1 declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(An1Config.DEFAULT_DOWNLOAD_HOSTS).contains(java.net.URI(direct.url).host)
        // Next to the real file, on the same page and on the **same host**, sits an1's own store
        // app. If the precise anchor disappeared and the parser fell back to a broader selector,
        // the user would install that.
        assertThat(direct.url).doesNotContain("an1store")

        // The hash is the only thing that makes an1 verifiable, and lives in an S3 header on some
        // objects (two of six sampled). On Telegram it was there, verified against the real bytes:
        // if it disappeared from here, the verification card would quietly switch to "hash not
        // published" with nobody noticing.
        assertThat(direct.expectedSha256).isNotNull()
        assertThat(direct.expectedSize).isNotNull()
    }

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `An1Selectors` needs updating " +
                    "and the matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **an1 is blocking us** (${e.kind}). This had never happened — an1 answers " +
                    "identically to any User-Agent, including none. Check whether it has " +
                    "introduced anti-bot protection and reassess its tier and risk in the store " +
                    "table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **an1 is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: before touching the adapter, look at " +
                    "`permitsPerSecond`. Note that `x-ratelimit-remaining` on the CDN is a " +
                    "**shared** budget, not ours: a 429 can arrive without us having consumed " +
                    "it.",
            )
            StoreError.NotFound -> error(
                "$what: **NotFound**. `$APP_REF` is no longer on an1, or the slug has changed — " +
                    "an1 renames slugs, and it does so halfway through the download redirect " +
                    "chain too. Opening the page by hand says which of the two.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "minecraft"
        const val APP_REF = "2971-telegram"
        const val APP_TITLE = "Telegram"
        const val MIN_RESULTS = 5
    }
}
