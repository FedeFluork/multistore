package com.multistore.store.fdroid

import java.io.File
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import okio.GzipSink
import okio.buffer

/**
 * A fake `f-droid.org` serving the committed fixtures.
 *
 * Unit tests never touch the network. This is how to respect that rule without giving up on
 * exercising the network code: the HTTP client is the real one, the interceptors are the real ones,
 * the rate limiter is the real one — only who answers changes.
 */
class FdroidTestServer(private val server: MockWebServer) {

    /** The requests received, to check *how* the adapter queries the store. */
    val received: MutableList<RecordedRequest> = mutableListOf()

    /** Paths that must answer 404, to exercise the error branches. */
    val missing: MutableSet<String> = mutableSetOf()

    /** Overrides: path -> the name of the fixture to serve in its place. */
    val overrides: MutableMap<String, String> = mutableMapOf()

    /** If `true`, the index is served compressed, as the real server does. */
    var gzipIndex: Boolean = false

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                val response = respond(request)
                // A HEAD response has no body. Sending one anyway leaves the bytes in the socket,
                // which the next request reads as a status line: the resulting error ("Unexpected
                // status line: PK...") points at the wrong place, and one ends up looking for a bug
                // in the client that is actually in the fake server.
                return if (request.method == "HEAD") headOnly(response) else response
            }
        }
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    val searchApiUrl: String get() = server.url("/search/api/search_apps").toString()

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path in missing) return MockResponse.Builder().code(HTTP_NOT_FOUND).build()

        overrides[path]?.let { return fileResponse(it, path) }

        return when {
            path.endsWith("/search/api/search_apps") -> {
                val query = request.url.queryParameter("q").orEmpty()
                fileResponse(
                    if (query.contains("f-droid", ignoreCase = true) || query == "tor") {
                        Fixtures.SEARCH_APPS
                    } else {
                        Fixtures.SEARCH_APPS_EMPTY
                    },
                    path,
                )
            }

            path == "/repo/entry.jar" -> fileResponse(Fixtures.ENTRY_JAR, path)
            path.startsWith("/repo/") -> {
                val name = path.removePrefix("/repo/")
                runCatching { Fixtures.file(name) }.fold(
                    onSuccess = { fileResponse(name, path) },
                    onFailure = { MockResponse.Builder().code(HTTP_NOT_FOUND).build() },
                )
            }

            else -> MockResponse.Builder().code(HTTP_NOT_FOUND).build()
        }
    }

    private fun headOnly(response: MockResponse): MockResponse = response.newBuilder()
        .body("")
        .setHeader("Content-Length", response.body?.contentLength ?: 0L)
        .build()

    private fun fileResponse(fixtureName: String, path: String): MockResponse {
        val file: File = Fixtures.file(fixtureName)
        val builder = MockResponse.Builder().code(HTTP_OK)
        val raw = Buffer().write(file.readBytes())
        return if (gzipIndex && fixtureName.endsWith(".json") && path.contains("index")) {
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeAll(raw) }
            builder.addHeader("Content-Encoding", "gzip").body(compressed).build()
        } else {
            builder.body(raw).build()
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }
}
