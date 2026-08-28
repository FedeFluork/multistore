package com.multistore.store.apkmirror

import com.multistore.store.apkmirror.ApkMirrorConfig
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake `www.apkmirror.com` serving the committed fixtures.
 *
 * Unit tests never touch the network. The HTTP client stays the real one, with its User-Agent
 * interceptor and rate limiter: it is the only way to prove the declared UA really reaches the
 * socket, which on this store is the difference between 200 and 403.
 *
 * It can also **fake the challenge**: [challengeOn] makes a path answer with the real 403 and
 * mitigation header, captured over HTTP/2. That proves the detector recognises it and that the
 * adapter does not try to extract selectors from it — because a parse failure on a challenge page
 * sends people looking for a markup change that never happened.
 */
class ApkMirrorTestServer(private val server: MockWebServer) {

    val received: MutableList<RecordedRequest> = mutableListOf()

    /** Paths that must answer 404. */
    val missing: MutableSet<String> = mutableSetOf()

    /** Paths that must answer with Cloudflare's challenge page. */
    val challengeOn: MutableSet<String> = mutableSetOf()

    val overrides: MutableMap<String, String> = mutableMapOf()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                val response = respond(request)
                return if (request.method == "HEAD") response.withoutBody() else response
            }
        }
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path in challengeOn) return challenge()
        if (path in missing) return notFound()
        overrides[path]?.let { return page(it) }

        // Search lives on the root: it is told apart by a query parameter, not by the path.
        request.url.queryParameter("s")?.let { query ->
            return page(if (query == Fixtures.QUERY_WITH_RESULTS) Fixtures.SEARCH else Fixtures.SEARCH_EMPTY)
        }

        return when {
            path == "/${ApkMirrorConfig.RECENT_FEED_SEGMENT}/" -> feed(Fixtures.RECENT_FEED)
            path == "/" -> page(Fixtures.APP)
            path.endsWith("/download/") -> page(Fixtures.INTERSTITIAL)
            path == "/apk/${Fixtures.APP_PATH}/" -> page(Fixtures.APP)
            path == "/apk/${Fixtures.RELEASE_PATH}/" -> page(Fixtures.RELEASE)
            path == "/apk/${Fixtures.VARIANT_APK_PATH}/" -> page(Fixtures.VARIANT_APK)
            path == "/apk/${Fixtures.VARIANT_BUNDLE_PATH}/" -> page(Fixtures.VARIANT_BUNDLE)
            else -> notFound()
        }
    }

    /** An RSS feed, with the `Content-Type` apkmirror actually sends. */
    private fun feed(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/xml; charset=UTF-8")
        .body(Buffer().write(Fixtures.bytes(fixture)))
        .build()

    private fun page(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(fixture)))
        .build()

    /** The **real** challenge: 403, the mitigation header, and a "Just a moment…" body. */
    private fun challenge(): MockResponse = MockResponse.Builder()
        .code(HTTP_FORBIDDEN)
        .addHeader("Content-Type", "text/html; charset=UTF-8")
        .addHeader("cf-mitigated", "challenge")
        .body(Buffer().write(Fixtures.bytes(Fixtures.CHALLENGE)))
        .build()

    private fun notFound(): MockResponse = MockResponse.Builder()
        .code(HTTP_NOT_FOUND)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(Fixtures.NOT_FOUND)))
        .build()

    private fun MockResponse.withoutBody(): MockResponse = newBuilder()
        .body("")
        .setHeader("Content-Length", body?.contentLength ?: 0L)
        .build()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}
