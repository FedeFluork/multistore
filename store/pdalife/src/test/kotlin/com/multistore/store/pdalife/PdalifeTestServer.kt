package com.multistore.store.pdalife

import com.multistore.store.pdalife.PdalifeConfig
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake `pdalife.com` serving the committed fixtures.
 *
 * Unit tests never touch the network. The HTTP client stays the real one, with its interceptors and
 * its rate limiter: only who answers changes.
 *
 * ### The download's first hop answers 301, like the real one
 *
 * `/dwn/{hash}.html` on pdalife is a redirect towards `mobdisc.com`, and the double reproduces it
 * towards its own `/mobdisc/`. The adapter does not need it — it just hands that URL over — but it
 * is needed to **prove** that it does not follow it: an adapter that started reading the landing
 * page would find three buttons, two of which are adverts.
 */
class PdalifeTestServer(private val server: MockWebServer) {

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
     * listing **with no versions at all**, say. The HTML is obtained by cutting the real page, not
     * by writing one from scratch: that way what remains is pdalife markup.
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

        searchFixture(path)?.let { return page(it) }

        return when (path) {
            "/${PdalifeConfig.RECENT_FEED_PATH}/" -> feed(Fixtures.RECENT_FEED)
            "/" -> page(Fixtures.SEARCH)
            "/${Fixtures.APP_REF}.html" -> page(Fixtures.DETAIL)
            "/${Fixtures.MOD_REF}.html" -> page(Fixtures.DETAIL_MOD)
            "/${Fixtures.NO_PACKAGE_REF}.html" -> page(Fixtures.DETAIL_NO_PACKAGE)
            "/dwn/${Fixtures.APP_DOWNLOAD_HASH}.html" -> redirectToMobdisc()
            "/dwn/${Fixtures.MOD_DOWNLOAD_HASH}.html" -> redirectToMobdisc()
            MOBDISC_PATH -> page(Fixtures.DOWNLOAD)
            else -> notFound()
        }
    }

    /**
     * The search, which here lives entirely in the path: `/search/{slug}/` and
     * `/search/{slug}/page-N/`.
     *
     * The slug is compared against the one the adapter would have produced, not against the raw
     * query: it is the only way for the double to also exercise slugification instead of bypassing
     * it.
     */
    private fun searchFixture(path: String): String? {
        val segments = path.trim('/').split('/')
        if (segments.firstOrNull() != SEARCH_SEGMENT || segments.size !in SEARCH_SEGMENTS) return null
        val page = segments.getOrNull(2)
        return when (segments.getOrNull(1).orEmpty()) {
            Fixtures.QUERY_WITH_RESULTS -> if (page == null) Fixtures.SEARCH else Fixtures.SEARCH_PAGE_2
            Fixtures.QUERY_OTHER_OS -> Fixtures.SEARCH_OTHER_OS
            Fixtures.QUERY_UNRATED -> Fixtures.SEARCH_UNRATED
            else -> Fixtures.SEARCH_EMPTY
        }
    }

    /** An RSS feed: `application/rss+xml`, which is what pdalife sends. */
    private fun feed(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "application/rss+xml; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(fixture)))
        .build()

    private fun page(fixture: String): MockResponse = body(Fixtures.bytes(fixture))

    private fun body(bytes: ByteArray): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(bytes))
        .build()

    /** The 301 pdalife sends towards the other domain. See the note at the head of the class. */
    private fun redirectToMobdisc(): MockResponse = MockResponse.Builder()
        .code(HTTP_MOVED_PERMANENTLY)
        .addHeader("Location", "$baseUrl$MOBDISC_PATH")
        .build()

    /**
     * The 404 is pdalife's **real** one, not an empty response.
     *
     * pdalife answers 404 with 33 KB of full page, menu, sidebar and footer included — and that
     * sidebar contains ten links to listings with the same `a.color-android` as the results. A
     * bodyless 404 would hide the only risk that matters: that the parser finds something inside
     * and returns a listing instead of `NotFound`.
     */
    private fun notFound(): MockResponse = MockResponse.Builder()
        .code(HTTP_NOT_FOUND)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(Fixtures.NOT_FOUND)))
        .build()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_MOVED_PERMANENTLY = 301
        const val HTTP_NOT_FOUND = 404
        const val SEARCH_SEGMENT = "search"
        val SEARCH_SEGMENTS = 2..3
        const val MOBDISC_PATH = "/mobdisc/download.html"
    }
}
