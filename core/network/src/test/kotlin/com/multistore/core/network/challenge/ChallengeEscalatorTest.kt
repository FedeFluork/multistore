package com.multistore.core.network.challenge

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.BlockKind
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.model.StoreId
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
 * The ladder's ceiling, and who decides it.
 *
 * Two properties that look obvious and are not, because getting them wrong produces no visible
 * error: a ladder that ignores the setting opens WebViews for someone who asked for none, and a
 * ladder that reads the setting **once** turns "network only" into an entry that appears to do
 * nothing until the next restart.
 */
@DisplayName("ChallengeEscalator — how far it climbs, and who decides")
class ChallengeEscalatorTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: StoreHttpClients

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val work = Files.createTempDirectory("escalator").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(work, "cache")))
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
    }

    private fun client(): StoreHttpClient =
        clients.forStore(StoreId.LITEAPKS, StoreNetworkProfile(userAgent = "test-agent"))

    /** A rung that talks to nobody: all that matters is that it was tried. */
    private class Recording(override val tier: Int, private val attempts: MutableList<Int>) :
        ChallengeResolver {
        override val name: String = "recording-$tier"

        override suspend fun attempt(request: Request, client: StoreHttpClient): Response? {
            attempts += tier
            return null
        }
    }

    @Test
    @DisplayName("CONSERVATIVE does not reach rung 3")
    fun conservativeStopsBeforeTheWebView() = runTest {
        val attempted = mutableListOf<Int>()
        val escalator = ChallengeEscalator(
            resolvers = listOf(Recording(0, attempted), Recording(1, attempted), Recording(3, attempted)),
            strategySource = { ChallengeStrategy.CONSERVATIVE },
        )

        escalator.execute(Request.Builder().url(server.url("/app")).build(), client())

        assertThat(attempted).containsExactly(0, 1).inOrder()
    }

    @Test
    @DisplayName("BALANCED does")
    fun balancedReachesTheWebView() = runTest {
        val attempted = mutableListOf<Int>()
        val escalator = ChallengeEscalator(
            resolvers = listOf(Recording(0, attempted), Recording(1, attempted), Recording(3, attempted)),
            strategySource = { ChallengeStrategy.BALANCED },
        )

        escalator.execute(Request.Builder().url(server.url("/app")).build(), client())

        assertThat(attempted).containsExactly(0, 1, 3).inOrder()
    }

    @Test
    @DisplayName("the setting is re-read on every request, not at startup")
    fun theStrategyIsReadEveryTime() = runTest {
        val attempted = mutableListOf<Int>()
        var strategy = ChallengeStrategy.CONSERVATIVE
        val escalator = ChallengeEscalator(
            resolvers = listOf(Recording(0, attempted), Recording(3, attempted)),
            strategySource = { strategy },
        )
        val request = Request.Builder().url(server.url("/app")).build()

        escalator.execute(request, client())
        assertThat(attempted).containsExactly(0)

        // The user changes their mind in Settings while the app runs. With a value captured at
        // construction, this second request would still stop at rung 0 — and the entry would
        // appear to do nothing until a restart.
        attempted.clear()
        strategy = ChallengeStrategy.BALANCED
        escalator.execute(request, client())

        assertThat(attempted).containsExactly(0, 3).inOrder()
    }

    @Test
    @DisplayName("a captcha stops the climb: no automatic rung solves it")
    fun aCaptchaStopsTheClimb() = runTest {
        val attempted = mutableListOf<Int>()
        server.enqueue(MockResponse(code = 403, body = "<div class=\"g-recaptcha\"></div>"))
        val escalator = ChallengeEscalator(
            resolvers = listOf(RealStep(0), Recording(3, attempted)),
            strategySource = { ChallengeStrategy.BALANCED },
        )

        val outcome = escalator.execute(Request.Builder().url(server.url("/app")).build(), client())

        // Climbing would burn time and make the site suspicious over something that, by
        // definition, is waiting for a person.
        assertThat(attempted).isEmpty()
        assertThat(outcome).isInstanceOf(ChallengeOutcome.Blocked::class.java)
        assertThat((outcome as ChallengeOutcome.Blocked).kind).isEqualTo(BlockKind.CAPTCHA)
    }

    private class RealStep(override val tier: Int) : ChallengeResolver {
        override val name: String = "real-$tier"

        override suspend fun attempt(request: Request, client: StoreHttpClient): Response =
            client.execute(request)
    }
}
