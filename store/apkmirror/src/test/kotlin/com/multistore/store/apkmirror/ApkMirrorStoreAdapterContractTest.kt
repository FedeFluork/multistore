package com.multistore.store.apkmirror

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.NetworkTier
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
 * apkmirror against the contract every adapter must honour.
 *
 * The third store to extend it and the first with a three-level chain. The rate limiter is the real
 * one: the fixtures fit inside the burst, so the tests do not wait.
 */
@DisplayName("Contract — apkmirror")
class ApkMirrorStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: ApkMirrorTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var apkmirror: ApkMirrorStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = ApkMirrorTestServer(server)
        val work = Files.createTempDirectory("apkmirror-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkmirror = ApkMirrorStoreAdapter(
            config = ApkMirrorConfig(
                baseUrl = fake.baseUrl,
                // The real rate limit is one permit every three seconds, as is right towards a
                // site that answers 429. With a burst of four the fixtures would nearly always
                // pass, but the longest chain makes five requests: what is raised here is the
                // bucket, not the speed of the code under test.
                burst = 32,
            ),
            clients = clients,
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = apkmirror

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.APP_PATH)

    // --- Beyond the contract ---------------------------------------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        apkmirror.search(queryWithResults)

        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        // On this store that is not a formality: `okhttp/4.12.0` receives 403 with 153 bytes.
        sent.forEach { assertThat(it).isEqualTo(ApkMirrorConfig.DEFAULT_USER_AGENT) }
    }

    @Test
    @DisplayName("the declared rate limit stays under the store's Crawl-delay")
    fun rateLimitRespectsTheDeclaredCrawlDelay() {
        val real = ApkMirrorConfig()

        // apkmirror declares `Crawl-delay: 3` and really enforces it: while this adapter was
        // written it answered **429**. The burst can be more generous — it is the traffic shape of
        // a browser opening a chain of pages — but the sustained average cannot.
        assertThat(real.permitsPerSecond).isAtMost(1.0 / 3.0)
        assertThat(real.capabilities().networkTier).isEqualTo(NetworkTier.OKHTTP)
    }

    private fun ApkMirrorConfig.capabilities() =
        ApkMirrorStoreAdapter(config = this, clients = clients).capabilities

    @Test
    @DisplayName("a query with no results does not return the sidebar widgets")
    fun sidebarIsNotMistakenForResults() = runTest {
        val page = apkmirror.search(queryWithoutResults).expect()

        // The page has **38** rows of the results class, all sidebar, and zero results. It is this
        // store's most dangerous trap: 38 real apps, all wrong.
        assertThat(page.items).isEmpty()
    }

    @Test
    @DisplayName("the results are apps, not their individual releases")
    fun releasesAreNotReturnedAsApps() = runTest {
        val page = apkmirror.search(queryWithResults).expect()

        assertThat(page.items).isNotEmpty()
        // The same list contains three- and four-segment links. Returning a release as an app
        // would give a listing with the version number in the title and a single version in it.
        page.items.forEach { assertThat(it.ref.value.count { c -> c == '/' }).isEqualTo(1) }
    }

    @Test
    @DisplayName("the listing carries file hash and signer, not just metadata")
    fun detailCarriesHashAndSigner() = runTest {
        val detail = apkmirror.getAppDetails(existingRef).expect()

        assertThat(detail.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
        assertThat(detail.summary.latestVersionCode).isEqualTo(Fixtures.VERSION_CODE)
        assertThat(detail.preferredSignerSha256).isEqualTo(Sha256.parseOrNull(Fixtures.SIGNER_SHA256))

        // The only hydrated version is the one that will be offered, and it is a single APK: that
        // is where the file hash lives, and without it step 2 of the pre-install pipeline would
        // have nothing to compare against.
        val hydrated = detail.versions.filter { it.sha256 != null }
        assertThat(hydrated).hasSize(1)
        assertThat(hydrated.single().artifactType).isEqualTo(ArtifactType.APK)
        assertThat(hydrated.single().sha256).isEqualTo(Sha256.parseOrNull(Fixtures.FILE_SHA256))
    }

    @Test
    @DisplayName("bundles are marked APKM, not APK")
    fun bundlesAreMarkedAsBundles() = runTest {
        val detail = apkmirror.getAppDetails(existingRef).expect()

        // An apkmirror "APK bundle" is base plus splits. Marking it as an APK would let version
        // selection choose it and hand it to `PackageInstaller`, which would refuse it after
        // hundreds of megabytes had been downloaded.
        assertThat(detail.versions.map { it.artifactType }).contains(ArtifactType.APKM)
        assertThat(detail.versions.map { it.artifactType }).contains(ArtifactType.APK)
    }

    @Test
    @DisplayName("the nine variants stay nine distinct versions")
    fun variantsKeepDistinctRefs() = runTest {
        val versions = apkmirror.getAppDetails(existingRef).expect().versions

        assertThat(versions).hasSize(9)
        // Six of the nine share a version code: if the ref were based on that, the unique
        // constraint would leave three. Here the ref is the variant's path, which is per file — but
        // the invariant is verified all the same, because on apkcombo the same mistake cost a
        // listing saying "nothing to install" in front of five APKs.
        assertThat(versions.mapNotNull { it.versionCode }.distinct().size).isLessThan(versions.size)
        assertThat(versions.map { it.ref }.toSet()).hasSize(versions.size)
    }

    @Test
    @DisplayName("the download resolves the last hop by opening it, not composing it")
    fun downloadFollowsTheInterstitial() = runTest {
        val resolution = apkmirror.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // The interstitial's key and the download endpoint's are **different**: the second is only
        // obtained by opening the page. Composing the final URL by hand would give a 403.
        assertThat(resolution.url).contains("download.php")
        assertThat(resolution.headers).containsKey("Referer")
        assertThat(resolution.expectedSha256).isEqualTo(Sha256.parseOrNull(Fixtures.FILE_SHA256))
        // The exact count in brackets, not the rounded megabytes: that value feeds the first step
        // of the pre-install pipeline, which compares expected size with downloaded size, and two
        // decimals cover a range of several kilobytes.
        assertThat(resolution.expectedSize).isEqualTo(Fixtures.FILE_SIZE_BYTES)
    }

    @Test
    @DisplayName("a challenge does not become a parse error")
    fun challengeIsNotAParseFailure() = runTest {
        fake.challengeOn += "/apk/${Fixtures.APP_PATH}/"

        val result = apkmirror.getAppDetails(existingRef)

        // The right diagnosis is "blocked", not "the markup changed": the second would send people
        // to rewrite selectors that work perfectly.
        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        val error = (result as StoreResult.Failure).error
        assertThat(error).isInstanceOf(StoreError.Blocked::class.java)
    }

    @Test
    @DisplayName("a 404 page produces no listing")
    fun notFoundIsNotFound() = runTest {
        fake.missing += "/apk/${Fixtures.APP_PATH}/"

        val result = apkmirror.getAppDetails(existingRef)

        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }
}
