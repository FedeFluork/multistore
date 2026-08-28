package com.multistore.core.model

import kotlinx.serialization.Serializable

/**
 * Which requests the assisted-download WebView must **not** make.
 *
 * ### Why it is not uBlock Origin
 *
 * It cannot be: a `WebView` has no extensions, and no Adblock-Plus-style rule engine runs inside
 * one. What can be done, and what covers most of the harm on these pages, is not opening the
 * connection at all — `shouldInterceptRequest` returns an empty response and the request never
 * leaves. Cosmetic rules are out of reach: a blocked banner leaves its hole in the page.
 *
 * ### Why the list is short, and where it comes from
 *
 * It is not EasyList: it is a **census of the third-party hosts the nine stores actually load**,
 * taken on 27/08/2026 from the pages committed as fixtures. 297 distinct hosts, of which these
 * are the advertising, tracking and redirect ones. A general-purpose list would be broader and
 * would bring a license, a weight and an inventory nobody here has measured; this one updates
 * over the signed channel, which is also where it can grow without a release.
 *
 * ### [allowedHosts] always wins, and that is not politeness
 *
 * Cloudflare Turnstile and reCAPTCHA come from hosts every advertising list contains. Blocking
 * them would break **the assisted download itself**, the only reason that WebView exists: the
 * page would spin on a challenge it cannot complete, and the symptom — "this store stopped
 * working" — would never lead anyone to look here.
 */
@Serializable
data class WebFilterConfig(
    val blockedHosts: List<String> = DEFAULT_BLOCKED,
    val allowedHosts: List<String> = DEFAULT_ALLOWED,
) {

    private val blocked: Set<String> = blockedHosts.map { it.lowercase() }.toSet()
    private val allowed: Set<String> = allowedHosts.map { it.lowercase() }.toSet()

    /**
     * `true` if a request to [host] should be dropped.
     *
     * The comparison is on the **domain suffix**, not on equality: `googlesyndication.com` covers
     * `pagead2.` and `tpc.` without listing them, and an ad network that adds a third tomorrow
     * needs no release. The leading dot is not decorative: without it,
     * `notgooglesyndication.com` would match.
     */
    fun blocks(host: String?): Boolean {
        val name = host?.lowercase()?.trimEnd('.').orEmpty()
        if (name.isEmpty()) return false
        if (matches(name, allowed)) return false
        return matches(name, blocked)
    }

    private fun matches(host: String, suffixes: Set<String>): Boolean =
        suffixes.any { host == it || host.endsWith(".$it") }

    companion object {
        /**
         * The advertising and tracking hosts censused on the nine stores' pages.
         *
         * Grouped by family rather than alphabetically: whoever opens this list is here to add
         * something, and the question they are asking is "what kind is it".
         */
        val DEFAULT_BLOCKED: List<String> = listOf(
            // Google ad networks, present on apkmody, an1 and pdalife.
            "googlesyndication.com",
            "doubleclick.net",
            "googletagservices.com",
            "adservice.google.com",
            "fundingchoicesmessages.google.com",
            // Measurement and tracking, on seven stores out of nine.
            "googletagmanager.com",
            "google-analytics.com",
            "scorecardresearch.com",
            "clarity.ms",
            "stats.wp.com",
            "mc.yandex.ru",
            // Networks that on these stores bring the interstitials and the new windows.
            // `profitablecpmratenetwork.com` is the species that makes this list necessary
            // rather than tidy: a CPM network that lives on redirects.
            "profitablecpmratenetwork.com",
            "push-sdk.com",
            "adschill.com",
            "ssm.codes",
            "ads.uptodown.dev",
            "openpanel.enbox.net",
            // The hosts behind the **fake buttons** on download pages: a real APK that is not
            // the one asked for (`monstervpn`, `jooyfun`), or an affiliate destination. Blocking
            // them takes nothing from the page and takes away the wrong tap.
            "api.monstervpn.cc",
            "appstore.jooyfun.com",
            "cdn.ezjojoy.com",
            "thr33trk.com",
            "s0-greate.net",
            "nap5k.com",
            "al5sm.com",
            "maniskdow.club",
            "aj1559.online",
            "bvtpk.com",
            "omenpenial.com",
            "static.apkflash.com",
        )

        /**
         * What is never blocked, whatever the rest says.
         *
         * The two challenge providers the assisted path **must** be able to execute — actually
         * doing what the site asks is allowed — plus the hosts their scripts come from: reCAPTCHA
         * loads from `www.google.com/recaptcha` and `www.gstatic.com/recaptcha`, and on pdalife
         * the download goes through exactly there.
         *
         * **These are whole hosts, not domains**, and the first draft got that wrong: with
         * `google.com` in this list, the "an allowance wins" rule also permitted
         * `adservice.google.com`, which is in the blocked list above. An allowance written too
         * broadly is not an allowance, it is a hole — and the symptom would have been advertising
         * still getting through with no error anywhere.
         */
        val DEFAULT_ALLOWED: List<String> = listOf(
            "challenges.cloudflare.com",
            "recaptcha.net",
            "www.gstatic.com",
            "www.google.com",
        )
    }
}
