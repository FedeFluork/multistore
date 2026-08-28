package com.multistore.core.remoteconfig

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The network side: when it asks, what it agrees to download, and how it describes a host that does
 * not answer.
 *
 * MockWebServer and not the real network: a unit test never touches the network, and the clock does
 * not really make time pass.
 */
class RemoteConfigFetcherTest {

    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val keys = SigningFixture()
    private val clock = FixedClock()

    @After fun tearDown() = server.close()

    private fun fetcher(directory: File = folder.newFolder()): Pair<RemoteConfigFetcher, RemoteConfigStore> {
        val store = RemoteConfigStore(directory, keys.documents(), clock)
        return RemoteConfigFetcher(
            calls = OkHttpClient(),
            store = store,
            clock = clock,
            io = Dispatchers.Unconfined,
            url = server.url("/v1/parsers.json").toString(),
        ) to store
    }

    @Test
    fun `a valid document is downloaded and cached`() = runTest {
        server.enqueue(MockResponse(body = keys.envelope("""{"schemaVersion":1,"stores":{}}""").decodeToString()))
        val directory = folder.newFolder()
        val (fetcher, _) = fetcher(directory)

        val attempt = fetcher.refresh()

        assertThat(attempt).isInstanceOf(FetchAttempt.Accepted::class.java)
        assertThat(File(directory, RemoteConfigStore.FILE_NAME).exists()).isTrue()
    }

    @Test
    fun `a 404 is unreachable, not a refused document`() = runTest {
        server.enqueue(MockResponse(code = 404))
        val (fetcher, store) = fetcher()

        assertThat(fetcher.refresh()).isEqualTo(FetchAttempt.Unreachable(clock.now(), httpCode = 404))
        assertThat(store.status.value.active).isEqualTo(ActiveConfig.CompiledDefaults)
    }

    @Test
    fun `a 304 says there is nothing new`() = runTest {
        server.enqueue(MockResponse(code = 304))
        val (fetcher, _) = fetcher()

        assertThat(fetcher.refresh()).isEqualTo(FetchAttempt.NotModified(clock.now()))
    }

    @Test
    fun `an HTML error page in place of the document is refused`() = runTest {
        server.enqueue(MockResponse(body = "<html><body>Forbidden</body></html>"))
        val (fetcher, _) = fetcher()

        assertThat(fetcher.refresh())
            .isEqualTo(FetchAttempt.Rejected(clock.now(), ConfigRejection.MALFORMED_ENVELOPE))
    }

    /**
     * The cap on the body.
     *
     * The host is no more under our control than the network carrying us to it: without a limit, a
     * stream that does not end would fill the process's memory. By raising
     * [RemoteConfigFetcher.MAX_DOCUMENT_BYTES] beyond the body's size, this test turns green for the
     * wrong reason — and that is why the body here is built **from the cap**, not from a number
     * written by hand.
     */
    @Test
    fun `a body larger than the cap is not even read to the end`() = runTest {
        server.enqueue(MockResponse(body = "x".repeat(RemoteConfigFetcher.MAX_DOCUMENT_BYTES + 1)))
        val directory = folder.newFolder()
        val (fetcher, _) = fetcher(directory)

        assertThat(fetcher.refresh()).isEqualTo(FetchAttempt.Unreachable(clock.now(), httpCode = 200))
        assertThat(File(directory, RemoteConfigStore.FILE_NAME).exists()).isFalse()
    }

    @Test
    fun `a host that does not answer is not a refused document`() = runTest {
        server.close()
        val (fetcher, _) = fetcher()

        assertThat(fetcher.refresh()).isEqualTo(FetchAttempt.Unreachable(clock.now(), httpCode = null))
    }

    @Test
    fun `a recent cache starts no request`() = runTest {
        val directory = folder.newFolder()
        File(directory, RemoteConfigStore.FILE_NAME)
            .also { it.writeBytes(keys.envelope("""{"schemaVersion":1,"stores":{}}""")) }
            .setLastModified(clock.now().toEpochMilliseconds())
        val (fetcher, _) = fetcher(directory)

        assertThat(fetcher.refreshIfStale()).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an old cache starts the request`() = runTest {
        val directory = folder.newFolder()
        File(directory, RemoteConfigStore.FILE_NAME)
            .also { it.writeBytes(keys.envelope("""{"schemaVersion":1,"stores":{}}""")) }
            .setLastModified(clock.now().toEpochMilliseconds())
        val (fetcher, _) = fetcher(directory)
        clock.advanceTo(clock.now() + RemoteConfigFetcher.REFRESH_INTERVAL + 1.hours)
        server.enqueue(MockResponse(body = keys.envelope("""{"schemaVersion":1,"stores":{}}""").decodeToString()))

        assertThat(fetcher.refreshIfStale()).isInstanceOf(FetchAttempt.Accepted::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `with no cache we ask immediately`() = runTest {
        server.enqueue(MockResponse(body = keys.envelope("""{"schemaVersion":1,"stores":{}}""").decodeToString()))
        val (fetcher, _) = fetcher()

        assertThat(fetcher.refreshIfStale()).isInstanceOf(FetchAttempt.Accepted::class.java)
    }
}
