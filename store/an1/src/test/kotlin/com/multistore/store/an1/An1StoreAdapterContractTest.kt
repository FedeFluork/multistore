package com.multistore.store.an1

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.SearchFilters
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
 * an1 against the contract every adapter must honour.
 *
 * The **sixth** store to extend it, and the first with no `packageName` anywhere. The contract did
 * not have to bend: `providesPackageName = false` was already there, and this is exactly the case
 * it existed for.
 */
@DisplayName("Contract — an1")
class An1StoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: An1TestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var an1: An1StoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = An1TestServer(server)
        val work = Files.createTempDirectory("an1-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        an1 = adapterWith(An1Config(baseUrl = fake.baseUrl, downloadHosts = listOf(fake.downloadHost)))
    }

    private fun adapterWith(config: An1Config) = An1StoreAdapter(config = config, clients = clients)

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = an1

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.APP_REF)

    /**
     * A ref with the **right shape** that does not exist.
     *
     * The contract's default would be rejected by validation without a single request: it would
     * test the validation, not the 404. With this the page is really requested, and the parser has
     * to cope with the 31 KB of full 404 page an1 returns.
     */
    override val missingRef: StoreAppRef = StoreAppRef("9999999-does-not-exist")

    // --- Beyond the contract: what holds for this store only -------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        an1.search(queryWithResults)
        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        sent.forEach { assertThat(it).isEqualTo(An1Config.DEFAULT_USER_AGENT) }
    }

    @Test
    @DisplayName("search really paginates, and the second page does not repeat the first")
    fun searchPaginates() = runTest {
        val first = an1.search(queryWithResults, page = 0).expect()
        val second = an1.search(queryWithResults, page = 1).expect()

        // Measured: 10 and 4, no overlap. It is the only scraped store that genuinely paginates —
        // the others return the same page for a second request, and their adapters do not even
        // ask for it.
        assertThat(first.items).hasSize(Fixtures.PAGE_1_RESULTS)
        assertThat(second.items).hasSize(Fixtures.PAGE_2_RESULTS)
        assertThat(first.items.map { it.ref }).containsNoneIn(second.items.map { it.ref })
        assertThat(first.hasMore).isTrue()
        assertThat(second.hasMore).isFalse()
    }

    @Test
    @DisplayName("results include the entries marked MOD, which carry an extra class")
    fun modEntriesAreNotDropped() = runTest {
        // an1 marks modified entries with an extra class. On the first page of one query they are
        // five and five: a selector written as an exact attribute comparison — rather than as a
        // class selector — would lose **half**, and search would look like it worked.
        assertThat(Fixtures.html(Fixtures.SEARCH)).contains("class=\"item_app mod\"")

        val page = an1.search(queryWithResults).expect()

        assertThat(page.items.map { it.ref.value }).contains("4414-survivalcraft-2-mod")
    }

    @Test
    @DisplayName("a search with no results collects nothing from the rest of the page")
    fun emptySearchStaysEmpty() = runTest {
        // an1 does not put suggested cards in place of results, unlike uptodown and apkmody. The
        // premise has to be measured rather than asserted: if it ever added them, this line notices
        // before the parser starts inventing results.
        assertThat(Fixtures.html(Fixtures.SEARCH_EMPTY)).doesNotContain("item_app")

        assertThat(an1.search(queryWithoutResults).expect().items).isEmpty()
    }

    @Test
    @DisplayName("the download is the app's file, not the store an1 advertises next to it")
    fun downloadIsTheAppAndNotTheirOwnStore() = runTest {
        // The premise: the download page has **two** `.apk` files on the same host.
        assertThat(Fixtures.html(Fixtures.DOWNLOAD)).contains("an1store.apk")

        val resolution = an1.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        assertThat(resolution.fileName).isEqualTo(Fixtures.APP_FILE)
        assertThat(resolution.url).doesNotContain("an1store")
    }

    @Test
    @DisplayName("the SHA-256 comes from the HEAD on the CDN, not from the page")
    fun hashComesFromTheCdnHead() = runTest {
        val resolution = an1.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        // It is the only thing that makes an1 verifiable, and it is written on no page.
        assertThat(resolution.expectedSha256?.hex).isEqualTo(Fixtures.APP_SHA256)
        // The **exact** size comes from the same place: the listing writes `79.9Mb`, rounded to one
        // decimal, i.e. with fifty thousand bytes of uncertainty.
        assertThat(resolution.expectedSize).isEqualTo(fake.cdnBody.size.toLong())
    }

    @Test
    @DisplayName("without the hash header the download still happens, without a hash")
    fun downloadSurvivesAMissingChecksum() = runTest {
        // Two of six sampled files carry the header: most an1 downloads take this branch. Giving
        // up the file because an optional metadata field is missing would make the store unusable
        // for the normal case.
        fake.cdnHeaders.clear()

        val resolution = an1.getDownloadLink(existingRef).expect() as DownloadResolution.Direct

        assertThat(resolution.expectedSha256).isNull()
        assertThat(resolution.url).isNotEmpty()
    }

    @Test
    @DisplayName("a link pointing outside the file host is not served")
    fun aLinkToAnotherHostIsRejected() = runTest {
        // The healthy state first, or the test would pass with an adapter that refuses everything.
        // Then the expected host is moved: the page stays identical, only what the adapter accepts
        // changes.
        assertThat(an1.getDownloadLink(existingRef)).isInstanceOf(StoreResult.Success::class.java)

        val strict = adapterWith(An1Config(baseUrl = fake.baseUrl, downloadHosts = listOf("files.other-host.example")))
        val result = strict.getDownloadLink(existingRef)

        // **`NotFound`, not a parse failure.** The selector found its anchor: the markup has not
        // changed, the link points outside. Diagnosing it as broken markup would send people
        // looking for a selector that is perfectly fine.
        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("large apps live on the second host, and download all the same")
    fun theSecondFileHostIsAccepted() = runTest {
        // **The failure found on the device.** One app answered "The store answered in an
        // unexpected format" because its file sits on the second host while the configuration
        // declared only the first. The fixture is that app's real page.
        assertThat(Fixtures.html(Fixtures.DOWNLOAD_SECOND_HOST)).contains("files.an1.co/")

        val resolution = an1.getDownloadLink(StoreAppRef(Fixtures.GAME_REF))
            .expect() as DownloadResolution.Direct

        assertThat(resolution.fileName).isEqualTo(Fixtures.GAME_FILE)
    }

    @Test
    @DisplayName("a file an1 no longer hosts is NotFound, not changed markup")
    fun anOffsiteFileIsNotFound() = runTest {
        // Two listings out of twelve put a link shortener in the download anchor, ending at Google
        // Drive. It is not followed: on a store with no package name and no hash, the host list is
        // the last structural control left.
        assertThat(Fixtures.html(Fixtures.DOWNLOAD_OFFSITE)).contains("bit.ly/")

        val result = an1.getDownloadLink(StoreAppRef(Fixtures.OFFSITE_REF))

        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("a game listing and a program listing are told apart")
    fun gamesAndProgramsAreTold() = runTest {
        val program = an1.getAppDetails(existingRef).expect()
        val game = an1.getAppDetails(StoreAppRef(Fixtures.GAME_REF)).expect()

        // The microdata category is the only place that says so: neither the slug nor the URL
        // distinguishes a game listing from a program one.
        assertThat(program.summary.contentKind)
            .isEqualTo(com.multistore.core.model.ContentKind.APP)
        assertThat(game.summary.contentKind)
            .isEqualTo(com.multistore.core.model.ContentKind.GAME)
        assertThat(game.summary.title).isEqualTo(Fixtures.GAME_TITLE)
    }

    @Test
    @DisplayName("the adult-content filter changes nothing, and must not")
    fun theNsfwFilterIsInert() = runTest {
        // an1 does not label adult content: 33 categories, none adult. An adapter filtering anyway
        // — by keyword, say — would make it impossible for the UI to say which stores the setting
        // affects.
        val filtered = an1.search(queryWithResults, SearchFilters.NONE).expect()
        val unfiltered = an1.search(queryWithResults, SearchFilters(includeNsfw = true)).expect()

        assertThat(filtered.items.map { it.ref }).isEqualTo(unfiltered.items.map { it.ref })
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }
}
