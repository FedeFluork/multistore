package com.multistore.store.apkcombo

import com.multistore.store.apkcombo.ApkComboConfig
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake `apkcombo.com` serving the committed fixtures.
 *
 * Unit tests never touch the network. The HTTP client stays the real one, with its interceptors
 * and its rate limiter: only the responder changes. That way the tests also cover what a parser
 * tested against a string does not — the User-Agent actually sent, the redirects followed, the
 * final URL used to resolve relative links.
 */
class ApkComboTestServer(private val server: MockWebServer) {

    /** The requests received, to verify *how* the adapter queries the store. */
    val received: MutableList<RecordedRequest> = mutableListOf()

    /** Paths that must answer 404 even where a fixture exists. */
    val missing: MutableSet<String> = mutableSetOf()

    /** Substitutions: path -> fixture to serve in its place. */
    val overrides: MutableMap<String, String> = mutableMapOf()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                val response = respond(request)
                // A response to HEAD has no body: sending one leaves bytes in the socket that the
                // next request reads as a status line, and the resulting error points at the wrong
                // place. The same trap already met on the fake F-Droid.
                return if (request.method == "HEAD") response.withoutBody() else response
            }
        }
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path in missing) return notFound()
        overrides[path]?.let { return page(it) }

        return when {
            path.startsWith(SEARCH_PREFIX) -> {
                val query = path.removePrefix(SEARCH_PREFIX)
                page(if (query.contains(Fixtures.QUERY_WITH_RESULTS)) Fixtures.SEARCH else Fixtures.SEARCH_EMPTY)
            }

            path == "/${ApkComboConfig.RECENT_FEED_PATH}" -> feed(Fixtures.RECENT_FEED)
            path == "/" -> page(Fixtures.DETAIL)
            path == "/${Fixtures.APP_PATH}/" -> page(Fixtures.DETAIL)
            path == "/${Fixtures.APP_PATH}/old-versions/" -> page(Fixtures.OLD_VERSIONS)
            path == "/${Fixtures.APP_PATH}/download/apk" -> page(Fixtures.DOWNLOAD)
            path.startsWith("/${Fixtures.APP_PATH}/download/phone-") -> page(Fixtures.DOWNLOAD_OLD)

            else -> notFound()
        }
    }

    /**
     * An RSS feed, with its `Content-Type`.
     *
     * The type is `text/xml` and not `text/html` on purpose: it is what apkcombo sends, and serving
     * another would blind the test to the day the adapter decided based on that header.
     */
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

    /**
     * The 404 is apkcombo's **real** one, not an empty response.
     *
     * The store answers 404 with 55 KB of complete page, menu and suggestions included. A bodyless
     * fake 404 would test a case that does not exist, and would hide the only real risk: a parser
     * that finds something inside that page anyway.
     */
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
        const val SEARCH_PREFIX = "/search/"
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }
}
