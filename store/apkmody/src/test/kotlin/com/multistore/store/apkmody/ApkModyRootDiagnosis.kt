package com.multistore.store.apkmody

/**
 * Where apkmody's root actually leads — the question this store's canary has to ask before naming a
 * cause, and the one its `healthCheck` cannot answer.
 *
 * ### Why the adapter's own probe is not enough here
 *
 * [ApkModyStoreAdapter.healthCheck] is
 *
 * ```kotlin
 * fetcher.resolveRedirect(config.baseUrl).map { }
 * ```
 *
 * and `.map { }` throws away [PageFetcher.Redirected.url][com.multistore.store.common.html.PageFetcher.Redirected].
 * So it answers `Success` for a root that has **301'd somewhere else entirely** — and on this store
 * that is not a hypothetical shape, it is the demonstrated failure mode. `apkmody.com` already
 * redirects deep paths to `wokogames.com`, and `.fun` to an IPTV site; `.mobi` is the last of the
 * three still standing, which is why it is the one in [ApkModyConfig.DEFAULT_BASE_URL]. A probe
 * blind to a moved root is blind to precisely the thing most likely to happen to apkmody.
 *
 * The canary therefore resolves the root itself, through **the adapter's own client and config** —
 * same User-Agent, same rate limit — and compares the host it lands on. `healthCheck` is left as it
 * is: it has no production caller at all, and widening production for a diagnostic want is what
 * this project declines to do. That it is blind is worth writing down rather than quietly fixing
 * under cover of a canary change.
 *
 * ### The three readings, and why one of them is not a skip
 *
 * uptodown's remedy — abort when the whole store is unreachable — does not transplant here, and the
 * reason is that on apkmody the equivalent finding is **news**, not noise. A root that answers from
 * a different domain means the store has moved, and that is a repair: a new
 * `DEFAULT_BASE_URL`, a re-check of what the old domain now serves, and a line in the store table.
 * Skipping it would hide the one event this store's history says to expect.
 */
internal enum class RootReading {
    /** The root answered and stayed on apkmody's own host: whatever failed, failed on its own. */
    OWN_HOST,

    /**
     * The root answered from **somewhere else**: the domain has moved or been parked.
     *
     * The loudest possible reading, and the one `healthCheck` cannot produce.
     */
    MOVED_AWAY,

    /** The root did not answer at all, so it explains nothing by itself. */
    NO_ANSWER,
}

/**
 * The reading for a resolved root, given the host it landed on.
 *
 * [finalHost] is `null` when the probe could not be completed. The comparison is against
 * [ApkModyConfig.baseUrl]'s host rather than the [ApkModyConfig.HOST] constant, and that is the
 * same choice `ApkModyStoreAdapter.isOwnHost` makes for the download chain: the base URL is what a
 * signed `parsers.json` can move if the store changes domain, so anchoring to the constant would
 * make a **successful** migration read as a store that had run away.
 */
internal fun apkModyRootReading(finalHost: String?, expectedHost: String?): RootReading = when {
    finalHost == null || expectedHost == null -> RootReading.NO_ANSWER
    finalHost.equals(expectedHost, ignoreCase = true) -> RootReading.OWN_HOST
    else -> RootReading.MOVED_AWAY
}

/**
 * Which repairs a 404 can mean on apkmody, written out so the message cannot quietly name fewer.
 *
 * `StoreError.NotFound` has **five** producers on this adapter and the message it replaced named
 * roughly one and a half of them:
 *
 *  1. `ApkModyRefs.appPath(ref)` returning `null` — our own ref shape, from three call sites;
 *  2. the **package contradiction** guard: the listing declares one package and the file on the CDN
 *     lives under another's path. Reachable from `getAppDetails` only, and the only one the old
 *     sentence really described;
 *  3. **no anchor on the CDN host.** `ApkModyDownloadParser` runs through `parseHtmlOrNotFound`, so
 *     a download page with no link whose host is [ApkModyConfig.downloadHost] arrives here and not
 *     as a `ParseFailure`. The repair is one field — `downloadHost` — and the old message did not
 *     mention it at all;
 *  4. an HTTP 404 from the site, which on this store most often means the **domain** moved;
 *  5. `/popular` renamed, which 404s `getTrending` and has nothing to do with any listing.
 *
 * Three of the five lead to a one-field edit in [ApkModyConfig] and none of the three is "pick
 * another app", which is where the old sentence sent every reader.
 */
internal const val NOT_FOUND_PRODUCERS = 5
