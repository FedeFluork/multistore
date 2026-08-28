package com.multistore.store.apkcombo

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
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
 * apkcombo for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * The fixtures freeze the markup of the day they were captured: a green suite over them says
 * nothing about what the site answers today. This is the only test in the module that touches the
 * network, and it is excluded from `test`. Run it with `./gradlew :store:apkcombo:canaryTest`,
 * which CI runs overnight **without blocking** anything.
 *
 * ### What it must say when it fails
 *
 * A canary's value is entirely in its message: "apkcombo is unreachable" and "apkcombo changed its
 * markup" lead to two different jobs, and a bare success assertion does not tell them apart. Each
 * check therefore separates the two cases — a parse failure names the selector to rewrite, a
 * network error does not.
 */
@Tag("canary")
@DisplayName("Canary — apkcombo (real network)")
class ApkComboCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var apkcombo: ApkComboStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("apkcombo-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkcombo = ApkComboStoreAdapter(config = ApkComboConfig(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results with a packageName`() = runTest {
        val page = apkcombo.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        // The `packageName` comes from the URL's second segment: if it disappeared, the
        // `providesPackageName = true` capability would become a lie and cross-store identity would
        // rest on the title alone.
        assertThat(page.items.mapNotNull { it.packageName }).isNotEmpty()
    }

    @Test
    fun `the listing still exposes version and version code`() = runTest {
        val detail = apkcombo.getAppDetails(StoreAppRef(APP_PATH)).orFail("detail")

        assertThat(detail.summary.title).isNotEmpty()
        assertThat(detail.summary.packageName).isEqualTo(PACKAGE_NAME)
        // The version code is only in the information table, inside a blurred span. It is the
        // listing's most fragile field and the one without which the anti-downgrade rule does not
        // work.
        assertThat(detail.summary.latestVersionCode).isNotNull()
        assertThat(detail.versions).isNotEmpty()
    }

    @Test
    fun `an app with several variants keeps more than one`() = runTest {
        val detail = apkcombo.getAppDetails(StoreAppRef(MULTI_VARIANT_PATH)).orFail("detail")

        // One app publishes five APKs and an XAPK on apkcombo, **all with the same version
        // code**. While the version ref was based on that, the unique constraint saved only one —
        // and that one was the XAPK, which the app could not install: the listing said "no
        // installable package" in front of a page offering five. The defect passed every
        // fixture-based test and was found by the device.
        assertThat(detail.versions.size).isGreaterThan(1)
        assertThat(detail.versions.map { it.ref }.toSet()).hasSize(detail.versions.size)
        assertThat(detail.versions.any { it.artifactType == ArtifactType.APK }).isTrue()
    }

    @Test
    fun `the download still resolves a signed URL`() = runTest {
        val resolution = apkcombo.getDownloadLink(StoreAppRef(APP_PATH)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("apkcombo declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(direct.fileName).isNotEmpty()
        // The R2 signature lasts four hours. If it disappeared, a cached resolution would come
        // back 403 and look like a store block.
        assertThat(direct.expiresAt).isNotNull()
    }

    @Test
    fun `the new-releases feed still exists and carries the packageName`() = runTest {
        val page = apkcombo.getRecent().orFail("new-releases feed")

        // If the feed disappeared, the published document would lose its richest source and Home
        // would only notice at the next publication. The minimum count is deliberately low: a feed
        // is a window, and how wide it is depends on how much the store published that day.
        assertThat(page.items.size).isAtLeast(MIN_FEED_ITEMS)
        // The property that makes this source different from the other three: each entry's URL is
        // `/{slug}/{packageName}/`. If its shape changed, the entries would remain but without the
        // package — and step 4 of pre-install verification would lose its comparison.
        assertThat(page.items.count { it.packageName != null }).isEqualTo(page.items.size)
        // The bracketed prefix belongs to the feed: were it to appear in the titles, every app on
        // Home would be a new app to the identity matcher.
        assertThat(page.items.filter { it.title.startsWith("[") }).isEmpty()
    }

    /**
     * The value, or a message saying **which of the two failures** happened.
     *
     * This is what makes a canary useful: whoever reads the issue opened overnight must be able to
     * tell from the first line whether to rewrite a selector or just retry tomorrow.
     */
    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `ApkComboSelectors` needs " +
                    "updating and the matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **apkcombo is blocking us** (${e.kind}). This had never happened: check " +
                    "whether it has introduced anti-bot protection and reassess its tier and " +
                    "risk in the store table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **apkcombo is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: it is the canary asking too much, or " +
                    "another client from the same egress. Before touching the adapter, look at " +
                    "`permitsPerSecond`.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        /** A feed is a window: how wide it is depends on how much the store published. */
        const val MIN_FEED_ITEMS = 10

        const val QUERY = "telegram"
        const val APP_PATH = "telegram/org.telegram.messenger"
        const val PACKAGE_NAME = "org.telegram.messenger"

        /** Five APKs and one XAPK, **all with the same version code**: see the test above. */
        const val MULTI_VARIANT_PATH = "duckduckgo-privacy-browser/com.duckduckgo.mobile.android"
    }
}
