package com.multistore.core.network.http

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreId
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The image client: what it shares with the store one, and what it does not.
 *
 * The three differences from a per-store client are deliberate, and two are visible only by
 * looking at the configuration — exactly the kind of thing that breaks silently when someone
 * rewrites `imageClient` in six months.
 */
@DisplayName("Images — the client that downloads them")
class ImageClientTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: StoreHttpClients

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val work = Files.createTempDirectory("image-client").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(work, "cache")))
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    @Test
    @DisplayName("images do not go through the stores' HTTP cache")
    fun imagesDoNotShareTheHttpCache() = runTest {
        // Coil has its own disk cache, with its own eviction and key: keeping ours as well
        // would store the same bytes **twice** — and two hundred megabytes of icons would evict
        // the store pages from the HTTP cache's fifty, to keep copies of files Coil already saved
        // elsewhere.
        assertThat(clients.imageClient("test-agent").cache).isNull()
    }

    @Test
    @DisplayName("images get a higher per-host ceiling than pages")
    fun imagesGetTheirOwnDispatcher() = runTest {
        val store = clients.forStore(StoreId.FDROID, StoreNetworkProfile(userAgent = "test-agent"))
        val images = clients.imageClient("test-agent")

        // With the shared dispatcher, twenty icons would queue **behind** an apkmirror request
        // already waiting out three seconds of `Crawl-delay`. The two questions differ: two
        // connections per host is politeness towards a site being scraped, a list of icons is
        // what a browser does with a page's subresources.
        assertThat(images.dispatcher.maxRequestsPerHost)
            .isGreaterThan(store.client.dispatcher.maxRequestsPerHost)
        assertThat(images.dispatcher.maxRequestsPerHost)
            .isEqualTo(StoreHttpClients.MAX_IMAGE_REQUESTS_PER_HOST)
    }

    @Test
    @DisplayName("images share the stores' connection pool")
    fun imagesShareTheConnectionPool() = runTest {
        val store = clients.forStore(StoreId.FDROID, StoreNetworkProfile(userAgent = "test-agent"))

        // The one of the three worth sharing: `f-droid.org` serves both the pages and the
        // icons, and with two pools the socket ceiling the app believes it honours applies to
        // half the traffic.
        assertThat(clients.imageClient("test-agent").connectionPool)
            .isSameInstanceAs(store.client.connectionPool)
    }

    @Test
    @DisplayName("the HTTP cache can be measured and emptied")
    fun theHttpCacheCanBeMeasuredAndCleared() = runTest {
        server.enqueue(MockResponse(body = "x".repeat(4096)))
        val http = clients.forStore(
            StoreId.APKMIRROR,
            StoreNetworkProfile(userAgent = "test-agent", pageCacheTtl = 5.minutes),
        )
        http.execute(Request.Builder().url(server.url("/page").toString()).build()).use {
            it.body.string()
        }

        assertThat(clients.httpCacheBytes()).isGreaterThan(0L)

        clients.clearHttpCache()

        // The "clear the store pages" button is the only thing acting on this level: the
        // ceiling is fixed by `okhttp3.Cache` at construction, so it is not a setting.
        assertThat(clients.httpCacheBytes()).isEqualTo(0L)
    }
}
