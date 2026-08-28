package com.multistore.core.challenge

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real engine: an off-screen WebView loading the page and letting its JS run.
 *
 * It is the part no test can touch — on the JVM it does not exist, and Robolectric's shadow runs no
 * JavaScript — so in here there is **only** what cannot be moved elsewhere. The protocol lives in
 * [WebViewSilentResolver] and is tested with a fake engine; this class opens, waits, reads the
 * cookies and closes.
 *
 * ### Why nothing here is simulated
 *
 * JavaScript on, `DOM storage` on, cookies accepted: it is a browser loading a page. There is no
 * forged TLS fingerprint, no guessed token, no third-party service. What the site asks — "run this
 * script and prove you are a browser engine" — is **really done**, and it is exactly the permitted
 * half of the line this project draws.
 *
 * ### When we stop waiting, and why there are two signals
 *
 * The first is obvious: **the transit permit has arrived**. The second was imposed by the measurement
 * of 25/08/2026 on the emulator, and it is the most frequent case: **the browser was not challenged
 * at all.** On `liteapks.com` the WebView receives `200` while the same URL asked by OkHttp in
 * HTTP/1.1 receives `403 cf-mitigated: challenge`. Waiting only for the first signal, the probe sat
 * still for the whole timeout and answered `TimedOut` — thirty seconds to discover there was nothing
 * to discover.
 *
 * The second signal is **not** "the page has finished loading": the challenge page finishes too, and
 * then loads another. It is "the page has finished **and** is not a challenge page", and the document
 * is asked: `window._cf_chl_opt` is the object Cloudflare's script defines, and it does not depend on
 * the title's language.
 *
 * ### Three details that are not details
 *
 * - **The User-Agent is the store's, not the WebView's.** A `cf_clearance` is tied to the UA that
 *   obtained it; presenting it with another makes it a valid and useless cookie, and the symptom —
 *   the WebView passes, the retry does not — would send one looking for the fault in the wrong place.
 * - **The WebView is given a size.** It is attached to no window, so it is born 0 x 0; part of the
 *   anti-bot JavaScript looks at the window's dimensions, and a window zero wide is a more suspicious
 *   signal than any User-Agent.
 * - **It is always destroyed.** A WebView has a thread of its own and goes on running the page's JS
 *   for as long as it lives: without `destroy()`, every challenge would leave behind an engine
 *   running for the process's lifetime.
 */
class AndroidWebViewChallengeEngine(
    private val context: Context,
    /**
     * The names of the cookies that count as a transit permit.
     *
     * Just one, and it is measured: `cf_clearance` is what Cloudflare's Managed Challenge issues, the
     * only automatic challenge observed across the nine stores. It is a parameter and not a constant
     * because the day a second one appeared the right answer would be a line of configuration, not a
     * branch in the code — but while there is one, declaring one is also the way of saying we know
     * nothing about the others.
     */
    private val clearanceCookies: Set<String> = setOf(CLOUDFLARE_CLEARANCE),
) : SilentChallengeEngine {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun solve(request: SilentChallengeRequest): SilentChallengeResult =
        withContext(Dispatchers.Main.immediate) {
            val cookieManager = runCatching { CookieManager.getInstance() }.getOrElse { error ->
                return@withContext SilentChallengeResult.Unavailable(error.reason())
            }
            val webView = runCatching { WebView(context) }.getOrElse { error ->
                return@withContext SilentChallengeResult.Unavailable(error.reason())
            }

            var loadsFinished = 0
            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    // The challenge writes to `localStorage` before letting anyone through.
                    domStorageEnabled = true
                    userAgentString = request.userAgent
                }
                // Off screen but not zero-sized: see the class's KDoc.
                webView.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                // An explicit `WebViewClient` serves to keep the navigation **inside** the WebView:
                // without it, the redirect closing the challenge can end up in an Intent towards the
                // system browser, where the cookie is of no use to us.
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loadsFinished++
                    }
                }
                webView.loadUrl(request.url)

                var seen = 0
                val outcome = withTimeoutOrNull(request.timeout) {
                    while (true) {
                        delay(POLL_INTERVAL)
                        val header = cookieManager.getCookie(request.url)
                        if (header != null && header.grantsTransit()) {
                            return@withTimeoutOrNull SilentChallengeResult.Solved(
                                finalUrl = webView.url ?: request.url,
                                cookieHeader = header,
                            )
                        }
                        // The document is inspected only **after** a new load: asking it on every
                        // round would be an `evaluateJavascript` every 250 ms on a page still running
                        // the challenge.
                        if (loadsFinished > seen) {
                            seen = loadsFinished
                            if (!webView.looksLikeAChallenge()) {
                                return@withTimeoutOrNull SilentChallengeResult.NoChallenge(
                                    finalUrl = webView.url ?: request.url,
                                )
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }

                outcome ?: SilentChallengeResult.TimedOut
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }

    /**
     * `true` if the loaded document is a challenge page.
     *
     * `window._cf_chl_opt` is inspected, being the object Cloudflare's script defines: the title
     * ("Just a moment…") is translated and would give a different answer depending on the device's
     * language. In case of doubt the answer is `true`, i.e. "keep waiting": closing too early would
     * lose a challenge that was about to succeed.
     */
    private suspend fun WebView.looksLikeAChallenge(): Boolean =
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(CHALLENGE_PROBE) { value ->
                continuation.resume(value != FALSE_LITERAL)
            }
        }

    private fun String.grantsTransit(): Boolean =
        clearanceCookies.any { name -> split(PAIR_SEPARATOR).any { it.trim().startsWith("$name=") } }

    /** The message that ends up in diagnostics, without the stack. */
    private fun Throwable.reason(): String = this::class.java.simpleName

    private companion object {
        const val CLOUDFLARE_CLEARANCE = "cf_clearance"
        const val PAIR_SEPARATOR = ";"

        /** `evaluateJavascript` hands back JSON: a boolean arrives as `true` / `false`. */
        const val CHALLENGE_PROBE = "(typeof window._cf_chl_opt !== 'undefined')"
        const val FALSE_LITERAL = "false"

        /** A plausible phone, in logical pixels. See the third detail in the KDoc. */
        const val VIEWPORT_WIDTH = 412
        const val VIEWPORT_HEIGHT = 915

        val POLL_INTERVAL: Duration = 250.milliseconds
    }
}
