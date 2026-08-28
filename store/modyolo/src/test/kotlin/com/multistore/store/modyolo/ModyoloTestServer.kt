package com.multistore.store.modyolo

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake `modyolo.com` serving the committed fixtures, **plus its CDN**.
 *
 * Unit tests never touch the network. The HTTP client stays the real one, with its interceptors and
 * its rate limiter: only who answers changes.
 *
 * ### Three things this double must do, and all three are necessary
 *
 *  1. **honour the `Referer`.** The POST to `admin-ajax.php` carries neither the id nor the
 *     variant: modyolo decides from the `Referer`, and without it answers 200 with an empty body. A
 *     double that served the fragment to anyone would turn green an adapter that does not send the
 *     `Referer` — that is, an adapter that downloads nothing in production;
 *  2. **serve the CDN**, because modyolo's `preflight` really queries it. It is the piece that sets
 *     this store apart from all the others, and without the fake CDN it would stay unproven;
 *  3. **answer 400 past the last page**, as WordPress does. It is the only store that does, and it
 *     is the case the contract test reaches by asking for page 9999.
 */
class ModyoloTestServer(private val server: MockWebServer) {

    /** The requests received, to check *how* the adapter queries the store. */
    val received: MutableList<RecordedRequest> = mutableListOf()

    /** CDN paths that must answer 500, the way a dead binary does. */
    val deadFiles: MutableSet<String> = mutableSetOf()

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

        if (path.startsWith("/$CDN_SEGMENT/")) {
            // About a quarter of the catalogue answers this way: the listing is there, the link
            // resolves, the file does not.
            return if (path in deadFiles) serverError() else cdnFile()
        }

        if (path == ModyoloConfig.AJAX_PATH) return ajax(request)
        if (path == ModyoloConfig.SEARCH_PATH) return search(request)
        if (path.startsWith("${ModyoloConfig.DETAIL_PATH}/")) {
            return json(
                when (path.substringAfterLast('/')) {
                    Fixtures.APP_ID -> Fixtures.DETAIL
                    Fixtures.SINGLE_ID -> Fixtures.DETAIL_SINGLE
                    else -> Fixtures.DETAIL_MISSING
                },
            )
        }
        if (path.startsWith("${ModyoloConfig.DOWNLOAD_PATH}/")) {
            return when (path.removePrefix("${ModyoloConfig.DOWNLOAD_PATH}/").substringBefore('/')) {
                Fixtures.APP_REF -> html(Fixtures.DOWNLOAD_PAGE)
                Fixtures.SINGLE_REF -> html(Fixtures.DOWNLOAD_PAGE_SINGLE)
                else -> notFound()
            }
        }
        return if (path == "/") html(Fixtures.DOWNLOAD_PAGE) else notFound()
    }

    private fun search(request: RecordedRequest): MockResponse {
        val query = request.url.queryParameter(ModyoloConfig.SEARCH_PARAM).orEmpty()
        val page = request.url.queryParameter(ModyoloConfig.PAGE_PARAM)?.toIntOrNull() ?: 1
        val excluded = request.url.queryParameter(ModyoloConfig.EXCLUDE_PARAM) != null

        // Past the last page WordPress answers 400, not an empty list.
        if (page > 1) return badRequest()

        return when {
            query.contains(Fixtures.QUERY_WITH_NSFW) ->
                json(if (excluded) Fixtures.SEARCH_NSFW_EXCLUDED else Fixtures.SEARCH_NSFW)
            query.contains(Fixtures.QUERY_WITH_RESULTS) -> json(Fixtures.SEARCH)
            else -> json(Fixtures.SEARCH_EMPTY)
        }
    }

    /**
     * The download AJAX call: answers **only** if the `Referer` is a variant's.
     *
     * This is the real, measured behaviour: with `Referer: /download/minecraft-19` (no index)
     * modyolo answers 200 with twenty empty bytes; with `/download/minecraft-19/1` it answers with
     * the fragment. Reproducing it here is what lets the test notice if the adapter stopped
     * sending it.
     */
    private fun ajax(request: RecordedRequest): MockResponse {
        val referer = request.headers[REFERER].orEmpty()
        if (!VARIANT_REFERER.containsMatchIn(referer)) return emptyFragment()
        val fixture = if (Fixtures.SINGLE_REF in referer) {
            Fixtures.DOWNLOAD_AJAX_SINGLE
        } else {
            Fixtures.DOWNLOAD_AJAX
        }
        return htmlBody(Fixtures.bytes(fixture), rewriteCdn = true)
    }

    private fun json(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "application/json; charset=UTF-8")
        .body(Buffer().write(Fixtures.bytes(fixture)))
        .build()

    private fun html(fixture: String): MockResponse = htmlBody(Fixtures.bytes(fixture), rewriteCdn = false)

    /**
     * The only change applied to a fixture: the CDN host becomes this server.
     *
     * It is unavoidable — the real fragment links `https://files-2.modyolo.com/…`, which a test
     * must not reach — and it does not touch what is being verified: which link is chosen, how it
     * is normalised, and what the `HEAD` answers.
     */
    private fun htmlBody(bytes: ByteArray, rewriteCdn: Boolean): MockResponse {
        val body = if (rewriteCdn) {
            var html = bytes.toString(Charsets.UTF_8)
            CDN_HOST_PREFIXES.forEach { html = html.replace(it, "$baseUrl/$CDN_SEGMENT/") }
            html.toByteArray()
        } else {
            bytes
        }
        return MockResponse.Builder()
            .code(HTTP_OK)
            .addHeader("Content-Type", "text/html; charset=utf-8")
            .body(Buffer().write(body))
            .build()
    }

    private fun emptyFragment(): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body("0")
        .build()

    private fun cdnFile(): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "application/vnd.android.package-archive")
        .body(Buffer().write(ByteArray(CDN_BODY_BYTES) { 0 }))
        .build()

    /** The dead binary: **500**, not 404. That is how modyolo says the file is not there. */
    private fun serverError(): MockResponse = MockResponse.Builder()
        .code(HTTP_SERVER_ERROR)
        .body("")
        .build()

    private fun badRequest(): MockResponse = MockResponse.Builder()
        .code(HTTP_BAD_REQUEST)
        .addHeader("Content-Type", "application/json; charset=UTF-8")
        .body(INVALID_PAGE_BODY)
        .build()

    private fun notFound(): MockResponse = MockResponse.Builder()
        .code(HTTP_NOT_FOUND)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body("")
        .build()

    private fun MockResponse.withoutBody(): MockResponse = newBuilder()
        .body("")
        .setHeader("Content-Length", body?.contentLength ?: 0L)
        .build()

    companion object {
        /** The segment under which this server pretends to be `files-2.modyolo.com`. */
        const val CDN_SEGMENT = "cdn"
        const val CDN_BODY_BYTES = 7

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500
        private const val REFERER = "Referer"
        /**
         * The two CDN hosts. `files.modyolo.com` serves the old entries — and it is the one whose
         * paths have **raw spaces** — `files-2` the new ones.
         */
        private val CDN_HOST_PREFIXES =
            listOf("https://files-2.modyolo.com/", "https://files.modyolo.com/")
        private const val INVALID_PAGE_BODY =
            """{"code":"rest_post_invalid_page_number",""" +
                """"message":"The page number requested is larger than the number of pages available.",""" +
                """"data":{"status":400}}"""

        /** `/download/{slug}-{id}/{n}`: without the trailing index, modyolo does not answer. */
        private val VARIANT_REFERER = Regex("""/download/[a-z0-9-]+-\d+/\d+$""")
    }
}
