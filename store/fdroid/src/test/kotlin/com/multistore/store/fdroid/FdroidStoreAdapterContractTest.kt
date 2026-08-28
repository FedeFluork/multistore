package com.multistore.store.fdroid

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.VersionRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.SearchSource
import com.multistore.store.api.StoreAdapter
import com.multistore.store.api.StoreAdapterContractTest
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.fdroid.index.PackagePayload
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * F-Droid against the contract every adapter must satisfy.
 *
 * Being F-Droid the first, this class is also the model for the other eight — and it shows the less
 * obvious case: a store that serves search and detail from the local index, not with an HTTP
 * request.
 */
@DisplayName("Contract — F-Droid")
class FdroidStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: FdroidTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var fdroid: FdroidStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = FdroidTestServer(server)
        val work = Files.createTempDirectory("fdroid-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        fdroid = FdroidStoreAdapter(
            config = FdroidConfig(baseUrl = fake.baseUrl, searchApiUrl = fake.searchApiUrl),
            clients = clients,
            workDir = work,
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = fdroid

    override val queryWithResults: String = "f-droid"

    override val existingRef: StoreAppRef = FdroidRefs.appRef(Fixtures.PKG_FDROID)

    override val existingVersionRef: VersionRef?
        get() = (detailFromIndex(existingRef) as? StoreResult.Success)?.value?.versions?.firstOrNull()?.ref

    /**
     * The detail comes from the index, not from the network.
     *
     * It is what `searchSource = LOCAL_INDEX` declares, and in the real app this function is
     * `:core:data` reading the payload from Room and asking the adapter to project it. Here the
     * payload comes from the fixture: what changes is where the bytes come from, not who interprets
     * them.
     */
    override suspend fun detailFor(ref: StoreAppRef): StoreResult<StoreListingDetail> =
        detailFromIndex(ref)

    private fun detailFromIndex(ref: StoreAppRef): StoreResult<StoreListingDetail> {
        val packageName = FdroidRefs.packageName(ref)
        val raw = Fixtures.slicePackages()[packageName]
            ?: return StoreResult.Failure(StoreError.NotFound)
        // As FdroidIndexSnapshot writes it: the stored payload carries its own name.
        val payload = PackagePayload.withPackageName(raw, packageName).toString()
        return fdroid.projectEntry(payload)
            ?.let { StoreResult.Success(it) }
            ?: StoreResult.Failure(StoreError.NotFound)
    }

    // --- Checks specific to a local-index store ----------------------------------------------

    @Test
    @DisplayName("it declares it searches the local index, and is therefore an IndexedStoreAdapter")
    fun declaresLocalIndex() {
        assertThat(fdroid.capabilities.searchSource).isEqualTo(SearchSource.LOCAL_INDEX)
    }

    @Test
    @DisplayName("detail over HTTP is Unsupported, and the reason is the capability")
    fun httpDetailIsUnsupported() = runTest {
        // Not a hole: it is the declaration that somebody else gives that answer. If one day the
        // adapter started answering here, the capability would have to change.
        assertThat(fdroid.getAppDetails(existingRef)).isInstanceOf(StoreResult.Unsupported::class.java)
        assertThat(fdroid.getVersions(existingRef)).isInstanceOf(StoreResult.Unsupported::class.java)
    }

    @Test
    @DisplayName("the fallback search never queries the repository")
    fun bootstrapSearchOnlyTouchesTheSearchHost() = runTest {
        fake.received.clear()

        fdroid.search(queryWithResults)

        assertThat(fake.received).isNotEmpty()
        // The search service is separate from the repository: mixing them would make the search
        // depend on the index's availability, and vice versa.
        assertThat(fake.received.none { it.url.encodedPath.startsWith("/repo/") }).isTrue()
    }

    @Test
    @DisplayName("the fallback search derives the packageName from the listing URL")
    fun bootstrapSearchDerivesPackageNames() = runTest {
        val page = (fdroid.search(queryWithResults) as StoreResult.Success).value

        assertThat(page.items).isNotEmpty()
        // The API does not publish the packageName: it is read from `…/packages/<pkg>`. Without it,
        // the results would not be linkable to anything.
        page.items.forEach { assertThat(it.packageName).isNotEmpty() }
        assertThat(page.items.map { it.packageName }).contains(Fixtures.PKG_FDROID)
    }

    @Test
    @DisplayName("past the first page it does not fake a pagination the API does not have")
    fun noFakePagination() = runTest {
        val second = (fdroid.search(queryWithResults, page = 1) as StoreResult.Success).value

        // The API ignores `page` and would always return the same 10 results: presenting them as
        // "page 2" would make them appear twice in the list.
        assertThat(second.items).isEmpty()
        assertThat(second.hasMore).isFalse()
    }

    @Test
    @DisplayName("healthCheck queries entry.jar, and a 404 becomes NotFound")
    fun healthCheckUsesEntryJar() = runTest {
        assertThat(fdroid.healthCheck()).isInstanceOf(StoreResult.Success::class.java)
        assertThat(fake.received.last().url.encodedPath).isEqualTo("/repo/entry.jar")
        assertThat(fake.received.last().method).isEqualTo("HEAD")

        fake.missing += "/repo/entry.jar"
        val down = fdroid.healthCheck()
        assertThat((down as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("the download resolves with no network, and the URL comes from the name in the index")
    fun downloadResolvesOffline() = runTest {
        val version = requireNotNull(existingVersionRef)
        fake.received.clear()

        val resolution = fdroid.getDownloadLink(existingRef, version)

        assertThat(resolution).isInstanceOf(StoreResult.Success::class.java)
        // Zero requests: everything needed is inside the VersionRef. It is also why this method
        // cannot throw network exceptions.
        assertThat(fake.received).isEmpty()
    }

    @Test
    @DisplayName("with no version the download does not guess: it says so")
    fun downloadWithoutVersionIsRefused() = runTest {
        val result = fdroid.getDownloadLink(existingRef, version = null)

        assertThat((result as StoreResult.Failure).error).isInstanceOf(StoreError.Unsupported::class.java)
    }

    @Test
    @DisplayName("a malformed VersionRef gives ParseFailure, not an exception")
    fun malformedVersionRefIsAParseFailure() = runTest {
        val result = fdroid.getDownloadLink(existingRef, VersionRef("spazzatura"))

        assertThat((result as StoreResult.Failure).error).isInstanceOf(StoreError.ParseFailure::class.java)
    }
}
