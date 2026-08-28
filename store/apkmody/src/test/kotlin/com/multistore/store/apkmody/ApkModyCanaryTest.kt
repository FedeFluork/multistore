package com.multistore.store.apkmody

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
 * apkmody for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network. This is the only one in the module that does, and it is
 * excluded from `test`. Run it with `./gradlew :store:apkmody:canaryTest`.
 *
 * A canary's value is in its message: "apkmody is unreachable", "it changed markup" and "it is rate
 * limiting us" lead to three different jobs, and a bare success assertion does not tell them apart.
 */
@Tag("canary")
@DisplayName("Canary — apkmody (real network)")
class ApkModyCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var apkmody: ApkModyStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("apkmody-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkmody = ApkModyStoreAdapter(config = ApkModyConfig(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results`() = runTest {
        val page = apkmody.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        assertThat(page.items.first().title).isNotEmpty()
        // The content kind comes from the path's first segment: were it to disappear, so would the
        // only app/game distinction this store publishes.
        assertThat(page.items.any { it.contentKind != com.multistore.core.model.ContentKind.UNKNOWN }).isTrue()
    }

    @Test
    fun `the listing still exposes the packageName`() = runTest {
        val detail = apkmody.getAppDetails(StoreAppRef(APP_PATH)).orFail("detail")

        assertThat(detail.summary.title).isNotEmpty()
        // **It is the field holding verification up on this store.** apkmody redistributes
        // modified APKs: no hash, no original signature to compare against. The `packageName` match
        // is the only pre-install step that can still say no here, and without this table row it is
        // gone.
        assertThat(detail.summary.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(detail.versions).isNotEmpty()
    }

    @Test
    fun `the history still carries the current version's version code`() = runTest {
        val versions = apkmody.getVersions(StoreAppRef(APP_PATH)).orFail("cronologia")

        assertThat(versions).isNotEmpty()
        assertThat(versions.map { it.ref }.toSet()).hasSize(versions.size)
        // The version code lives **only** inside the file name. If apkmody changed its naming
        // scheme, the anti-downgrade rule on this store would lose its point of comparison — and
        // no other part of the page would notice.
        assertThat(versions.mapNotNull { it.versionCode }).isNotEmpty()
    }

    @Test
    fun `the download still points at the CDN and not at the advert`() = runTest {
        val resolution = apkmody.getDownloadLink(StoreAppRef(APP_PATH)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("apkmody declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(java.net.URI(direct.url).host).isEqualTo(ApkModyConfig.DEFAULT_DOWNLOAD_HOST)
        // Next to the real file, in the same list and with the same markup, sits apkmody's own
        // installer. If the host filter fell away, the download would offer that.
        assertThat(direct.url).contains("/packages/$PACKAGE_NAME/")
    }

    @Test
    fun `the chart still exists, and comes from the structured-data list`() = runTest {
        val page = apkmody.getTrending().orFail("classifica")

        assertThat(page.items).isNotEmpty()
        // The SEO suffix belongs to the page, not to the app: were it kept, every entry on Home
        // would have a title that does not match its own listing's.
        assertThat(page.items.map { it.title }.filter { it.endsWith("Mod APK") }).isEmpty()
    }

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `ApkModySelectors` needs " +
                    "updating and the matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **apkmody is blocking us** (${e.kind}). This had never happened: check " +
                    "whether it has introduced anti-bot protection and reassess its tier and " +
                    "risk in the store table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **apkmody is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: before touching the adapter, look at " +
                    "`permitsPerSecond`.",
            )
            StoreError.NotFound -> error(
                "$what: **NotFound**. Two very different possibilities: the app is no longer on " +
                    "apkmody, or the listing and the file contradict each other on the package — " +
                    "the adapter refuses a listing that declares one package and serves another's " +
                    "file. Opening `$APP_PATH` by hand says which of the two.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "spotify"
        const val APP_PATH = "apps/spotify-pro"
        const val PACKAGE_NAME = "com.spotify.music"
    }
}
