package com.multistore.core.challenge

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.StoreId
import com.multistore.core.network.challenge.ChallengeOutcome
import com.multistore.core.network.cookie.ClearanceCookieJar
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The silent WebView rung, without Chromium.
 *
 * The split is the same as `ShellInstaller` with `PrivilegedShell`, and for the same reason: a
 * WebView does not exist on a JVM and under Robolectric runs no JavaScript, so a resolver building
 * one itself would be entirely unverifiable. Split this way, **everything** except running the
 * challenge is tested here — i.e. all the things that, wrong, fail silently: the cookie that does not
 * reach the store, the User-Agent that does not match, the WebView opened twice, the `HEAD` that
 * becomes a download.
 */
class WebViewSilentResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: StoreHttpClients
    private lateinit var cookies: ClearanceCookieJar

    private val storeUserAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) Chrome/128 Mobile"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cookies = ClearanceCookieJar()
        clients = StoreHttpClients(
            environment = NetworkEnvironment(
                cacheDirectory = File(Files.createTempDirectory("challenge").toFile(), "cache"),
            ),
            cookieJar = cookies,
        )
    }

    @After
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    private fun client(): StoreHttpClient =
        clients.forStore(StoreId.LITEAPKS, StoreNetworkProfile(userAgent = storeUserAgent))

    /** An engine that runs nothing and reports what it was asked. */
    private class FakeEngine(
        private val outcome: (SilentChallengeRequest) -> SilentChallengeResult,
        private val work: Duration = Duration.ZERO,
    ) : SilentChallengeEngine {
        val requests = mutableListOf<SilentChallengeRequest>()

        override suspend fun solve(request: SilentChallengeRequest): SilentChallengeResult {
            requests += request
            if (work > Duration.ZERO) delay(work)
            return outcome(request)
        }
    }

    private fun solving(cookie: String?) = FakeEngine({ req ->
        SilentChallengeResult.Solved(finalUrl = req.url, cookieHeader = cookie)
    })

    @Test
    fun `the cookie obtained by the WebView reaches the store`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "<html>ok</html>"))
        val resolver = WebViewSilentResolver(solving("cf_clearance=abc123"), cookies)

        val response = resolver.attempt(
            Request.Builder().url(server.url("/spotify-2.html")).build(),
            client(),
        )

        response?.close()
        // It is the whole rung in one line: a challenge solved and not transferred is of no use,
        // because the real request is still made by OkHttp.
        assertThat(server.takeRequest().headers["Cookie"]).isEqualTo("cf_clearance=abc123")
    }

    @Test
    fun `the User-Agent asked of the WebView is the store's`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "<html>ok</html>"))
        val engine = solving("cf_clearance=abc123")
        val resolver = WebViewSilentResolver(engine, cookies)

        resolver.attempt(Request.Builder().url(server.url("/spotify-2.html")).build(), client())
            ?.close()

        // A `cf_clearance` is tied to the UA it was obtained with. With two different UAs the cookie
        // comes back valid and useless, and the symptom — the WebView passes, the retry does not —
        // sends one looking for the fault everywhere but here.
        assertThat(engine.requests.single().userAgent).isEqualTo(storeUserAgent)
    }

    @Test
    fun `with no cookie there is no retry`() = runTest {
        val resolver = WebViewSilentResolver(solving(cookie = null), cookies)

        val response = resolver.attempt(
            Request.Builder().url(server.url("/spotify-2.html")).build(),
            client(),
        )

        assertThat(response).isNull()
        // Repeating the request identical to the one that has just taken a 403 is not an extra
        // attempt: it is another refusal, and on a site behind Cloudflare also an insistence.
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a timeout is not a retry`() = runTest {
        val resolver = WebViewSilentResolver(FakeEngine({ SilentChallengeResult.TimedOut }), cookies)

        val response = resolver.attempt(
            Request.Builder().url(server.url("/spotify-2.html")).build(),
            client(),
        )

        assertThat(response).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `browser not challenged - no retry, and no waiting for the timeout`() = runTest {
        val engine = FakeEngine({ req -> SilentChallengeResult.NoChallenge(req.url) })
        val resolver = WebViewSilentResolver(engine, cookies)

        val response = resolver.attempt(
            Request.Builder().url(server.url("/spotify-2.html")).build(),
            client(),
        )

        // Measured on the emulator on 25/08/2026: on liteapks the Chromium engine receives 200 where
        // OkHttp in HTTP/1.1 receives 403. The browser obtained nothing OkHttp does not already have,
        // so there is nothing to transfer and retrying would be only a second refusal — on a site
        // behind Cloudflare, also an insistence.
        assertThat(response).isNull()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(engine.requests).hasSize(1)
    }

    @Test
    fun `a device with no WebView does not make the ladder fail`() = runTest {
        val resolver = WebViewSilentResolver(
            FakeEngine({ SilentChallengeResult.Unavailable("MissingWebViewPackageException") }),
            cookies,
        )

        // `null` means "this rung has nothing to offer", which is exactly the case: the ladder carries
        // on or closes with the real block, not with an exception.
        assertThat(
            resolver.attempt(Request.Builder().url(server.url("/spotify-2.html")).build(), client()),
        ).isNull()
    }

    @Test
    fun `two requests together open a single WebView`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "<html>ok</html>"))
        server.enqueue(MockResponse(code = 200, body = "<html>ok</html>"))
        val engine = FakeEngine(
            outcome = { req -> SilentChallengeResult.Solved(req.url, "cf_clearance=abc123") },
            work = 50.milliseconds,
        )
        val resolver = WebViewSilentResolver(engine, cookies)
        val http = client()

        listOf("/spotify-2.html", "/telegram-3.html")
            .map { path ->
                async { resolver.attempt(Request.Builder().url(server.url(path)).build(), http) }
            }
            .awaitAll()
            .forEach { it?.close() }

        // The second challenge would have been for the **same** transit permit: not only wasted, but
        // also a second execution of the challenge, which to a site behind Cloudflare looks exactly
        // like what that challenge is looking for.
        assertThat(engine.requests).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a HEAD does not take the WebView to the file`() = runTest {
        server.enqueue(MockResponse(code = 200))
        val engine = solving("cf_clearance=abc123")
        val resolver = WebViewSilentResolver(engine, cookies)

        resolver.attempt(
            Request.Builder().url(server.url("/files/gta-san-andreas.apk")).head().build(),
            client(),
        )?.close()

        // A WebView can only do GET: pointing it at a HEAD's URL would mean **downloading** the object
        // that HEAD only meant to query — on an1 that is the whole APK, inside a browser engine. The
        // transit permit belongs to the host anyway.
        assertThat(engine.requests.single().url).isEqualTo(server.url("/").toString())
    }

    @Test
    fun `a GET stays on the page that was challenged`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "<html>ok</html>"))
        val engine = solving("cf_clearance=abc123")
        val resolver = WebViewSilentResolver(engine, cookies)

        resolver.attempt(Request.Builder().url(server.url("/spotify-2.html")).build(), client())
            ?.close()

        // It is the page Cloudflare decided to challenge on, and the one a browser would load:
        // changing it would mean solving a challenge different from the one posed.
        assertThat(engine.requests.single().url).isEqualTo(server.url("/spotify-2.html").toString())
    }

    @Test
    fun `inside the ladder, the 403 becomes a 200 at the WebView rung`() = runTest {
        // The fake Cloudflare does not count responses: it **looks at the cookie**. It is the only
        // shape that proves what we want to prove — a queue of "403, 403, 200" would turn green even
        // for a rung that transfers nothing, because what passed would be the third response and not
        // the transit permit. This way instead, with the transfer removed, the server still says 403.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.headers["Cookie"]?.contains("cf_clearance=") == true) {
                    MockResponse(code = 200, body = "<html>the real page</html>")
                } else {
                    // The real shape, measured on liteapks on 25/08/2026.
                    MockResponse.Builder()
                        .code(403)
                        .setHeader("cf-mitigated", "challenge")
                        .body("<title>Just a moment...</title>")
                        .build()
                }
        }

        val resolver = WebViewSilentResolver(solving("cf_clearance=abc123"), cookies)
        val laddered = StoreHttpClients(
            environment = NetworkEnvironment(
                cacheDirectory = File(Files.createTempDirectory("ladder").toFile(), "cache"),
            ),
            androidResolvers = listOf(resolver),
            strategySource = { ChallengeStrategy.BALANCED },
            cookieJar = cookies,
        )

        try {
            val http = laddered.forStore(StoreId.LITEAPKS, StoreNetworkProfile(userAgent = storeUserAgent))
            val outcome = http.escalator.execute(
                Request.Builder().url(server.url("/spotify-2.html")).build(),
                http,
            )

            assertThat(outcome).isInstanceOf(ChallengeOutcome.Passed::class.java)
            val passed = outcome as ChallengeOutcome.Passed
            // The rung reached is what ends up in `health_events`: it is the only way of knowing, by
            // reading the diagnostics, that that store costs a WebView.
            assertThat(passed.tier).isEqualTo(3)
            passed.response.close()
        } finally {
            laddered.shutdown()
        }
    }
}
