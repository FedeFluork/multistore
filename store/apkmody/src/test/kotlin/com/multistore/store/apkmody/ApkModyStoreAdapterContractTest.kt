package com.multistore.store.apkmody

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
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
 * apkmody against the contract every adapter must honour.
 *
 * The **third** store to extend it, and the first redistributing modified APKs: no hash, no
 * signature to compare against, and a single pre-install control able to say no. The contract did
 * not have to bend for this either.
 */
@DisplayName("Contract — apkmody")
class ApkModyStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: ApkModyTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var apkmody: ApkModyStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = ApkModyTestServer(server)
        val work = Files.createTempDirectory("apkmody-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkmody = ApkModyStoreAdapter(
            config = ApkModyConfig(baseUrl = fake.baseUrl, downloadHost = DOWNLOAD_HOST),
            clients = clients,
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = apkmody

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.APP_PATH)

    /**
     * A ref with the **right shape** that does not exist.
     *
     * The contract's default would be rejected by ref validation without a single request: the test
     * would pass while proving the validation, not the 404. With this the page is really requested,
     * and the parser has to cope with the 226 KB of full 404 page apkmody returns.
     */
    override val missingRef: StoreAppRef = StoreAppRef("apps/qzxvnpwmklj-does-not-exist")

    override val existingVersionRef: VersionRef = VersionRef(Fixtures.OLD_VERSION_SEGMENT)

    // --- Beyond the contract: what holds for this store only -------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        apkmody.search(queryWithResults)
        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        sent.forEach { assertThat(it).isEqualTo(ApkModyConfig.DEFAULT_USER_AGENT) }
    }

    @Test
    @DisplayName("the second search page is empty and costs no request")
    fun secondPageIsEmptyWithoutFetching() = runTest {
        apkmody.search(queryWithResults, page = 0).expect()
        val afterFirst = fake.received.size

        val second = apkmody.search(queryWithResults, page = 1).expect()

        // A page parameter returns the same bytes as the first page and the path form answers 404:
        // pagination does not exist. The request count is the half that matters — the empty page
        // must cost zero network.
        assertThat(second.items).isEmpty()
        assertThat(second.hasMore).isFalse()
        assertThat(fake.received.size).isEqualTo(afterFirst)
    }

    @Test
    @DisplayName("a search with no results does not collect the footer's apps")
    fun emptySearchDoesNotPickUpTheFooter() = runTest {
        // The premise is measured, not asserted: the "no results" page **does** contain links to
        // real apps. If the footer ever disappeared, this test would keep passing while proving
        // nothing, and the line below is what would notice.
        assertThat(Fixtures.html(Fixtures.SEARCH_EMPTY)).contains("/${Fixtures.APP_PATH}\"")

        val page = apkmody.search(queryWithoutResults).expect()

        assertThat(page.items).isEmpty()
    }

    @Test
    @DisplayName("the download is the file on the CDN, not the advert next to it")
    fun downloadIsTheFileAndNotTheAdvert() = runTest {
        val resolution = apkmody.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // The download list has two anchors with the same markup and the same icon: the real file
        // and apkmody's own installer. It is an `.apk` like the other — only the host tells them
        // apart, which is why the host is a configuration field and not a parser detail.
        assertThat(java.net.URI(resolution.url).host).isEqualTo(DOWNLOAD_HOST)
        assertThat(resolution.url).doesNotContain("apkmody-")
        assertThat(resolution.fileName).endsWith(".apk")
        // The CDN path contains the package name, and this is the second place apkmody declares
        // it: were it not the listing's, the file would not be this app's.
        assertThat(resolution.url).contains("/packages/${Fixtures.APP_PACKAGE}/")
    }

    @Test
    @DisplayName("a history version resolves to its own file, not to the current one")
    fun anOlderVersionResolvesToItsOwnFile() = runTest {
        val resolution = apkmody.getDownloadLink(existingRef, existingVersionRef)
            .expect() as DownloadResolution.Direct

        // The version ref **is** the path fragment serving that version: ignored, every download
        // would return the current version and the history would be a decorative menu. The
        // difference shows in the file name.
        assertThat(resolution.fileName).contains(Fixtures.OLD_VERSION_NAME)
        assertThat(resolution.fileName).doesNotContain(Fixtures.APP_LATEST_VERSION)
    }

    @Test
    @DisplayName("every version has a distinct ref")
    fun versionRefsAreDistinct() = runTest {
        val versions = apkmody.getVersions(existingRef).expect()

        assertThat(versions).hasSize(HISTORY_ENTRIES)
        // `app_versions` has a unique constraint on `(listing_id, version_ref)`: a non-unique
        // discriminator silently makes every version but the last written disappear. On apkcombo
        // that really happened, and the listing said "no installable package" in front of a page
        // offering five.
        assertThat(versions.map { it.ref }.toSet()).hasSize(versions.size)
    }

    @Test
    @DisplayName("the version code is on the current version and not invented on the others")
    fun onlyTheCurrentVersionCarriesItsVersionCode() = runTest {
        val versions = apkmody.getVersions(existingRef).expect()

        val current = versions.first { it.versionName == Fixtures.APP_LATEST_VERSION }
        // The version code lives **only** in the file name, and the current file is linked at the
        // top of the history page: it is the only row that can have it without an extra request.
        // And it is the one that matters, because it is where the update decision is made.
        assertThat(current.versionCode).isEqualTo(Fixtures.APP_LATEST_VERSION_CODE)

        // The others declare it **null**, not zero and not derived from the name: any invented
        // rule would give a number bearing no relation to the real one.
        versions.filter { it.versionName != Fixtures.APP_LATEST_VERSION }
            .forEach { assertThat(it.versionCode).isNull() }
    }

    @Test
    @DisplayName("it declares no expected size, because apkmody rounds it")
    fun doesNotDeclareARoundedSizeAsAnExpectation() = runTest {
        val resolution = apkmody.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // Measured with a HEAD on the CDN: the rounded figure is 158,314,004 bytes in binary
        // units, the file delivers 158,310,989. Three thousand bytes are enough for the download
        // engine to declare a finished connection dropped.
        assertThat(resolution.expectedSize).isNull()
        // The approximation stays where it does no harm: the listing shows it before starting.
        assertThat(apkmody.getVersions(existingRef).expect().mapNotNull { it.sizeBytes }).isNotEmpty()
    }

    @Test
    @DisplayName("search results carry no invented icon")
    fun searchResultsDoNotInventAnIcon() = runTest {
        val page = apkmody.search(queryWithResults).expect()

        // The card's image is a **cover** at 360×180: of twenty results eighteen are the site's
        // placeholder and the other two are YouTube frames. Putting it in the icon field would mean
        // showing a video frame in place of the icon — worse than nothing, because it looks like
        // data.
        assertThat(page.items.mapNotNull { it.iconUrl }).isEmpty()
        // The real icon does exist, on the listing.
        assertThat(apkmody.getAppDetails(existingRef).expect().summary.iconUrl).isNotNull()
    }

    @Test
    @DisplayName("a listing that contradicts itself about the package is not served")
    fun aListingThatContradictsItselfIsRejected() = runTest {
        // First it is checked that in the healthy state the listing comes out: without that, the
        // test would pass with an adapter that refuses everything.
        assertThat(apkmody.getAppDetails(existingRef).expect().summary.packageName)
            .isEqualTo(Fixtures.APP_PACKAGE)

        // Then the contradiction, built from **two real pages**: one app's listing and another
        // app's history, whose file lives under a different package path. It is the substitution
        // the hard block at step 4 of the pipeline exists for. That block would fire anyway, but
        // **after** 135 MB of download: here it costs nothing and arrives first.
        fake.overrides["/${Fixtures.APP_PATH}/history"] = Fixtures.HISTORY_OTHER_APP
        val result = apkmody.getAppDetails(existingRef)

        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }

    private companion object {
        const val DOWNLOAD_HOST = "cdn.topmongo.com"
        const val HISTORY_ENTRIES = 4
    }
}
