package com.multistore.store.uptodown

import com.multistore.store.uptodown.UptodownConfig
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A fake uptodown serving the committed fixtures.
 *
 * ### Listings live on a subdomain, and a local server has none
 *
 * `https://telegram.en.uptodown.com/android` cannot be reproduced on `127.0.0.1`. That is why
 * [UptodownConfig.appUrlTemplate] is a template with `{slug}` rather than a concatenation: here it
 * is pointed at `/app/{slug}/android` and the adapter builds URLs this server can serve, while in
 * production it builds subdomains.
 *
 * Recognition instead stays the real one ([UptodownConfig.appHostSuffix]), and must: the fixtures
 * contain uptodown's authentic hrefs, and it is on those that the search parser has to read the
 * slug.
 */
class UptodownTestServer(private val server: MockWebServer) {

    val received: MutableList<RecordedRequest> = mutableListOf()

    /** Paths that must answer 404 even though a fixture would exist for them. */
    val missing: MutableSet<String> = mutableSetOf()

    /** Substitutions: path -> fixture to serve in its place. */
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

    /** The URL template to give the configuration under test. */
    val appUrlTemplate: String get() = "$baseUrl/app/${UptodownConfig.SLUG_PLACEHOLDER}/android"

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path in missing) return notFound()
        overrides[path]?.let { return page(it) }

        if (path == SEARCH_PATH) {
            val query = request.url.queryParameter(UptodownConfig.QUERY_PARAM).orEmpty()
            return page(
                if (query.contains(Fixtures.QUERY_WITH_RESULTS)) Fixtures.SEARCH else Fixtures.SEARCH_EMPTY,
            )
        }

        val app = "/app/${Fixtures.APP_SLUG}/${UptodownConfig.PLATFORM}"
        return when (path) {
            "/${UptodownConfig.PLATFORM}/${UptodownConfig.TOP_SEGMENT}" -> page(Fixtures.TOP)
            "/${UptodownConfig.PLATFORM}/${UptodownConfig.RECENT_SEGMENT}" -> page(Fixtures.LATEST_UPDATES)
            "/" -> page(Fixtures.SEARCH)
            app -> page(Fixtures.DETAIL)
            "$app/${UptodownConfig.VERSIONS_SEGMENT}" -> page(Fixtures.VERSIONS)
            "$app/${UptodownConfig.DOWNLOAD_SEGMENT}" -> page(Fixtures.DOWNLOAD)
            "$app/${UptodownConfig.DOWNLOAD_SEGMENT}/${Fixtures.OLD_VERSION_ID}" -> page(Fixtures.DOWNLOAD_OLD)
            else -> notFound()
        }
    }

    private fun page(fixture: String): MockResponse = MockResponse.Builder()
        .code(HTTP_OK)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(Buffer().write(Fixtures.bytes(fixture)))
        .build()

    /**
     * The 404 is uptodown's **real** one: 36 KB of full page with menu and suggestions.
     *
     * A fake bodyless 404 would hide the only risk: that the parser finds something inside it.
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
        val SEARCH_PATH = "/${UptodownConfig.PLATFORM}/${UptodownConfig.SEARCH_SEGMENT}"
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }
}
