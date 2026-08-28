package com.multistore.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How strict verification is before installing.
 *
 * Both fields are phrased **negatively** — "allow" rather than "verify". proto3 has no explicit
 * defaults: the zero value of a `bool` is `false`. A field named `verifyHashWhenAvailable` would
 * therefore start off, meaning verification disabled, while the name reads as the opposite.
 * Phrased this way the zero value **is** the safe behaviour.
 *
 * What is absent matters as much: the `packageName` match between file and listing is not
 * represented here because it is not configurable. It is the defence against installing the
 * wrong APK, and it has no switch.
 */
data class SecuritySettings(
    /**
     * Do not block when the SHA-256 published by the store does not match the file.
     *
     * Even when on, the mismatch stays **declared**: the verification result reports "hash not
     * verified", because *verified* and *not contradicted* are what the user must be able to
     * tell apart.
     */
    val allowUnverifiedHash: Boolean = false,
    /**
     * Do not block when the signature is not the expected one.
     *
     * It does not govern the case of a package already installed under a different signature:
     * there the OS refuses the update, and the only way out is uninstall and reinstall — a
     * per-app choice, available with this switch off too.
     */
    val allowSignerMismatch: Boolean = false,
)

/**
 * Network settings that concern heavy traffic: store indexes and downloads.
 *
 * A full F-Droid index is around 18 MB compressed. Downloading that on a metered connection
 * without asking is a cost the user did not authorise, which is why this is a field rather than
 * a decision taken in code.
 */
data class NetworkSettings(
    /** `false` = on a metered network, ask instead of starting on our own. */
    val meteredNetworkAllowed: Boolean = false,
    /**
     * How far the app may escalate on its own when a store challenges it.
     *
     * The default is [ChallengeStrategy.BALANCED] and coincides with the proto3 zero value: see
     * the note on ordering inside the enum, where the same choice written the other way round
     * would have started the app with no WebView at all.
     */
    val challengeStrategy: ChallengeStrategy = ChallengeStrategy.DEFAULT,
    /**
     * Do not open the store page even when a tap is the only way to download.
     *
     * **Negative on purpose.** Written as `allowUserAssistedChallenge`, the proto3 zero value
     * would be `false` — uptodown and pdalife downloads switched off for anyone who never
     * opened Settings, with nothing saying why.
     *
     * What it does not govern: the silent WebView rung. That asks the user nothing and is
     * decided by [challengeStrategy]. This is only about the path where a person must tap.
     */
    val blockUserAssistedChallenge: Boolean = false,
    /**
     * Let advertising through in the assisted-download WebView.
     *
     * `false` — the proto3 zero value — means **filter on**, the prudent behaviour: that WebView
     * opens download pages of stores that live on advertising, where on pdalife two of the three
     * buttons are adverts and one of them serves a real `.apk`.
     *
     * The switch exists because a filter that gets it wrong breaks the only thing that screen is
     * for. It does not touch the exceptions: Cloudflare Turnstile and reCAPTCHA are never
     * blocked, so turning it on will not unblock a download stuck because of them.
     */
    val allowWebAds: Boolean = false,
)

/**
 * What search is allowed to show.
 *
 * A separate type rather than part of [NetworkSettings] because it is not a network choice but a
 * choice about **what** is seen, and it is applied somewhere else: in `SearchRepository`, before
 * the fan-out.
 */
data class SearchSettings(
    /**
     * `false` — the default — hides what a store **labels** as adult content.
     *
     * The safe behaviour is "do not show", which for a `bool` is exactly `false`, so the
     * positive name already gives the right default.
     *
     * It is not a promise about the catalogue. Only one store of the nine publishes the label,
     * and publishes it incompletely: see the note on `show_nsfw_content` in `settings.proto`.
     */
    val showNsfwContent: Boolean = false,
    /**
     * How long **one** store is waited for before it is declared absent from this search.
     *
     * Per store, not per search: waiting for the slowest before showing the first result would,
     * with nine sources, be a search that looks broken. A store that times out becomes a
     * shortfall shown next to the others' results.
     */
    val storeTimeout: Duration = DEFAULT_STORE_TIMEOUT,
    /**
     * How to order results when the search does not ask for something else.
     *
     * [SearchSort.RELEVANCE] is also the proto zero value: it is the order the aggregator
     * produces by itself.
     */
    val defaultSort: SearchSort = SearchSort.RELEVANCE,
    /**
     * Whether to start showing only apps, only games, or everything. `null` = everything.
     *
     * Null rather than [ContentKind.UNKNOWN]: that value means "this store does not say", which
     * is a possible answer for a row, whereas the question here is whether the filter is active.
     * Conflating them would give a filter reading "show only apps whose kind is unknown".
     */
    val defaultContentKind: ContentKind? = null,
) {
    companion object {
        /**
         * Deliberately generous.
         *
         * apkmirror declares `Crawl-delay: 3` and the rate limiter honours it **before** issuing
         * the request: a tight timeout would cut that store out precisely for having been polite
         * to it.
         */
        val DEFAULT_STORE_TIMEOUT: Duration = 8.seconds

        /**
         * The permitted range.
         *
         * Below two seconds apkmirror would not receive the request in time, so a lower bound
         * would switch a store off while appearing to speed search up. Above a minute the screen
         * would sit on "8 stores out of 9" long enough to read as a hang.
         *
         * **The minimum must stay greater than zero.** It is also what makes the proto3 zero
         * value — "the user never chose anything" — fall back to the default. See
         * `Int.toStoreTimeout` in `:core:datastore`.
         *
         * An out-of-range value falls back to the default rather than being clamped: clamping
         * 100000 to 60 would give a minute's wait nobody asked for.
         */
        val STORE_TIMEOUT_RANGE: ClosedRange<Duration> = 2.seconds..60.seconds

        /**
         * The values the screen offers.
         *
         * [DEFAULT_STORE_TIMEOUT] is among them on purpose: choosing it explicitly writes `8`
         * where `0` was, and nothing changes. A separate "default" entry would add a state that
         * behaves identically to an existing one.
         */
        val STORE_TIMEOUT_CHOICES: List<Duration> =
            listOf(4.seconds, DEFAULT_STORE_TIMEOUT, 15.seconds, 30.seconds)
    }
}
