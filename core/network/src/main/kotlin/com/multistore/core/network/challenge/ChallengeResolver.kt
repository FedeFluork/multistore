package com.multistore.core.network.challenge

import com.multistore.core.model.BlockKind
import com.multistore.core.network.http.StoreHttpClient
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * One rung of the escalation ladder.
 *
 * The rule separating legitimate rungs from what this project does not do: **actually executing
 * what the site asks is allowed, pretending to have done so is not.** Forcing HTTP/1.1 is a real
 * protocol negotiation; using Cronet means really using Chromium's stack; opening a WebView means
 * really executing the challenge's JavaScript. Forging a TLS fingerprint to impersonate a browser
 * we are not running, no.
 *
 * Rungs 0 and 1 live here because they are pure network negotiation and need no Android. Rung 3
 * is in `:core:challenge` and rung 4 in `:feature:webviewdownload`; rung 2 (Cronet) does not
 * exist, because no measurement shows a store that passes with it and fails with OkHttp. None of
 * the three required touching an adapter: `ChallengeResolver` is an open interface and extra
 * rungs reach `StoreHttpClients` via Hilt `@IntoSet`.
 */
interface ChallengeResolver {

    /** Position on the ladder. Lower = cheaper, tried first. */
    val tier: Int

    /** Name for the health log and the diagnostic export. */
    val name: String

    /**
     * Attempts the request.
     *
     * Returns `null` if this rung has nothing to offer for this case (for instance, rung 1 makes
     * no sense if the request was already HTTP/1.1).
     */
    suspend fun attempt(request: Request, client: StoreHttpClient): Response?
}

/** Rung 0: the request as it is, with the store's User-Agent. */
class PlainResolver : ChallengeResolver {
    override val tier: Int = 0
    override val name: String = "plain"

    override suspend fun attempt(request: Request, client: StoreHttpClient): Response =
        client.execute(request)
}

/**
 * Rung 1: retry forcing HTTP/1.1.
 *
 * Practically free — there is no pretence, only a shorter protocol list in the ALPN handshake.
 * Some WAFs treat HTTP/2 differently, and sometimes more harshly, than old HTTP/1.1.
 *
 * ### Its measured balance, which is currently negative
 *
 * The only justification ever published for it was "unblocks `apkmody.com` 5 times out of 5".
 * That measurement was taken with **`curl`**, on a domain now in the blocklist because it
 * redirects elsewhere.
 *
 * Against it, two measurements taken with the real client:
 *
 * - **apkmirror:** with OkHttp it is HTTP/2 that passes and HTTP/1.1 that gets challenged — the
 *   exact opposite of what curl shows;
 * - **liteapks (25/08/2026):** the detail page answers 200 over HTTP/2 and **403
 *   `cf-mitigated: challenge`** when this rung forces HTTP/1.1. Here the rung does not resolve a
 *   challenge: it **provokes** one.
 *
 * It stays because it costs one request on an already-failed path and might help on networks we
 * have not observed. But whoever proposes removing it will be right, and this comment is what
 * they need to prove it.
 */
class ProtocolFallbackResolver : ChallengeResolver {
    override val tier: Int = 1
    override val name: String = "http11"

    override suspend fun attempt(request: Request, client: StoreHttpClient): Response =
        client.derive { protocols(listOf(Protocol.HTTP_1_1)) }.execute(request)
}

/** The outcome of a climb up the ladder. */
sealed interface ChallengeOutcome {

    /** Got through. [tier] says at which rung: it is recorded in `health_events`. */
    data class Passed(val response: Response, val tier: Int) : ChallengeOutcome

    /** No available rung worked. */
    data class Blocked(val kind: BlockKind, val lastTier: Int, val httpCode: Int?) : ChallengeOutcome

    /** A network failure, not a block. */
    data class Failed(val cause: IOException, val lastTier: Int) : ChallengeOutcome
}

/**
 * How high the climb may go is decided by [com.multistore.core.model.ChallengeStrategy], which
 * lives in `:core:model`.
 *
 * It moved there when it also became the type of a setting: `NetworkSettings` is in
 * `:core:model`, and an enum duplicated across the two modules would have meant a conversion in
 * between — a point where the value read from the DataStore and the one applied to the ladder can
 * diverge with nothing saying so.
 */
