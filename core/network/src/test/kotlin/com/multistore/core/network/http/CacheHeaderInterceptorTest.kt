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
 * The cache override: it fills a silence, it does **not** contradict an answer.
 *
 * The two tests together are the defence, and the second is the one that counts. The obvious
 * solution — always impose a `max-age` — would pass the first and break the rule: apkcombo and
 * uptodown declare `no-store` explicitly, and ignoring that would be more aggressive than a
 * browser. The measurement is in [CacheHeaderInterceptor]'s KDoc.
 */
@DisplayName("HTTP cache — the per-store override")
class CacheHeaderInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: StoreHttpClients

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val work = Files.createTempDirectory("cache-override").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(work, "cache")))
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    private fun client(ttlMinutes: Int) = clients.forStore(
        StoreId.APKMIRROR,
        StoreNetworkProfile(userAgent = "test-agent", pageCacheTtl = ttlMinutes.minutes),
    )

    private suspend fun fetchTwice(ttlMinutes: Int) {
        val http = client(ttlMinutes)
        val request = Request.Builder().url(server.url("/page").toString()).build()
        http.execute(request).use { it.body.string() }
        http.execute(request).use { it.body.string() }
    }

    @Test
    @DisplayName("a page with no cache header at all is stored")
    fun aSilentResponseIsCached() = runTest {
        // This is apkmirror: no `Cache-Control`, no `Expires`, no validator. Without the
        // override, visiting the same listing twice is two requests to a site that declares
        // `Crawl-delay: 3` and answers 429 to whoever ignores it.
        repeat(2) { server.enqueue(MockResponse(code = 200, body = "<html>page</html>")) }

        fetchTwice(ttlMinutes = 5)

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    @DisplayName("a page that says no-store is not stored regardless")
    fun anExplicitNoStoreIsObeyed() = runTest {
        // apkcombo and uptodown. The site had its say, and it is respected: a browser that
        // ignores `no-store` is not aggressive, it is broken.
        repeat(2) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                    .body("<html>page</html>")
                    .build(),
            )
        }

        fetchTwice(ttlMinutes = 5)

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    @DisplayName("with no TTL configured the interceptor never enters the chain")
    fun withoutATtlNothingChanges() = runTest {
        repeat(2) { server.enqueue(MockResponse(code = 200, body = "<html>page</html>")) }

        fetchTwice(ttlMinutes = 0)

        // The default is "respect what the site says", and for stores that say nothing that
        // means no cache. The override is a per-store choice, not a global behaviour switched on
        // quietly.
        assertThat(server.requestCount).isEqualTo(2)
    }
}
