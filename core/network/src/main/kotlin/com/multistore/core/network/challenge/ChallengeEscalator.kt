package com.multistore.core.network.challenge

import com.multistore.core.model.BlockKind
import com.multistore.core.model.ChallengeStrategy
import com.multistore.core.network.http.StoreHttpClient
import java.io.IOException
import okhttp3.Request
import okhttp3.Response

/**
 * How far the app may climb right now.
 *
 * A function and not a value, because the user changes the setting while the app is running: a
 * value read once at startup would make "network only" a setting that appears to do nothing until
 * the next restart.
 *
 * The default is the compiled one, so a network test with no DataStore around does not have to
 * invent one.
 */
fun interface ChallengeStrategySource {
    fun current(): ChallengeStrategy

    companion object {
        val DEFAULT: ChallengeStrategySource = ChallengeStrategySource { ChallengeStrategy.DEFAULT }
    }
}

/**
 * Climbs the ladder: tries the rungs in order and stops at the first that gets through.
 *
 * An adapter never implements challenge handling itself: it asks `:core:network`, which applies
 * the rungs in order and stops at the first that succeeds. The rung reached is in
 * [ChallengeOutcome.Passed.tier], and the caller records it in `health_events` for diagnosis.
 *
 * The resolvers come from outside: the list grows with `:core:challenge`'s rung 3 **without this
 * class changing** and without any adapter knowing. That is the one property that makes the
 * ladder an open interface instead of a `when`.
 */
class ChallengeEscalator(
    resolvers: List<ChallengeResolver>,
    private val strategySource: ChallengeStrategySource = ChallengeStrategySource.DEFAULT,
    private val detector: (Response) -> BlockKind? = ChallengeDetector::classify,
) {
    private val ladder = resolvers.sortedBy { it.tier }

    /**
     * @param strategy to force a ceiling other than the user's. The default reads it from
     * [strategySource] **on every call**: see that interface's KDoc.
     */
    suspend fun execute(
        request: Request,
        client: StoreHttpClient,
        strategy: ChallengeStrategy = strategySource.current(),
    ): ChallengeOutcome {
        var lastTier = -1
        var lastBlock: BlockKind? = null
        var lastCode: Int? = null
        var lastFailure: IOException? = null

        for (resolver in ladder) {
            if (resolver.tier > strategy.maxTier) break
            lastTier = resolver.tier
            val response = try {
                resolver.attempt(request, client) ?: continue
            } catch (e: IOException) {
                lastFailure = e
                continue
            }

            val block = detector(response)
            if (block == null) return ChallengeOutcome.Passed(response, resolver.tier)

            lastBlock = block
            lastCode = response.code
            response.closeQuietly()

            // No automatic rung solves a captcha meant for a human: climbing would only burn
            // time and make the site suspicious. We leave at once, and rung 4 — with the user's
            // tap — will be invoked by whoever can.
            if (block == BlockKind.CAPTCHA || block == BlockKind.GEO) break
        }

        return when {
            lastBlock != null -> ChallengeOutcome.Blocked(lastBlock, lastTier, lastCode)
            lastFailure != null -> ChallengeOutcome.Failed(lastFailure, lastTier)
            else -> ChallengeOutcome.Blocked(BlockKind.FORBIDDEN, lastTier, lastCode)
        }
    }

    private fun Response.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        /** The rungs available without Android. */
        fun networkOnly(
            strategySource: ChallengeStrategySource = ChallengeStrategySource.DEFAULT,
        ): ChallengeEscalator = ChallengeEscalator(
            resolvers = listOf(PlainResolver(), ProtocolFallbackResolver()),
            strategySource = strategySource,
        )

        /**
         * The two network rungs plus those only Android can offer.
         *
         * [android] comes from `:core:challenge` and is empty in a JVM test: the same factory
         * therefore builds the full ladder on device and the short one on the bench, without the
         * caller needing to know which it is getting.
         */
        fun withAndroidRungs(
            android: List<ChallengeResolver>,
            strategySource: ChallengeStrategySource = ChallengeStrategySource.DEFAULT,
        ): ChallengeEscalator = ChallengeEscalator(
            resolvers = listOf(PlainResolver(), ProtocolFallbackResolver()) + android,
            strategySource = strategySource,
        )
    }
}
