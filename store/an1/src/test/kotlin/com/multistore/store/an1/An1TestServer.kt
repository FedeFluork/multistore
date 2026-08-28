package com.multistore.store.an1

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake `an1.com` serving the committed fixtures, **plus its CDN**.
 *
 * Unit tests never touch the network. The HTTP client stays the real one, with its interceptors
 * and its rate limiter: only the responder changes.
 *
 * ### Why this server must also impersonate the file host
 *
 * On an1 the `HEAD` on the CDN **is part of resolving the download**: that is where the SHA-256
 * and the exact size come from. A double serving only the pages would leave that piece untested,
 * and it is precisely the piece that distinguishes this store from the others without a hash. The
 * real pages contain the real host, though, and a local server does not have it: which is why the
 * download host is a configuration field and the download parser rewrites the host before
 * returning — see [downloadRewrite].
 */
class An1TestServer(private val server: MockWebServer) {

    /** The requests received, to verify *how* the adapter queries the store. */
    val received: MutableList<RecordedRequest> = mutableListOf()

    /** Paths that must answer 404 even where a fixture exists. */
    val missing: MutableSet<String> = mutableSetOf()

    /** Substitutions: path -> fixture to serve in its place. */
    val overrides: MutableMap<String, String> = mutableMapOf()

    /** The headers the fake CDN adds to the file's `HEAD`. Emptying it = no hash. */
    val cdnHeaders: MutableMap<String, String> = mutableMapOf(
        CHECKSUM_HEADER to Fixtures.APP_SHA256,
    )

    /**
     * The fake file's body, **real**, so that `Content-Length` is real.
     *
     * Declaring a `Content-Length` of eighty megabytes by hand on an empty response would prove we
     * can write a header, not that we can read one: MockWebServer computes its own from the body,
     * and the two would contradict each other. Seven bytes are enough — what is being verified is
     * that the expected size comes **from the response** and not from the page, which writes
     * `79.9Mb` rounded.
     */
    val cdnBody: ByteArray = ByteArray(CDN_BODY_BYTES) { 0 }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                val response = respond(request)
                // A response to HEAD has no body: sending one leaves bytes in the socket that the
                // next request reads as a status line.
                return if (request.method == "HEAD") response.withoutBody() else response
            }
        }
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    /** The host the double serves in place of the three real ones. */
    val downloadHost: String get() = server.hostName

    /**
     * The download fixture with the CDN host rewritten onto this server.
     *
     * It is the only modification applied to a fixture, and it is unavoidable: the real page links
     * a real host, which a test must not reach. What matters — **which** of the page's two `.apk`
     * files gets chosen — stays intact, because the rewrite touches both the same way.
     */
    private fun downloadRewrite(fixture: String): ByteArray {
        var html = Fixtures.html(fixture)
        An1Config.DEFAULT_DOWNLOAD_HOSTS.forEach { host ->
            html = html.replace("https://$host/", "$baseUrl/$CDN_SEGMENT/")
        }
        return html.toByteArray()
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path in missing) return notFound()
        overrides[path]?.let { return page(it) }

        if (path.startsWith("/$CDN_SEGMENT/")) return cdnFile()

        if (path == SEARCH_PATH) {
            val query = request.url.queryParameter(STORY_PARAM).orEmpty()
            if (!query.contains(Fixtures.QUERY_WITH_RESULTS)) return page(Fixtures.SEARCH_EMPTY)
            val start = request.url.queryParameter(SEARCH_START_PARAM)
            return page(if (start == null) Fixtures.SEARCH else Fixtures.SEARCH_PAGE_2)
        }

        return when (path) {
            "/" -> page(Fixtures.SEARCH)
            "/${Fixtures.APP_REF}.html" -> page(Fixtures.DETAIL)
            "/${Fixtures.GAME_REF}.html" -> page(Fixtures.DETAIL_GAME)
            downloadPath(Fixtures.APP_ID) -> download(Fixtures.DOWNLOAD)
            downloadPath(Fixtures.GAME_ID) -> download(Fixtures.DOWNLOAD_SECOND_HOST)
            // No rewriting: the shortener must stay the real one, or the test would prove we
            // discard a host we invented ourselves.
            downloadPath(Fixtures.OFFSITE_ID) -> page(Fixtures.DOWNLOAD_OFFSITE)
            else -> notFound()
        }
    }

    private fun downloadPath(id: String): String =
        "/${An1Config.DOWNLOAD_PREFIX}$id${An1Config.DOWNLOAD_SUFFIX}"

    private fun page(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(fixture)))
        .build()

    private fun download(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(downloadRewrite(fixture)))
        .build()

    private fun cdnFile(): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "application/vnd.android.package-archive")
        .apply { cdnHeaders.forEach { (name, value) -> addHeader(name, value) } }
        .body(Buffer().write(cdnBody))
        .build()

    /**
     * The 404 is an1's **real** one, not an empty response.
     *
     * an1 answers 404 with 31 KB of complete page, menu and footer included. A bodyless 404 would
     * hide the only risk that matters: that the parser finds something in it and returns a listing
     * instead of a not-found.
     */
    private fun notFound(): MockResponse = MockResponse.Builder()
        .code(HTTP_NOT_FOUND)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(Fixtures.NOT_FOUND)))
        .build()

    /**
     * The same response without a body, but with the `Content-Length` it would have had.
     *
     * That is what a real server does answering `HEAD`, and it is exactly the piece needed here:
     * an1's expected APK size comes from that header and from nowhere else. A double answering
     * `Content-Length: 0` would make the test green with an expected size of zero, i.e. with
     * pre-install verification comparing zero bytes.
     */
    private fun MockResponse.withoutBody(): MockResponse = newBuilder()
        .body("")
        .setHeader("Content-Length", body?.contentLength ?: 0L)
        .build()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val SEARCH_PATH = "/index.php"
        const val STORY_PARAM = "story"
        const val SEARCH_START_PARAM = "search_start"
        const val CDN_SEGMENT = "cdn"
        const val CDN_BODY_BYTES = 7
        const val CHECKSUM_HEADER = "x-amz-meta-checksum-sha256"
    }
}
