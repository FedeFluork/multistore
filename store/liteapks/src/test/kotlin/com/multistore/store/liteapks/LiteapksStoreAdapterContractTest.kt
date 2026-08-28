package com.multistore.store.liteapks

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
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * liteapks against the contract every adapter must satisfy.
 *
 * It is the **eighth** to extend it and it closes the nine stores. What sets it apart from the
 * other seven scraped ones is the two kinds of file: the same page can offer the APKs the store has
 * modified — behind an intermediate page, with a transit permit — and the **original** APK,
 * directly and with no gate. `getDownloadLink` has to do both, and the tests below check it tells
 * them apart by host and not by position.
 */
@DisplayName("Contract — liteapks")
class LiteapksStoreAdapterContractTest : StoreAdapterContractTest() {

    private lateinit var server: MockWebServer
    private lateinit var fake: LiteapksTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var liteapks: LiteapksStoreAdapter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = LiteapksTestServer(server)
        val work = Files.createTempDirectory("liteapks-contract").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(work, "cache")))
        liteapks = LiteapksStoreAdapter(
            config = LiteapksConfig(baseUrl = fake.baseUrl),
            clients = clients,
            // A stopped clock: the transit permit carries an expiry, and a test comparing it with
            // `now()` would be a test depending on when it is run.
            clock = FrozenClock,
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    override fun adapter(): StoreAdapter = liteapks

    override val queryWithResults: String = Fixtures.QUERY_WITH_RESULTS

    override val queryWithoutResults: String = Fixtures.QUERY_WITHOUT_RESULTS

    /**
     * The **game**, not the app, and the choice is forced by the contract.
     *
     * `providesScreenshots = true` is checked against the reference listing, and on liteapks the
     * screenshots are on 20 listings out of 31 — nearly always games. The app's listing stays
     * committed and the tests below use it: it is the one with no `packageName` and no screenshots,
     * i.e. the other real case.
     */
    override val existingRef: StoreAppRef = StoreAppRef(Fixtures.GAME_REF)

    /** A well-formed slug that does not exist: the page is requested and really answers 404. */
    override val missingRef: StoreAppRef = StoreAppRef("qzxvnpwmklj-does-not-exist")

    /** The game's first slot: `minecraft-11909/1`. */
    override val existingVersionRef: VersionRef = VersionRef("${Fixtures.GAME_STEM}/1")

    // --- Beyond the contract: what holds for this store only ------------------------------

    @Test
    @DisplayName("the User-Agent actually sent is not the library default")
    fun userAgentReachesTheWire() = runTest {
        liteapks.search(queryWithResults)
        val sent = fake.received.mapNotNull { it.headers["User-Agent"] }
        assertThat(sent).isNotEmpty()
        sent.forEach { assertThat(it).isEqualTo(LiteapksConfig.DEFAULT_USER_AGENT) }
    }

    /**
     * **The search paginates, and it is the biggest correction this adapter brings.**
     *
     * `curl` said "capped at ONE page, ~9 results, `?s=…&paged=2` -> 404": that was a measurement of
     * `curl`, which on this store gets a challenge almost everywhere. With the real client the pages
     * are four of eighteen, and the 404 arrives **past** the last one.
     */
    @Test
    @DisplayName("search really paginates, and stops where the site stops")
    fun searchPaginates() = runTest {
        val first = liteapks.search(Fixtures.QUERY_PAGED, page = 0).expect()
        val second = liteapks.search(Fixtures.QUERY_PAGED, page = 1).expect()
        val last = liteapks.search(Fixtures.QUERY_PAGED, page = 3).expect()

        assertThat(first.items).hasSize(Fixtures.PAGE_ROWS)
        assertThat(second.items).hasSize(Fixtures.PAGE_ROWS)
        assertThat(last.items).hasSize(Fixtures.LAST_PAGE_ROWS)
        // The two pages do not overlap: that is what separates real pagination from an ignored
        // parameter — which is exactly what `?s=game&page=2` does, with `page` instead of
        // `paged`.
        assertThat(first.items.map { it.ref }).containsNoneIn(second.items.map { it.ref })

        assertThat(first.hasMore).isTrue()
        assertThat(last.hasMore).isFalse()

        assertThat(fake.received.map { it.url.query })
            .containsExactly("s=game", "s=game&paged=2", "s=game&paged=4")
            .inOrder()
    }

    /**
     * The declared total is true while it is small, and **saturates** at sixty.
     *
     * `telegram` declares 7 and the rows are 7; `game` declares 60, as do `a`, `mod`, `pro` and
     * `e`. Reporting it as "results found" would be a lie on every popular query, and that is why
     * `hasMore` stops trusting it at the cap.
     */
    @Test
    @DisplayName("the declared total matches when small and saturates when large")
    fun declaredTotalSaturates() = runTest {
        val small = liteapks.search(Fixtures.QUERY_WITH_RESULTS).expect()
        val large = liteapks.search(Fixtures.QUERY_PAGED).expect()

        assertThat(small.totalCount).isEqualTo(Fixtures.QUERY_RESULTS)
        assertThat(small.items).hasSize(Fixtures.QUERY_RESULTS)
        assertThat(small.hasMore).isFalse()

        assertThat(large.totalCount).isEqualTo(Fixtures.DECLARED_CAP)
    }

    /**
     * The store's file costs **one** request with the ref in hand, and carries the permit.
     *
     * The permit is two things together and neither is enough alone: the `?token=` in the URL and
     * the `Referer` among the headers. Measured against the real worker: with the token alone it
     * answers 403, with the Referer alone likewise, with both 200.
     */
    @Test
    @DisplayName("the store's file comes out with token and Referer, in a single request")
    fun storeFileCarriesTokenAndReferer() = runTest {
        val resolution = liteapks.getDownloadLink(existingRef, existingVersionRef).expect()

        val direct = resolution as DownloadResolution.Direct
        assertThat(direct.url).startsWith(Fixtures.GAME_FILE_URL)
        assertThat(direct.headers[REFERER]).endsWith("/download/${Fixtures.GAME_STEM}/1")
        assertThat(direct.fileName).endsWith(".apk")
        // No hash anywhere on the site, and the page writes the size rounded: they are the two
        // declarations `providesHash = NONE` and `expectedSize = null` make honest.
        assertThat(direct.expectedSha256).isNull()
        assertThat(direct.expectedSize).isNull()
        assertThat(fake.received.map { it.url.encodedPath })
            .containsExactly("/download/${Fixtures.GAME_STEM}/1")
    }

    /**
     * **The URL with raw spaces is normalised, and the already-encoded one is not.**
     *
     * The same CDN serves both forms. Encoding twice produces `%2520` and the worker answers 404
     * `NoSuchKey`; not encoding produces a URL OkHttp rejects. It is the same measurement that on
     * modyolo made 28 binaries out of 40 look dead.
     */
    @Test
    @DisplayName("the CDN's raw spaces become %20, and %20 stays %20")
    fun rawSpacesAreEncodedOnce() = runTest {
        val resolution = liteapks
            .getDownloadLink(StoreAppRef(Fixtures.APP_REF), VersionRef("${Fixtures.APP_STEM}/1"))
            .expect() as DownloadResolution.Direct

        assertThat(resolution.url).startsWith(Fixtures.APP_FILE_URL)
        assertThat(resolution.url).doesNotContain("%2520")
        assertThat(resolution.url).doesNotContain(" ")
        // The starting form, to show the normalisation really had something to do: without the raw
        // spaces the test would pass even with the defence removed.
        assertThat(Fixtures.html(Fixtures.SLOT_RAW_SPACES)).contains(
            java.util.Base64.getEncoder().encodeToString(Fixtures.APP_FILE_URL_RAW.toByteArray()),
        )
    }

    /**
     * The declared expiry is the one put into the token, and not some arbitrary number.
     *
     * The worker rejects an already-expired token: declaring an expiry different from the one
     * written in the URL would mean either re-resolving a still-good link, or handing a dead one to
     * whoever had cached it.
     */
    @Test
    @DisplayName("expiresAt is the same expiry the token carries written in it")
    fun expiryMatchesTheToken() = runTest {
        val direct = liteapks.getDownloadLink(existingRef, VersionRef("${Fixtures.APP_STEM}/1"))
            .expect() as DownloadResolution.Direct

        val expected = FrozenClock.now() + LiteapksConfig.DEFAULT_DOWNLOAD_TOKEN_TTL
        assertThat(direct.expiresAt).isEqualTo(expected)
        // The value is read from the query rather than from the string: the token ends in `==` and
        // ends up percent-encoded, exactly as `encodeURIComponent` writes it in their JavaScript.
        // Comparing raw characters would prove OkHttp's encoding, not the token.
        val sent = direct.url.toHttpUrlOrNull()?.queryParameter("token")
        assertThat(sent).isEqualTo(LiteapksRefs.downloadToken(expected))
    }

    /**
     * The **original** file goes through no page and carries no token.
     *
     * The host is the only thing that distinguishes it, and it is how the site's own JavaScript
     * distinguishes it too: `WORKER_DOWNLOAD_HOSTS` lists the two domains that want the permit, and
     * to all the others the theme adds nothing. Adding it where it is not needed is not harmless —
     * it is an extra query parameter on a storage key.
     */
    @Test
    @DisplayName("the original file comes out as-is, with no token and no requests")
    fun originalFileIsServedAsIs() = runTest {
        val original = "https://gp4.liteapks.com/Minecraft%20Earth/Minecraft%20Earth-0.33.0.apk"

        val direct = liteapks.getDownloadLink(existingRef, VersionRef(original))
            .expect() as DownloadResolution.Direct

        assertThat(direct.url).isEqualTo(original)
        assertThat(direct.url).doesNotContain("token=")
        // No expiry: on a URL with no gate there is nothing to expire, and declaring one would make
        // a perfectly good link be re-resolved.
        assertThat(direct.expiresAt).isNull()
        assertThat(fake.received).isEmpty()
    }

    /**
     * A listing that exists but offers no file is `NotFound`, not an empty success.
     *
     * Handing the download engine a URL built from an empty list would send it to download
     * `/download/`.
     */
    @Test
    @DisplayName("a listing with no files produces no download URL")
    fun listingWithoutFilesIsNotFound() = runTest {
        // The real file page, with only the rows removed: it stays liteapks markup and it stays a page
        // the parser can read. The case has no fixture because the site does not produce it — each of
        // the 31 sampled has at least one file — but "the page is there and offers nothing" is the shape
        // a withdrawn app would take.
        fake.rawOverrides["/download/${Fixtures.GAME_STEM}"] =
            Fixtures.html(Fixtures.DOWNLOAD_GAME).replace("dl-item", "dl-was-here")

        val resolution = liteapks.getDownloadLink(existingRef, version = null)

        assertThat(resolution).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((resolution as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    private fun <T> StoreResult<T>.expect(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error("should have succeeded on the fixtures, gave $error")
        StoreResult.Unsupported -> error("should have succeeded on the fixtures, gave Unsupported")
    }

    /** A fixed instant: `2026-08-25T12:00:00Z`. */
    private object FrozenClock : Clock {
        override fun now(): Instant = Instant.parse("2026-08-25T12:00:00Z")
    }

    private companion object {
        const val REFERER = "Referer"
    }
}
