package com.multistore.core.challenge

import kotlin.time.Duration

/**
 * Whoever really runs a page's challenge, without asking the user anything.
 *
 * It is an interface for the same reason `PrivilegedShell` is in `:core:installer`: **the part that
 * can be tested and the part that cannot are two.** A WebView does not exist on a JVM and does
 * nothing under Robolectric — the shadow draws a rectangle and runs no JavaScript — so a resolver
 * building one itself would be entirely unverifiable. Split this way, the only blind piece is
 * [AndroidWebViewChallengeEngine], while the **protocol** — who opens the WebView, when, with which
 * User-Agent, what is done with the cookie coming out and what is done when none does — is tested on
 * the JVM with a fake engine.
 *
 * ### What this interface promises, and what it does not
 *
 * It promises to **run** what the site asks: load the page in a real browser engine and let its
 * JavaScript run to the end. It is the permitted half of the line this project draws — really doing
 * what the site asks is legitimate; pretending to have done it is not.
 *
 * It does not promise to solve a captcha. A Cloudflare Managed Challenge is automatic and a WebView
 * passes it by itself; an interactive Turnstile or a reCAPTCHA wait for a human, and for those the
 * answer remains the user-assisted rung, with the page visible and a person's tap.
 */
interface SilentChallengeEngine {

    suspend fun solve(request: SilentChallengeRequest): SilentChallengeResult
}

/**
 * @param userAgent **must** be the one the request will then be retried with. A `cf_clearance` is
 * tied to the User-Agent that obtained it: taken with the WebView's and presented with the store's,
 * it comes back valid and useless.
 */
data class SilentChallengeRequest(
    val url: String,
    val userAgent: String,
    val timeout: Duration,
)

/** How it went. */
sealed interface SilentChallengeResult {

    /**
     * The challenge passed: there is a transit permit to transfer.
     *
     * [cookieHeader] is the `name=value; name=value` line the engine collected for that page — the
     * same form `CookieManager.getCookie` returns, with no attributes.
     */
    data class Solved(val finalUrl: String, val cookieHeader: String?) : SilentChallengeResult

    /**
     * The page loaded **without** the browser being challenged.
     *
     * It looks like a detail and is instead the most common case on the network the app really runs
     * on, and it was **measured** on 25/08/2026 on the emulator: on `liteapks.com` the WebView's
     * Chromium engine receives `200`, while the identical URL asked by OkHttp in HTTP/1.1 receives
     * `403 cf-mitigated: challenge`. Cloudflare does not decide per URL: it decides by **how whoever
     * asks presents themselves**.
     *
     * Distinct from [Solved] because it leads to a different action, which is the only criterion by
     * which a variant is added in this project: here **there is nothing to transfer**, so retrying
     * would mean remaking the same request that has just taken a 403 — another refusal, and on a site
     * behind Cloudflare also an insistence.
     *
     * Distinct from [TimedOut] because it is not a fault: the engine finished its work, and in a few
     * seconds rather than after the whole timeout.
     */
    data class NoChallenge(val finalUrl: String) : SilentChallengeResult

    /** Time ran out before a transit permit appeared. */
    data object TimedOut : SilentChallengeResult

    /**
     * There is no engine here to try with.
     *
     * It is not a fault to report as such: on a device where Android System WebView is disabled or
     * updating, `WebView(context)` throws. Distinguishing it from [TimedOut] serves whoever reads the
     * diagnostics — "there is no WebView" and "the WebView did not manage it" lead to two different
     * conclusions.
     */
    data class Unavailable(val reason: String) : SilentChallengeResult
}
