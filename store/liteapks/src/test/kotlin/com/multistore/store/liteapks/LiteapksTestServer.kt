package com.multistore.store.liteapks

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake `liteapks.com` serving the committed fixtures.
 *
 * Unit tests never touch the network. The HTTP client stays the real one, with its interceptors and
 * its rate limiter: only who answers changes.
 *
 * ### The 404 is the real one, and it matters more than it seems
 *
 * liteapks answers 404 with 39 KB of full page — menu, sidebar, footer. A bodyless 404 would hide
 * two risks at once: that the detail parser finds something inside and returns a listing instead of
 * `NotFound`, and that the search parser mistakes the error page for an empty search. The same page
 * also answers **past the last page of results**, and that is where the `curl` measurement had seen
 * its "`paged=2` -> 404".
 */
class LiteapksTestServer(private val server: MockWebServer) {

    /** The requests received, to check *how* the adapter queries the store. */
    val received: MutableList<RecordedRequest> = mutableListOf()

    /** Paths that must answer 404 even though a fixture would exist for them. */
    val missing: MutableSet<String> = mutableSetOf()

    /** Substitutions: path -> fixture to serve in its place. */
    val overrides: MutableMap<String, String> = mutableMapOf()

    /**
     * Replacements with HTML built by the test: path -> body.
     *
     * Needed for the cases the site does not produce and which therefore have no fixture — a valid
     * listing **with no files at all**, say. The HTML is obtained by cutting the real page, not by
     * writing one from scratch: that way what remains is liteapks markup.
     */
    val rawOverrides: MutableMap<String, String> = mutableMapOf()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                return respond(request)
            }
        }
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path in missing) return notFound()
        overrides[path]?.let { return page(it) }
        rawOverrides[path]?.let { return body(it.toByteArray()) }

        request.url.queryParameter(SEARCH_PARAM)?.let { return searchPage(it, request) }

        return when (path) {
            "/" -> page(Fixtures.SEARCH)
            "/${Fixtures.GAME_REF}.html" -> page(Fixtures.GAME)
            "/${Fixtures.APP_REF}.html" -> page(Fixtures.APP)
            "/download/${Fixtures.GAME_STEM}" -> page(Fixtures.DOWNLOAD_GAME)
            "/download/${Fixtures.APP_STEM}" -> page(Fixtures.DOWNLOAD_APP)
            "/download/${Fixtures.GAME_STEM}/1" -> page(Fixtures.SLOT)
            "/download/${Fixtures.APP_STEM}/1" -> page(Fixtures.SLOT_RAW_SPACES)
            else -> notFound()
        }
    }

    /**
     * The search, which here lives entirely in the query: `?s={query}` and `?s={query}&paged={n}`.
     *
     * **Past the last page it answers 404**, like the real site. That is not a decorative detail:
     * it is what stops an adapter believing pagination is infinite, and the reason `hasMore` stops
     * rather than trying.
     */
    private fun searchPage(query: String, request: RecordedRequest): MockResponse {
        val page = request.url.queryParameter(PAGE_PARAM)?.toIntOrNull() ?: FIRST_PAGE
        return when (query) {
            Fixtures.QUERY_WITH_RESULTS -> if (page == FIRST_PAGE) page(Fixtures.SEARCH) else notFound()
            Fixtures.QUERY_PAGED -> when (page) {
                FIRST_PAGE -> page(Fixtures.SEARCH_PAGE_1)
                SECOND_PAGE -> page(Fixtures.SEARCH_PAGE_2)
                LAST_PAGE -> page(Fixtures.SEARCH_LAST_PAGE)
                else -> notFound()
            }
            else -> page(Fixtures.SEARCH_EMPTY)
        }
    }

    private fun page(fixture: String): MockResponse = body(Fixtures.bytes(fixture))

    private fun body(bytes: ByteArray): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(bytes))
        .build()

    private fun notFound(): MockResponse = MockResponse.Builder()
        .code(HTTP_NOT_FOUND)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(Fixtures.NOT_FOUND)))
        .build()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val SEARCH_PARAM = "s"
        const val PAGE_PARAM = "paged"
        const val FIRST_PAGE = 1
        const val SECOND_PAGE = 2
        const val LAST_PAGE = 4
    }
}
