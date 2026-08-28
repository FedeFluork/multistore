package com.multistore.store.apkcombo

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreAdapterContractTest
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * apkcombo against the contract every adapter must honour.
 *
 * The **second** to extend it, and the proof the contract was worth having: F-Droid alone could
 * have shaped it around itself. Here the same base class runs against a store with no index, no
 * hash and no signature, and the points where the contract had to bend are exactly zero.
 */
@DisplayName("Contract — apkcombo")
class ApkComboStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: ApkComboTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var apkcombo: ApkComboStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = ApkComboTestServer(server)
        val work = Files.createTempDirectory("apkcombo-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkcombo = ApkComboStoreAdapter(
            config = ApkComboConfig(baseUrl = fake.baseUrl),
            clients = clients,
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = apkcombo

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.APP_PATH)

    // --- Beyond the contract: what holds for this store only -------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        apkcombo.search(queryWithResults)
        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        // The contract test checks the capability *declares* a UA. This checks the declared one
        // reaches the socket: between the two sits an interceptor, and that is where apkmirror
        // would hand us a 403 if it were skipped.
        sent.forEach { assertThat(it).isEqualTo(ApkComboConfig.DEFAULT_USER_AGENT) }
    }

    @Test
    @DisplayName("the second search page is empty and costs no request")
    fun secondPageIsEmptyWithoutFetching() = runTest {
        apkcombo.search(queryWithResults, page = 0).expect()
        val afterFirst = fake.received.size

        val second = apkcombo.search(queryWithResults, page = 1).expect()

        // apkcombo ignores the page parameter: the second page is identical to the first.
        // Returning it would give an infinite scroll over the same twenty results. The request
        // count is the half that matters: the empty page must cost zero network.
        assertThat(second.items).isEmpty()
        assertThat(second.hasMore).isFalse()
        assertThat(fake.received.size).isEqualTo(afterFirst)
    }

    @Test
    @DisplayName("a 404 page produces no invented results")
    fun notFoundPageYieldsNotFound() = runTest {
        fake.missing += "/${Fixtures.APP_PATH}/"

        val result = apkcombo.getAppDetails(existingRef)

        // apkcombo's 404 is a complete 55 KB page with menu and suggestions: if the parser found a
        // title in it, the app would show a listing for an app that does not exist.
        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("variants sharing a version code stay distinct versions")
    fun variantsSharingAVersionCodeKeepDistinctRefs() = runTest {
        val versions = apkcombo.getAppDetails(existingRef).expect().versions

        assertThat(versions).isNotEmpty()
        // **The premise is verified, not assumed**: if the fixture one day stopped having variants
        // sharing a version code, the invariant below would pass without proving anything.
        assertThat(versions.mapNotNull { it.versionCode }.distinct().size)
            .isLessThan(versions.size)

        // `app_versions` has a unique constraint on `(listing_id, version_ref)`. With the version
        // code as discriminator, saving four variants left **one** — the last written — and on one
        // app that was the group's only XAPK: the listing said "this store publishes no installable
        // package" in front of a page offering five. The defect passed every test in this module
        // and was found by the device; this test is what should have found it first.
        assertThat(versions.map { it.ref }.toSet()).hasSize(versions.size)
    }

    @Test
    @DisplayName("the download points at the signed file, not at the page hosting it")
    fun downloadResolvesToTheSignedUrl() = runTest {
        val resolution = apkcombo.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // The page's href wraps the signed URL: following it would work, but the real URL is
        // already in the query. If the fake server's host appeared here it would mean the adapter
        // is about to ask apkcombo for a redirect it can avoid.
        assertThat(resolution.url).startsWith("https://")
        assertThat(resolution.url).doesNotContain("/r2?")
        // The comparison is on the **host**, not on the whole string: apkcombo puts its own domain
        // inside the file name, and a substring check on the URL would fail for the wrong reason.
        assertThat(java.net.URI(resolution.url).host).doesNotContain(ApkComboConfig.HOST)
        assertThat(resolution.fileName).endsWith(".apk")
        assertThat(resolution.fileName).contains("Telegram")
        // The R2 signature lasts four hours: without an expiry, a cached resolution reused later
        // would give a 403 that looks like a store block.
        assertThat(resolution.expiresAt).isNotNull()
    }

    @Test
    @DisplayName("it declares no expected size, because apkcombo rounds it")
    fun doesNotDeclareARoundedSizeAsAnExpectation() = runTest {
        val resolution = apkcombo.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // apkcombo writes `119 MB`, rounded to the megabyte: 124,780,544 bytes against a real
        // 124,351,530. Read as an expectation, the download engine compares the **exact** size and,
        // finding fewer bytes, concludes the connection dropped — a finished file was declared
        // incomplete and the screen read "no connection" after 125 MB genuinely downloaded. An
        // inexact expectation is worse than none.
        assertThat(resolution.expectedSize).isNull()
        // The approximation stays where it does no harm: the listing shows it before the transfer
        // starts.
        val versions = apkcombo.getAppDetails(existingRef).expect().versions
        assertThat(versions.mapNotNull { it.sizeBytes }).isNotEmpty()
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }
}
