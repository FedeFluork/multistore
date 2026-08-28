package com.multistore.store.uptodown

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
 * The **real** uptodown, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network; this is the module's only one that does, and it is excluded
 * from `test`. Run it with `./gradlew :store:uptodown:canaryTest`.
 *
 * **What this canary does not do, and must not:** it downloads nothing. uptodown's file sits behind
 * a Turnstile, and obtaining it without running the challenge would be pretending to have solved
 * it. What is checked here is that the **metadata** are still readable — and they are what makes
 * the download verifiable when a person is the one pressing.
 */
@Tag("canary")
@DisplayName("Canary — uptodown (real network)")
class UptodownCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var uptodown: UptodownStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("uptodown-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        uptodown = UptodownStoreAdapter(config = UptodownConfig(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `the English search still returns recognisable listings`() = runTest {
        val page = uptodown.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        // The slug is read from the **subdomain**. If uptodown changed its URL scheme — moving to
        // a path, say — this line would be empty and no selector would fail: the parser would go on
        // reading cards it can no longer identify.
        assertThat(page.items.map { it.ref.value }.filter { it.isNotBlank() }).isNotEmpty()
        assertThat(page.items.first().title).isNotEmpty()
    }

    @Test
    fun `the listing still publishes packageName and SHA-256`() = runTest {
        val detail = uptodown.getAppDetails(StoreAppRef(APP_SLUG)).orFail("detail")

        assertThat(detail.summary.title).isNotEmpty()
        // uptodown redistributes `org.telegram.messenger.web`, not `org.telegram.messenger`: the
        // comparison is against that, and it is step 4 of the pipeline.
        assertThat(detail.summary.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(detail.versions).isNotEmpty()
        // **The line that makes uptodown verifiable.** It is the only user-assisted store of the
        // nine that publishes a hash: if it disappeared, the file taken with a tap would have
        // nothing left to be compared against, and the capability would have to drop to `NONE`.
        assertThat(detail.versions.count { it.sha256 != null }).isAtLeast(1)
        // `Android + 5.0`: the sign before the number. Without it, `minSdk` would be null
        // everywhere and every version would look compatible with every device.
        assertThat(detail.versions.mapNotNull { it.minSdk }).isNotEmpty()
    }

    @Test
    fun `the download stays assisted and declares the hash of the file it will serve`() = runTest {
        val resolution = uptodown.getDownloadLink(StoreAppRef(APP_SLUG)).orFail("download")

        val assisted = resolution as? DownloadResolution.UserAssisted
            ?: error(
                "uptodown declares USER_ASSISTED_ONLY but returned " +
                    "${resolution::class.simpleName}. If the Turnstile had disappeared, the thing " +
                    "to do would not be to change this test: it would be to reassess " +
                    "`downloadMode` and the row in the store table.",
            )
        assertThat(assisted.pageUrl).startsWith("https://")
        assertThat(assisted.expectedSha256).isNotNull()
    }

    @Test
    fun `the downloads chart still exists, with no numbers in the title`() = runTest {
        val page = uptodown.getTrending().orFail("classifica")

        assertThat(page.items).isNotEmpty()
        // uptodown writes the rank **inside** the title (`1. CapCut`). If the format changed, the
        // titles would start with a number and every shuffle of the chart would produce "new"
        // apps.
        assertThat(page.items.map { it.title }.filter { it.matches(Regex("""^\d+\..*""")) }).isEmpty()
    }

    @Test
    fun `the recently updated apps still exist`() = runTest {
        val page = uptodown.getRecent().orFail("recent")

        // It uses the same container as search: if `#content-list` disappeared from this page, the
        // fault would be indistinguishable from "no news" without this line.
        assertThat(page.items).isNotEmpty()
    }

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `UptodownSelectors` needs " +
                    "updating and the matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **uptodown is blocking us** (${e.kind}). The pages were not protected: " +
                    "check whether the Turnstile has been extended from the download page alone " +
                    "to the whole site, and if so reassess `networkTier` — the pages would move " +
                    "to tier 2 or 3 of the escalation ladder.",
            )
            is StoreError.RateLimited -> error(
                "$what: **uptodown is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Their ToS forbid automated access: rather than raising " +
                    "`permitsPerSecond`, it is better to lower it.",
            )
            StoreError.NotFound -> error(
                "$what: **NotFound**. On this store that nearly always means the URL scheme has " +
                    "changed: the listing lives at `{slug}.en.uptodown.com/android`, and a " +
                    "different subdomain gives a 404 without any selector failing.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "telegram"
        const val APP_SLUG = "telegram"
        const val PACKAGE_NAME = "org.telegram.messenger.web"
    }
}
