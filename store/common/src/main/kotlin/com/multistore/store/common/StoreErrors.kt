package com.multistore.store.common

import com.multistore.core.model.BlockKind
import com.multistore.core.network.challenge.ChallengeOutcome
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.Response

/**
 * The translation between "what happened on the network" and "what I tell the rest of the app".
 *
 * It lives here and not in `:core:network` because `StoreError` is in `:store:api`, which a
 * foundational module cannot see; and not in each adapter because nine different translations of
 * the same 429 would be nine different ways of getting it wrong.
 */
object StoreErrors {

    /** The network error, split by cause: the circuit breaker reacts differently to each. */
    fun fromIoException(e: IOException, httpCode: Int? = null): StoreError = when (e) {
        is UnknownHostException, is SocketTimeoutException, is SSLException ->
            StoreError.Network(e, httpCode)
        else -> StoreError.Network(e, httpCode)
    }

    /**
     * The error corresponding to an unsuccessful HTTP response.
     *
     * `Retry-After` is interpreted in both forms the standard allows: seconds, or an HTTP date.
     * Ignoring either means treating a server that asked to wait an hour as "retry immediately".
     */
    fun fromResponse(response: Response, now: Instant = Clock.System.now()): StoreError = when (response.code) {
        HTTP_NOT_FOUND, HTTP_GONE -> StoreError.NotFound
        HTTP_TOO_MANY_REQUESTS -> StoreError.RateLimited(response.retryAfter(now))
        HTTP_FORBIDDEN -> StoreError.Blocked(BlockKind.FORBIDDEN)
        HTTP_UNAVAILABLE_LEGAL -> StoreError.Blocked(BlockKind.GEO)
        else -> StoreError.Network(cause = null, httpCode = response.code)
    }

    fun fromChallenge(outcome: ChallengeOutcome): StoreError = when (outcome) {
        is ChallengeOutcome.Passed -> StoreError.Unexpected(null)
        is ChallengeOutcome.Blocked ->
            if (outcome.httpCode == HTTP_TOO_MANY_REQUESTS) {
                StoreError.RateLimited(null)
            } else {
                StoreError.Blocked(outcome.kind)
            }
        is ChallengeOutcome.Failed -> StoreError.Network(outcome.cause, null)
    }

    /**
     * The hash of a snippet, for a parse failure.
     *
     * It exists to recognise that two failures concern the same page without keeping its content:
     * there is no automatic telemetry here, and a page fragment in an exportable log can contain
     * user data. A hash cannot.
     */
    fun snippetHash(snippet: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(snippet.trim().toByteArray())
        return digest.take(SNIPPET_HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    fun parseFailure(selector: String, snippet: String): StoreError.ParseFailure =
        StoreError.ParseFailure(selector = selector, snippetHash = snippetHash(snippet))

    /**
     * `Retry-After` in the two forms the standard allows: seconds, or an HTTP date.
     *
     * Interpreting only one means treating a server that asked to wait an hour as "retry
     * immediately" — and reopening the tap towards a store that is already limiting us. [now] is
     * a parameter and not the system clock because the date form can only be tested by being able
     * to decide what time it is.
     */
    private fun Response.retryAfter(now: Instant): Duration? {
        val raw = header("Retry-After")?.trim() ?: return null
        raw.toLongOrNull()?.let { return it.seconds }
        val date = headers.getInstant("Retry-After") ?: return null
        val delta = date.toEpochMilli() - now.toEpochMilliseconds()
        return if (delta > 0) (delta / MILLIS_PER_SECOND).seconds else Duration.ZERO
    }

    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_GONE = 410
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_UNAVAILABLE_LEGAL = 451
    private const val SNIPPET_HASH_BYTES = 8
    private const val MILLIS_PER_SECOND = 1000L
}

/**
 * The shell that makes the promise "no exception leaves a `StoreAdapter` method" true.
 *
 * Writing it in every method of every adapter would be nine chances to forget.
 * `CancellationException` is **rethrown**: it is the mechanism by which a cancelled search stops
 * the other eight stores, and turning it into an error would switch that off.
 */
suspend inline fun <T> storeCall(crossinline block: suspend () -> StoreResult<T>): StoreResult<T> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        StoreResult.Failure(StoreErrors.fromIoException(e))
    } catch (e: Throwable) {
        StoreResult.Failure(StoreError.Unexpected(e))
    }
