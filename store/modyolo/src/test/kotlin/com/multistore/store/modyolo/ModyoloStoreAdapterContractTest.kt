package com.multistore.store.modyolo

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.VersionRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.SearchFilters
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreAdapterContractTest
import com.multistore.store.api.StoreResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * modyolo against the contract every adapter must satisfy.
 *
 * It is the **seventh** store to extend it, and it brings two firsts: the `preflight` that says no
 * without making the store look ill, and the adult-content filter. The contract grew by one
 * capability and one test — [FilterCapability.NSFW_CONTENT] and its check — and nothing else.
 */
@DisplayName("Contract — modyolo")
class ModyoloStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: ModyoloTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var modyolo: ModyoloStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = ModyoloTestServer(server)
        val work = Files.createTempDirectory("modyolo-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        modyolo = ModyoloStoreAdapter(config = ModyoloConfig(baseUrl = fake.baseUrl), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = modyolo

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    override val queryWithNsfwResults: String = Fixtures.QUERY_WITH_NSFW

    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.APP_REF)

    /** A ref with the right shape and an id that does not exist: the request really goes out. */
    override val missingRef: StoreAppRef = StoreAppRef("qzxvnpwmklj-999999999")

    override val existingVersionRef: VersionRef = VersionRef("2")

    // --- Beyond the contract: what holds for this store only ------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        modyolo.search(queryWithResults)
        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        sent.forEach { assertThat(it).isEqualTo(ModyoloConfig.DEFAULT_USER_AGENT) }
    }

    @Test
    @DisplayName("the adult filter travels as a parameter, not as a local discard")
    fun theNsfwFilterIsServerSide() = runTest {
        modyolo.search(Fixtures.QUERY_WITH_NSFW, SearchFilters.NONE)
        val excluded = fake.received.mapNotNull {
            it.url.queryParameter(ModyoloConfig.EXCLUDE_PARAM)
        }

        // Downloading twenty results to discard fifteen would work, and would be worse: the page
        // would stay short for no visible reason, and the traffic towards the store would be paid
        // by the user for content they asked not to see.
        assertThat(excluded).isNotEmpty()
        ModyoloConfig.DEFAULT_NSFW_CATEGORY_IDS.forEach { id ->
            assertThat(excluded.first()).contains(id.toString())
        }
    }

    @Test
    @DisplayName("with the switch on, the parameter disappears and the results come back")
    fun theFilterCanBeTurnedOff() = runTest {
        val all = modyolo.search(Fixtures.QUERY_WITH_NSFW, SearchFilters(includeNsfw = true)).expect()
        val sentWithout = fake.received.count { it.url.queryParameter(ModyoloConfig.EXCLUDE_PARAM) != null }

        assertThat(sentWithout).isEqualTo(0)
        assertThat(all.items).hasSize(Fixtures.NSFW_INCLUDED_RESULTS)
    }

    @Test
    @DisplayName("the filter removes fifteen results minus three, and not everything")
    fun theFilterRemovesWhatIsLabelled() = runTest {
        val filtered = modyolo.search(Fixtures.QUERY_WITH_NSFW, SearchFilters.NONE).expect()

        // The number matters in **both** directions. That it drops proves the filter works; that
        // it does not reach zero is the honest part: the three survivors are in "Role Playing"
        // (4218), i.e. adult content modyolo **does not label**. The setting's description says
        // that, not "hides adult content".
        assertThat(filtered.items).hasSize(Fixtures.NSFW_EXCLUDED_RESULTS)
    }

    @Test
    @DisplayName("a page past the last one is empty, not a store error")
    fun aPageBeyondTheLastIsEmpty() = runTest {
        // WordPress answers **400** with `rest_post_invalid_page_number`. Propagating it would open
        // the circuit breaker on a healthy source every time someone scrolls to the end.
        val page = modyolo.search(queryWithResults, page = 5).expect()

        assertThat(page.items).isEmpty()
        assertThat(page.hasMore).isFalse()
    }

    @Test
    @DisplayName("the download goes through the Referer, without which modyolo does not answer")
    fun theDownloadNeedsTheReferer() = runTest {
        val resolution = modyolo.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        val ajax = fake.received.filter { it.url.encodedPath == ModyoloConfig.AJAX_PATH }
        assertThat(ajax).isNotEmpty()
        // The double answers with an empty fragment if the `Referer` is not a variant's — exactly
        // like the real site. An adapter that forgot it would fail here rather than on the user's
        // phone.
        assertThat(ajax.first().headers["Referer"])
            .endsWith("/download/${Fixtures.APP_REF}/${ModyoloRefs.FIRST_VARIANT}")
        assertThat(resolution.fileName).isEqualTo(Fixtures.APP_FILE)
    }

    @Test
    @DisplayName("an older version resolves with its own Referer, not the current one's")
    fun anOlderVersionUsesItsOwnReferer() = runTest {
        modyolo.getDownloadLink(existingRef, existingVersionRef)

        val ajax = fake.received.first { it.url.encodedPath == ModyoloConfig.AJAX_PATH }
        // If the `VersionRef` were ignored, every download would return the current version and
        // the variant list would be a decorative menu. Here the difference is **entirely** in the
        // header: the request is otherwise identical.
        assertThat(ajax.headers["Referer"]).endsWith("/download/${Fixtures.APP_REF}/2")
    }

    @Test
    @DisplayName("preflight says no on the dead file without making the store look ill")
    fun preflightRejectsADeadBinaryWithoutFailing() = runTest {
        val resolution = modyolo.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // The healthy state first: without it the test would pass even with a preflight that
        // always says no.
        assertThat(modyolo.preflight(resolution).expect()).isTrue()

        fake.deadFiles += java.net.URI(resolution.url).path
        val result = modyolo.preflight(resolution)

        // **`Success(false)`, not `Failure`.** A quarter of modyolo's binaries answer 500: if each
        // counted as a store fault, the circuit breaker would open and with it the three quarters
        // that work would vanish from search.
        assertThat(result).isInstanceOf(StoreResult.Success::class.java)
        assertThat((result as StoreResult.Success).value).isFalse()
    }

    @Test
    @DisplayName("the file URL is normalised only where needed")
    fun theFileUrlIsNormalizedConditionally() = runTest {
        val resolution = modyolo.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // The CDN mixes both forms: already-encoded paths (`/The%20Walking%20Zombie/`) and paths
        // with raw spaces (`/Bloons TD 6/`). Encoding twice produces `%2520` and a file that does
        // not exist; not encoding produces a URL OkHttp rejects.
        assertThat(resolution.url).doesNotContain("%25")
        assertThat(resolution.url).doesNotContain(" ")
    }

    @Test
    @DisplayName("the listing publishes the package, deduced from the Google Play link")
    fun theListingCarriesThePackageName() = runTest {
        val detail = modyolo.getAppDetails(existingRef).expect()

        // It is the only pre-install check that can say no on this store: no hash, no original
        // signature to compare against. Verified against eight real APKs, seven of them
        // modified.
        assertThat(detail.summary.packageName).isEqualTo(Fixtures.APP_PACKAGE)
        // The capability stays `false` because the search results never carry it, and the listing
        // loses it for apps that are not on Google Play.
        assertThat(modyolo.capabilities.providesPackageName).isFalse()
        assertThat(modyolo.search(queryWithResults).expect().items.mapNotNull { it.packageName })
            .isEmpty()
    }

    @Test
    @DisplayName("every variant has a distinct ref")
    fun versionRefsAreDistinct() = runTest {
        val versions = modyolo.getVersions(existingRef).expect()

        assertThat(versions).hasSize(Fixtures.APP_VARIANTS)
        // `app_versions` has `UNIQUE(listing_id, version_ref)`: a non-unique discriminator
        // silently loses every version but the last one written.
        assertThat(versions.map { it.ref }.toSet()).hasSize(versions.size)
    }

    @Test
    @DisplayName("a post with a single variant stays installable")
    fun aSingleVariantPostIsStillInstallable() = runTest {
        // **The fault found on the device.** With a single variant modyolo emits no "Other
        // available link(s)" section at all, and the "Toolbox for Minecraft: PE" listing answered
        // "This store publishes no installable package for this app" in front of a file that
        // downloads perfectly well. The premise has to be measured, not asserted.
        assertThat(Fixtures.text(Fixtures.DOWNLOAD_PAGE_SINGLE)).doesNotContain("accordion-versions")

        val ref = StoreAppRef(Fixtures.SINGLE_REF)
        val versions = modyolo.getVersions(ref).expect()

        assertThat(versions).hasSize(1)
        // The name comes from the heading `Toolbox for Minecraft: PE - v5.4.54 - Mod`: cut at the
        // first ` - ` and drop the `v`. "Mod" stays, because it says this is not the original
        // build.
        assertThat(versions.single().versionName).isEqualTo(Fixtures.SINGLE_VERSION)
        assertThat(modyolo.getAppDetails(ref).expect().versions).hasSize(1)
    }

    @Test
    @DisplayName("a URL with raw spaces resolves all the same")
    fun rawSpacesInTheFileUrlAreHandled() = runTest {
        // The old CDN (`files.modyolo.com`) writes paths with uncoded spaces.
        assertThat(Fixtures.text(Fixtures.DOWNLOAD_AJAX_SINGLE)).contains("Toolbox for Minecraft/")

        val resolution = modyolo.getDownloadLink(StoreAppRef(Fixtures.SINGLE_REF))
            .expect() as DownloadResolution.Direct

        assertThat(resolution.url).doesNotContain(" ")
        assertThat(resolution.fileName).isEqualTo(Fixtures.SINGLE_FILE)
    }

    @Test
    @DisplayName("declares no expected size, because modyolo rounds it")
    fun doesNotDeclareARoundedSizeAsAnExpectation() = runTest {
        val resolution = modyolo.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // `891 MB` covers half a megabyte of uncertainty. Using it as the expected value makes a
        // complete file be declared incomplete — that happened on apkcombo.
        assertThat(resolution.expectedSize).isNull()
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }
}
