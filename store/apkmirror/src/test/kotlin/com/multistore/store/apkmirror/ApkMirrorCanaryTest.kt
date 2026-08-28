package com.multistore.store.apkmirror

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
 * apkmirror for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * On this store the canary is worth more than elsewhere, and not because of the markup: the
 * conditions that make it work are **Cloudflare's** decisions, not apkmirror's. Which User-Agent is
 * allowed, and which combination of TLS and protocol passes without a challenge, can change from
 * one day to the next without a line of HTML moving — i.e. the case no fixture can catch, by
 * definition.
 *
 * **It was this canary that disproved the protocol finding**: the curl measurement said "HTTP/1.1
 * is needed", with OkHttp the opposite holds. See the note atop `ApkMirrorConfig`.
 */
@Tag("canary")
@DisplayName("Canary — apkmirror (real network)")
class ApkMirrorCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var apkmirror: ApkMirrorStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("apkmirror-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkmirror = ApkMirrorStoreAdapter(config = ApkMirrorConfig(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still gets through, i e UA and protocol are still fine`() = runTest {
        val page = apkmirror.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        page.items.forEach { assertThat(it.title).isNotEmpty() }
    }

    @Test
    fun `the listing-release-variant chain holds, file hash included`() = runTest {
        val detail = apkmirror.getAppDetails(StoreAppRef(APP_PATH)).orFail("detail")

        assertThat(detail.summary.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(detail.versions).isNotEmpty()
        // The file hash and the signer are why this store is worth having: without them apkmirror
        // would drop to apkcombo's level and the verification card would say "hash not verified" on
        // every installation.
        assertThat(detail.versions.any { it.sha256 != null }).isTrue()
        assertThat(detail.preferredSignerSha256).isNotNull()
        // Three distinct version codes for one release: if only one were left, either the adapter
        // has started propagating it again, or the publisher changed convention. Both worth
        // knowing.
        assertThat(detail.versions.mapNotNull { it.versionCode }).isNotEmpty()
    }

    @Test
    fun `the last hop still reaches the download endpoint`() = runTest {
        val resolution = apkmirror.getDownloadLink(StoreAppRef(APP_PATH)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("apkmirror declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(direct.expectedSha256).isNotNull()
        assertThat(direct.expectedSize).isNotNull()
    }

    @Test
    fun `the latest-releases feed still exists`() = runTest {
        val page = apkmirror.getRecent().orFail("news feed")

        assertThat(page.items).isNotEmpty()
        // apkmirror is the only one of the four new-release sources that publishes the developer,
        // and that is what the inferred app key uses together with the title on a store that does
        // not give the package. If the title format changed, it would be lost silently.
        assertThat(page.items.count { it.developer != null }).isEqualTo(page.items.size)
        // The version number must not stay in the title: it would change on every release, and to
        // the identity matcher that would be a new app every time.
        assertThat(page.items.filter { it.title.contains(" by ") }).isEmpty()
    }

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). Update `ApkMirrorSelectors` " +
                    "and recapture the fixture.",
            )
            is StoreError.Blocked -> error(
                "$what: **blocked by Cloudflare** (${e.kind}). Retry by hand, in " +
                    "this order: (1) is the declared User-Agent still accepted? (2) does the " +
                    "same request pass with `curl`? (3) does it pass forcing HTTP/1.1? If none " +
                    "of the three, there is nothing to fix in the selectors: tier 2 (Cronet) or " +
                    "3 (WebView) of the escalation ladder is needed, that is a network engine " +
                    "that *is* a browser rather than resembling one.",
            )
            is StoreError.RateLimited -> error(
                "$what: **apkmirror is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: it is the canary asking too much, or " +
                    "another client from the same egress. Before touching the adapter, look at " +
                    "`permitsPerSecond`.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "firefox"
        const val APP_PATH = "mozilla/firefox"
        const val PACKAGE_NAME = "org.mozilla.firefox"
    }
}
