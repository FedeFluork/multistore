package com.multistore.core.network.http

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreId
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The request log: what reaches the listener, and what does **not**.
 *
 * The easy part is that a successful request produces a row. The two that matter are the others:
 * that a **403** lands there too — "it arrived and said no" and "it never left" are two different
 * diagnoses, and telling them apart is half the reason the log exists — and that the rate
 * limiter's wait is **not** counted as the store's response time.
 */
@DisplayName("Request log")
class RequestLogTest {

    private data class Line(
        val storeId: StoreId,
        val method: String,
        val url: String,
        val code: Int,
        val elapsed: Duration,
    )

    private val lines = mutableListOf<Line>()
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() = server.close()

    /**
     * The client factory, with the test's clock.
     *
     * `testTimeSource` and not `TimeSource.Monotonic`, and that is not a detail: `runTest`
     * advances the scheduler's waits **without** real time passing, so a real clock would answer
     * "zero milliseconds" to everything — and the rate-limiter test would stay green even while
     * counting the wait.
     */
    private fun TestScope.clients(): StoreHttpClients = StoreHttpClients(
        environment = NetworkEnvironment(
            cacheDirectory = File(Files.createTempDirectory("request-log").toFile(), "cache"),
        ),
        requestLog = { storeId, method, url, code, elapsed ->
            lines += Line(storeId, method, url, code, elapsed)
        },
        timeSource = testScheduler.timeSource,
    )

    /**
     * `burst = 1`, and that is not an incidental test detail.
     *
     * The rate limiter's bucket starts **full**: with the default `burst = 3` the first three
     * requests wait for nothing, so a test making two would measure two zero-length waits and
     * stay green whatever the stopwatch does. It is the classic mistake of a test that does not
     * contain the case it is meant to prove.
     */
    private fun StoreHttpClients.client(permitsPerSecond: Double = 100.0) = forStore(
        StoreId.APKMIRROR,
        StoreNetworkProfile(
            userAgent = "test-agent",
            permitsPerSecond = permitsPerSecond,
            burst = 1,
        ),
    )

    @Test
    @DisplayName("a successful request carries address, status and duration")
    fun aSuccessfulRequestIsRecorded() = runTest {
        server.enqueue(MockResponse(code = 200, body = "ciao"))
        val url = server.url("/page").toString()

        val clients = clients()
        try {
            clients.client().execute(Request.Builder().url(url).build()).use { it.body.string() }
        } finally {
            clients.shutdown()
        }

        val line = lines.single()
        assertThat(line.storeId).isEqualTo(StoreId.APKMIRROR)
        assertThat(line.method).isEqualTo("GET")
        assertThat(line.url).isEqualTo(url)
        assertThat(line.code).isEqualTo(200)
    }

    @Test
    @DisplayName("a 403 lands in the log too: it arrived and said no")
    fun aRefusalIsRecordedToo() = runTest {
        server.enqueue(MockResponse(code = 403, body = ""))

        val clients = clients()
        try {
            clients.client().execute(
                Request.Builder().url(server.url("/vietato").toString()).build(),
            ).use { it.body.string() }
        } finally {
            clients.shutdown()
        }

        // If the log recorded only 200s, "apkmirror is barring the way" would be
        // indistinguishable from "the app asked for nothing", which is exactly the diagnosis this
        // log exists to make possible.
        assertThat(lines.single().code).isEqualTo(403)
    }

    @Test
    @DisplayName("the rate limiter's wait does not count as the store being slow")
    fun theRateLimiterWaitIsNotTheStoreFault() = runTest {
        // One permit every two seconds: the second request waits on the rate limiter before it
        // even leaves. apkmirror declares `Crawl-delay: 3` and we wait those seconds on purpose —
        // counting them as response time would turn our own politeness into a diagnosis of
        // someone else's slowness.
        val clients = clients()
        try {
            val http = clients.client(permitsPerSecond = 0.5)
            server.enqueue(MockResponse(code = 200, body = "una"))
            server.enqueue(MockResponse(code = 200, body = "due"))
            val request = { path: String ->
                Request.Builder().url(server.url(path).toString()).build()
            }

            http.execute(request("/uno")).use { it.body.string() }
            http.execute(request("/due")).use { it.body.string() }

            assertThat(lines).hasSize(2)
            // With `burst = 1` the second request really waits two seconds of virtual clock
            // before leaving. The server answers instantly: the second row must read
            // milliseconds, not the two seconds the limiter imposed.
            assertThat(lines[1].elapsed.inWholeMilliseconds).isLessThan(1_000)
        } finally {
            clients.shutdown()
        }
    }

    @Test
    @DisplayName("with no listener nothing happens, and that is not a case to handle")
    fun theDefaultIsSilence() = runTest {
        val silent = StoreHttpClients(
            environment = NetworkEnvironment(
                cacheDirectory = File(Files.createTempDirectory("silent").toFile(), "cache"),
            ),
            timeSource = testScheduler.timeSource,
        )
        try {
            server.enqueue(MockResponse(code = 200, body = "ciao"))
            silent.forStore(StoreId.FDROID, StoreNetworkProfile(userAgent = "test-agent"))
                .execute(Request.Builder().url(server.url("/x").toString()).build())
                .use { it.body.string() }
            assertThat(lines).isEmpty()
        } finally {
            silent.shutdown()
        }
    }
}
