package com.multistore.store.apkmody

import com.multistore.store.apkmody.ApkModyConfig
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake `apkmody.mobi` serving the committed fixtures.
 *
 * Unit tests never touch the network. The HTTP client stays the real one, with its interceptors
 * and its rate limiter: only the responder changes. That way the tests also cover what a parser
 * tested against a string does not — the User-Agent actually sent, and the final URL relative links
 * are resolved against.
 */
class ApkModyTestServer(private val server: MockWebServer) {

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
                // place.
                return if (request.method == "HEAD") response.withoutBody() else response
            }
        }
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path in missing) return notFound()
        overrides[path]?.let { return page(it) }

        val query = request.url.queryParameter(SEARCH_PARAM)
        if (path == "/" && query != null) {
            return page(if (query.contains(Fixtures.QUERY_WITH_RESULTS)) Fixtures.SEARCH else Fixtures.SEARCH_EMPTY)
        }

        return when (path) {
            "/${ApkModyConfig.POPULAR_SEGMENT}" -> page(Fixtures.POPULAR)
            "/" -> page(Fixtures.SEARCH)
            "/${Fixtures.APP_PATH}" -> page(Fixtures.DETAIL)
            "/${Fixtures.APP_PATH}/download" -> page(Fixtures.DOWNLOAD)
            "/${Fixtures.APP_PATH}/history" -> page(Fixtures.HISTORY)
            "/${Fixtures.APP_PATH}/${Fixtures.OLD_VERSION_SEGMENT}" -> page(Fixtures.HISTORY_VERSION)
            else -> notFound()
        }
    }

    private fun page(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(fixture)))
        .build()

    /**
     * The 404 is apkmody's **real** one, not an empty response.
     *
     * The store answers 404 with 226 KB of complete page — menu, footer, trending apps — and the
     * only mark distinguishing it is a `404` heading in place of the app header. A bodyless fake
     * 404 would hide the only real risk: a parser that finds something inside that page anyway.
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
        const val SEARCH_PARAM = "s"
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }
}
