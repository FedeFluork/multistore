package com.multistore.store.common.html

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.BlockKind
import com.multistore.core.model.StoreId
import com.multistore.core.network.challenge.ChallengeEscalator
import com.multistore.core.network.challenge.ChallengeResolver
import com.multistore.core.network.challenge.ChallengeTierRecorder
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The ladder rung ends up in diagnostics — and **only** when there is something to say.
 *
 * The rung reached is recorded for diagnosis. For a while the datum existed and had no channel.
 *
 * The two tests together are the defence: the first proves the note arrives, the second that it
 * does **not** for the ordinary case. Without the second, the obvious solution — always record —
 * would pass, and would fill the log with rows saying nothing happened, making useless exactly the
 * table it was meant to make useful.
 */
@DisplayName("PageFetcher — the escalation rung in diagnostics")
class PageFetcherTierTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: StoreHttpClients
    private val recorded = mutableListOf<Pair<StoreId, Int>>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val work = Files.createTempDirectory("page-fetcher").toFile()
        clients = StoreHttpClients(
            environment = NetworkEnvironment(cacheDirectory = File(work, "cache")),
            tierRecorder = ChallengeTierRecorder { storeId, tier -> recorded += storeId to tier },
        )
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    private fun client(): StoreHttpClient =
        clients.forStore(StoreId.APKMIRROR, StoreNetworkProfile(userAgent = "test-agent"))

    @Test
    @DisplayName("reaching rung 1 leaves a trace with the store and the rung")
    fun escalationIsRecorded() = runTest {
        // Rung 0 returns a challenge, rung 1 the page: the situation the ladder exists for, and
        // the one whoever reads the diagnostics has to be able to see.
        server.enqueue(MockResponse(code = 403, body = "just a moment"))
        server.enqueue(MockResponse(code = 200, body = "<html><body>ok</body></html>"))
        val fetcher = PageFetcher(client(), twoStepLadder())

        val result = fetcher.get(server.url("/app").toString())

        assertThat(result).isInstanceOf(com.multistore.store.api.StoreResult.Success::class.java)
        assertThat(recorded).containsExactly(StoreId.APKMIRROR to 1)
    }

    @Test
    @DisplayName("rung 0 leaves no trace: it is the ordinary request, not news")
    fun theOrdinaryPathIsNotRecorded() = runTest {
        server.enqueue(MockResponse(code = 200, body = "<html><body>ok</body></html>"))
        val fetcher = PageFetcher(client(), twoStepLadder())

        fetcher.get(server.url("/app").toString())

        // Nine stores at dozens of requests per search: recording the ordinary case too would
        // mean one diagnostic row per page read, and a table in which the interesting event can no
        // longer be found.
        assertThat(recorded).isEmpty()
    }

    @Test
    @DisplayName("an adapter passing no ladder inherits the client's")
    fun theLadderComesFromTheClient() = runTest {
        // This is what let the silent WebView rung be added **without touching any of the
        // adapters**: each builds its own `PageFetcher` with only the client, so with a
        // network-only default the WebView would exist and nobody would walk it — a branch no
        // configuration reaches.
        val work = Files.createTempDirectory("inherited-ladder").toFile()
        val android = mutableListOf<String>()
        val laddered = StoreHttpClients(
            environment = NetworkEnvironment(cacheDirectory = File(work, "cache")),
            tierRecorder = ChallengeTierRecorder { storeId, tier -> recorded += storeId to tier },
            androidResolvers = listOf(RecordingStep(3, android)),
        )
        try {
            server.enqueue(MockResponse(code = 403, body = "cf-browser-verification"))
            server.enqueue(MockResponse(code = 403, body = "cf-browser-verification"))
            server.enqueue(MockResponse(code = 200, body = "<html><body>ok</body></html>"))
            val http = laddered.forStore(StoreId.LITEAPKS, StoreNetworkProfile(userAgent = "ua"))

            val result = PageFetcher(http).get(server.url("/spotify-2.html").toString())

            assertThat(android).containsExactly("provato")
            assertThat(result).isInstanceOf(com.multistore.store.api.StoreResult.Success::class.java)
            assertThat(recorded).containsExactly(StoreId.LITEAPKS to 3)
        } finally {
            laddered.shutdown()
        }
    }

    /** A fake Android rung: it records having been tried and reissues the request. */
    private class RecordingStep(
        override val tier: Int,
        private val attempts: MutableList<String>,
    ) : ChallengeResolver {
        override val name: String = "recording-$tier"

        override suspend fun attempt(request: Request, client: StoreHttpClient): Response {
            attempts += "provato"
            return client.execute(request)
        }
    }

    /** Two real rungs, both talking to the fake server. */
    private fun twoStepLadder() = ChallengeEscalator(
        resolvers = listOf(PlainStep(0), PlainStep(1)),
        detector = { response -> if (response.code == 403) BlockKind.CHALLENGE else null },
    )

    private class PlainStep(override val tier: Int) : ChallengeResolver {
        override val name: String = "step-$tier"

        override suspend fun attempt(request: Request, client: StoreHttpClient): Response =
            client.execute(request)
    }
}
