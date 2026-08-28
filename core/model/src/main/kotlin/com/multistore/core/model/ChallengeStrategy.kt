package com.multistore.core.model

/**
 * How far up the escalation ladder the app may climb, according to the user.
 *
 * The ladder: rung 0 the plain request, 1 the retry forcing HTTP/1.1, 2 Cronet (not implemented:
 * no measurement justifies it), 3 the silent WebView that **executes** the JS challenge, 4 the
 * visible WebView with the user's tap. Rung 4 does not depend on this choice: it is a path the
 * user starts, and it has a switch of its own.
 *
 * It lives in `:core:model` rather than `:core:network` because it is also the type of a setting,
 * and `NetworkSettings` is here. The first value declared is the one proto3 assigns to zero, i.e.
 * the default when the field is absent: **[BALANCED]**. With `CONSERVATIVE` at zero the app would
 * have started **without a WebView**, breaking the stores that need rung 3 for anyone who never
 * changed the setting. The rule is always the same: **the zero value must be the behaviour that
 * is wanted**, not the first that comes to mind writing the list in increasing aggressiveness.
 */
enum class ChallengeStrategy(
    /** The highest rung this strategy allows reaching. */
    val maxTier: Int,
) {
    /** The default: up to the silent WebView, which asks nothing of anyone. */
    BALANCED(SILENT_WEBVIEW_TIER),

    /**
     * Network only: no WebView, no alternative engine.
     *
     * Not "safer" in a strict sense — a WebView executing a site's challenge does exactly what a
     * browser open on that page would. It is **leaner**: no Chromium engine started, no
     * third-party JavaScript executed, no clearance cookie kept. Whoever picks it loses the
     * stores behind a challenge, and knows it.
     */
    CONSERVATIVE(PROTOCOL_FALLBACK_TIER),

    /** Like [BALANCED] but with more retries and longer waits. */
    AGGRESSIVE(SILENT_WEBVIEW_TIER),
    ;

    /** `true` if this strategy allows opening a WebView without asking anything. */
    val allowsSilentWebView: Boolean get() = maxTier >= SILENT_WEBVIEW_TIER

    companion object {
        /**
         * The default, restated here in readable form.
         *
         * Not redundant with `entries.first()`: it is the value the tests compare against what
         * the DataStore returns for an absent field, and the only way to make an accidental
         * reordering of the enum fail instead of slipping through.
         */
        val DEFAULT: ChallengeStrategy = BALANCED
    }
}

/** Rung 1: retry forcing HTTP/1.1. Pure protocol negotiation. */
private const val PROTOCOL_FALLBACK_TIER = 1

/** Rung 3: the silent WebView. See `:core:challenge`. */
private const val SILENT_WEBVIEW_TIER = 3
