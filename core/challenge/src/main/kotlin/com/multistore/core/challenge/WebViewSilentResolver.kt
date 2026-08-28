package com.multistore.core.challenge

import com.multistore.core.network.challenge.ChallengeResolver
import com.multistore.core.network.cookie.ClearanceCookieJar
import com.multistore.core.network.http.StoreHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response

/**
 * The silent WebView rung: the WebView that **runs** the challenge, and the cookie coming out of it.
 *
 * The rung in one line: a headless WebView that **runs** the JS challenge and transfers
 * `cf_clearance` into the `CookieJar`. Both halves are necessary — a challenge solved and not
 * transferred is of no use, because the real request is still made by OkHttp.
 *
 * ### What is measured, and what is not
 *
 * The challenge exists and is of the right kind: `liteapks.com/spotify-2.html` answers **403
 * `cf-mitigated: challenge`** and the page declares `cType: 'managed'`, with `__cf_chl` and no
 * Turnstile widget. A Managed Challenge is automatic — no tap, no captcha — and it is exactly what a
 * WebView passes by itself.
 *
 * **But from this network none of the clients we ship receives it.** On 25/08/2026, from an Italian
 * consumer IP: `curl` in HTTP/2 gets 403, **OkHttp with the Chrome mobile UA gets 200 with the whole
 * listing**, and the WebView is not challenged at all. So the rung is proven in its **mechanics** —
 * it opens, runs, reads, transfers, retries — and **not** against a real Managed Challenge. It serves
 * whoever sits in a worse reputation band, and costs zero requests while nobody is challenged.
 *
 * ### The protocol, which is the only testable part
 *
 * 1. open nothing if somebody else has just opened — see the counter;
 * 2. run the challenge **with the client's User-Agent**, not the WebView's;
 * 3. transfer the cookies obtained into the shared jar;
 * 4. if none arrived, **return `null`**: retrying a request identical to the one that has just taken
 *    a 403 is another refused request, not an extra attempt;
 * 5. otherwise retry, and let the detector say whether it passed.
 *
 * The real engine — [AndroidWebViewChallengeEngine] — sits behind [SilentChallengeEngine] precisely
 * so that these five points can be tested without Chromium.
 */
class WebViewSilentResolver(
    private val engine: SilentChallengeEngine,
    private val cookies: ClearanceCookieJar,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : ChallengeResolver {

    override val tier: Int = TIER

    override val name: String = "webview-silent"

    /**
     * One WebView at a time, for the whole app.
     *
     * It is not generic caution: with nine stores queried in parallel, a search challenging the same
     * host from two different requests would open two browser engines together to obtain **the same**
     * cookie. The second is not merely wasted — it is also a second execution of the challenge, i.e.
     * exactly the kind of behaviour that to a site behind Cloudflare looks like a bot.
     */
    private val gate = Mutex()

    override suspend fun attempt(request: Request, client: StoreHttpClient): Response? {
        val target = request.challengeTarget()
        val before = cookies.harvestCount(target.host)

        gate.withLock {
            // Whoever waited in the queue may find the work already done: in that case nothing is
            // opened and we go straight to the retry, which is the point of the whole wait.
            if (cookies.harvestCount(target.host) == before) {
                val result = engine.solve(
                    SilentChallengeRequest(
                        url = target.toString(),
                        userAgent = client.userAgent,
                        timeout = timeout,
                    ),
                )
                val harvested = when (result) {
                    is SilentChallengeResult.Solved ->
                        cookies.acceptFromWebView(target, result.cookieHeader)

                    // `NoChallenge` is not a failure and is the most frequent case: the browser loaded
                    // the page without being challenged, so it obtained nothing OkHttp does not
                    // already have. Retrying would mean remaking the request that has just taken a
                    // 403 — see point 4 above.
                    is SilentChallengeResult.NoChallenge,
                    SilentChallengeResult.TimedOut,
                    is SilentChallengeResult.Unavailable,
                    -> 0
                }
                if (harvested == 0) return null
            }
        }

        return client.execute(request)
    }

    /**
     * Which URL to run the challenge on.
     *
     * For a `GET` it is the page itself: it is what a browser would do, and it is also the page
     * Cloudflare decided to challenge on.
     *
     * For **everything else** it is the host's root, and the reason is concrete. A WebView can only
     * do `GET`: pointing it at a `HEAD`'s URL would mean **downloading** the object that `HEAD` only
     * meant to query — on an1 that is the whole APK, tens of megabytes, inside a browser engine and
     * at the worst moment. And pointing it at a `POST`'s URL would `GET` an endpoint that only answers
     * `POST`. The transit permit we need belongs to the **host** anyway, not to the single resource:
     * taking it from the root is identical and moves nothing that should not be moved.
     */
    private fun Request.challengeTarget() =
        if (method.equals(GET, ignoreCase = true)) url else url.resolve(ORIGIN_ROOT) ?: url

    private companion object {
        const val TIER = 3
        const val GET = "GET"
        const val ORIGIN_ROOT = "/"

        /**
         * Twenty seconds.
         *
         * A Managed Challenge resolves in two or three; the rest is margin for a slow network and for
         * the engine's first start, which on a cold device is not instantaneous. Higher would be
         * pointless: beyond that threshold, almost always, it is not an automatic challenge — it is a
         * captcha waiting for a person, and this rung does not solve those by definition.
         */
        val DEFAULT_TIMEOUT: Duration = 20.seconds
    }
}
