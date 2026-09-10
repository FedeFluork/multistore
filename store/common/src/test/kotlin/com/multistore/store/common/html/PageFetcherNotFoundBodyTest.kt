package com.multistore.store.common.html

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreId
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Reading the body of a 404 — the opt-in, and the two things that keep it narrow.
 *
 * A 404 normally becomes [StoreError.NotFound] without the body being read, and on eight of the
 * nine stores that stays exactly right. pdalife is the measured exception: since 06/09/2026 it
 * answers **404 with its complete search page** when a query matches nothing, so collapsing the
 * code into `NotFound` reported a store that had answered correctly as a store that had failed.
 *
 * The danger of the flag is the obvious one — "this address is gone" quietly becoming "no
 * results" — so what is tested here is not only that it works, but that it is **off** by default
 * and that it does not invent a page where there is none.
 */
@DisplayName("PageFetcher — the body of a 404")
class PageFetcherNotFoundBodyTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: StoreHttpClients

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val work = Files.createTempDirectory("page-fetcher-404").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(work, "cache")))
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    private fun client(): StoreHttpClient =
        clients.forStore(StoreId.PDALIFE, StoreNetworkProfile(userAgent = "test-agent"))

    @Test
    @DisplayName("by default a 404 is NotFound and its body is never seen")
    fun theDefaultIsUnchanged() = runTest {
        server.enqueue(MockResponse(code = 404, body = "<html><body>a real page</body></html>"))

        val result = PageFetcher(client()).get(server.url("/search/nothing/").toString())

        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("asked for, the body arrives with the 404 written on it")
    fun theBodyArrivesWhenAskedFor() = runTest {
        server.enqueue(MockResponse(code = 404, body = "<html><body>a real page</body></html>"))

        val result = PageFetcher(client())
            .get(server.url("/search/nothing/").toString(), readBodyOnNotFound = true)

        assertThat(result).isInstanceOf(StoreResult.Success::class.java)
        val page = (result as StoreResult.Success).value
        assertThat(page.html).contains("a real page")
        // The code travels with the page rather than being inferred: the adapter has to be able to
        // tell "the store answered the search" from "the store answered 404 with the search page",
        // and only the second needs proving before it can be read as an empty result.
        assertThat(page.isNotFound).isTrue()
    }

    @Test
    @DisplayName("a 404 with no body stays NotFound, and does not become a network fault")
    fun anEmptyNotFoundIsStillNotFound() = runTest {
        server.enqueue(MockResponse(code = 404, body = ""))

        val result = PageFetcher(client())
            .get(server.url("/search/nothing/").toString(), readBodyOnNotFound = true)

        // The blank-body guard exists for a 200, where an empty answer is a failure disguised as
        // success. Reusing it here would replace a diagnosis that is right — the address is not
        // there — with one that is not, and send whoever read it looking for a network fault.
        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isEqualTo(StoreError.NotFound)
    }

    @Test
    @DisplayName("the opt-in is about 404 only: a 500 is still a failure")
    fun otherCodesAreUnaffected() = runTest {
        server.enqueue(MockResponse(code = 500, body = "<html><body>oops</body></html>"))

        val result = PageFetcher(client())
            .get(server.url("/search/nothing/").toString(), readBodyOnNotFound = true)

        assertThat(result).isInstanceOf(StoreResult.Failure::class.java)
        assertThat((result as StoreResult.Failure).error).isNotEqualTo(StoreError.NotFound)
    }
}
