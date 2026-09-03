package com.multistore.store.liteapks

/**
 * How to read a `preflight` that came back **false** on liteapks — and why the carefully hedged 429
 * sentence in the canary's `orFail` could never run.
 *
 * ### The red that carried no message
 *
 * The line this serves used to be `assertThat(reachable).isTrue()`, and the path it sits on makes
 * that the worst-behaved assertion in the class:
 *
 *  - [LiteapksStoreAdapter.preflight] is `fetcher.head(url, headers).map { it.isSuccessful }`;
 *  - [com.multistore.core.network.challenge.ChallengeDetector] recognises **403, 503 and 451** —
 *    and not 429;
 *  - so a 429 is not a challenge, does not become [com.multistore.store.api.StoreError.Blocked],
 *    and arrives as a perfectly successful `HeadResult(429)`;
 *  - `map { it.isSuccessful }` turns that into `StoreResult.Success(false)`;
 *  - `orFail` sees a `Success` and hands the `false` through, so Truth prints `expected to be
 *    true` and **the `RateLimited` branch written specifically about this case never executes.**
 *
 * That branch's own wording is the proof of what was lost. It says: *"a 429 on a **file** URL can
 * come from `down.appsupload.com`, which answers `too_many_requests` to everybody because it is
 * their account's budget, not ours."* Correct, useful, and structurally unreachable.
 *
 * ### Why only 429 skips
 *
 * `down.appsupload.com` is a third party's storage account serving part of liteapks' catalogue, and
 * its quota is not ours to manage — the same lesson as an1's `x-ratelimit-*` headers, which move
 * when the world moves and not when we do. Nothing in this repository makes that request succeed,
 * so failing on it is a nightly issue nobody can act on.
 *
 * Everything else stays a failure, **404 included**, and that is deliberately narrow. A 404 on the
 * canary's own reference means the file it points at has moved, which is a real premise expiry with
 * a real action — re-anchor the reference — and it is rare for a current, popular app. Widening
 * this to "any unhappy code on a host that is not liteapks'" would have been the easy reading, and
 * it would quietly swallow a CDN migration: `getDownloadLink` would keep resolving, `preflight`
 * would keep saying false, and the canary would skip its way to permanent silence.
 *
 * Measured through the adapter on 03/09/2026: `minecraft` resolves to
 * `down.appsupload.com/Minecraft/minecraft-v1.26.10.4-final-mod1.apk` — no token, no query — and
 * that host answered **200** with `content-length: 1145807996`, so the shared-quota case is real
 * but not firing today.
 */
internal enum class PreflightVerdict {
    /**
     * A third party's quota, not ours: skip the check.
     *
     * Recognised by the status code alone, because the host is not the discriminator — liteapks
     * spreads its catalogue over several CDNs and which one a given file lands on is their upload
     * choice, so a rule keyed on the host would have to be rewritten every time they add one.
     */
    SOMEONE_ELSES_QUOTA,

    /** Anything else: fail, naming the host and the code so the reader can tell which. */
    WORTH_FAILING,
}

/**
 * The verdict for a false `preflight`, given the status the same URL answers.
 *
 * [code] is `null` when the diagnostic request could not be completed at all, and that is
 * [PreflightVerdict.WORTH_FAILING]: two requests to one URL disagreeing about whether they can be
 * made is not a quota, and guessing permissively is how a canary stops reporting.
 */
internal fun liteapksPreflightVerdict(code: Int?): PreflightVerdict =
    if (code == SHARED_QUOTA_CODE) PreflightVerdict.SOMEONE_ELSES_QUOTA else PreflightVerdict.WORTH_FAILING

/**
 * The code `down.appsupload.com` answers to everybody: 429.
 *
 * Not a challenge — `ChallengeDetector` handles 403, 503 and 451 — which is exactly why it reaches
 * the canary as a bare `false` rather than as a `RateLimited` failure.
 */
internal const val SHARED_QUOTA_CODE = 429
