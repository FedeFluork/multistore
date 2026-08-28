package com.multistore.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("The WebView filter")
class WebFilterConfigTest {

    private val filter = WebFilterConfig()

    @Test
    @DisplayName("blocks a subdomain of an ad network, not just the exact host")
    fun blocksSubdomains() {
        // `pagead2` and `tpc` are two hosts of the same network, censused on three stores.
        // Listing them one by one would mean a release for every third one that network adds.
        assertThat(filter.blocks("pagead2.googlesyndication.com")).isTrue()
        assertThat(filter.blocks("tpc.googlesyndication.com")).isTrue()
        assertThat(filter.blocks("googlesyndication.com")).isTrue()
    }

    @Test
    @DisplayName("a domain ending in the same text is not the same domain")
    fun doesNotMatchByBareSuffix() {
        // Without the leading dot, a domain registered on purpose would pass for its namesake —
        // an old trick, not a textbook case.
        assertThat(filter.blocks("notgooglesyndication.com")).isFalse()
    }

    @Test
    @DisplayName("challenge providers are never blocked: they are why the WebView exists")
    fun neverBlocksChallengeProviders() {
        assertThat(filter.blocks("challenges.cloudflare.com")).isFalse()
        assertThat(filter.blocks("www.gstatic.com")).isFalse()
        assertThat(filter.blocks("www.google.com")).isFalse()
    }

    @Test
    @DisplayName("an allowance does not swallow what sits beneath it")
    fun anAllowanceDoesNotSwallowABlockedHost() {
        // The first draft had `google.com` among the allowances, and from there
        // `adservice.google.com` — which is in the blocked list — came out permitted. The
        // allowance must be written as a host, not a domain, and this test is the only place
        // that would notice.
        assertThat(filter.blocks("adservice.google.com")).isTrue()
        assertThat(filter.blocks("fundingchoicesmessages.google.com")).isTrue()
    }

    @Test
    @DisplayName("a host absent from the list goes through")
    fun leavesEverythingElseAlone() {
        // The filter is a list of things not to load, not a permission to request: working the
        // other way round, an uncensused host would break the page.
        assertThat(filter.blocks("cdn.topmongo.com")).isFalse()
        assertThat(filter.blocks("downloadr2.apkmirror.com")).isFalse()
        assertThat(filter.blocks(null)).isFalse()
        assertThat(filter.blocks("")).isFalse()
    }

    @Test
    @DisplayName("the trailing dot of an absolute name does not make it another host")
    fun toleratesTheTrailingDot() {
        assertThat(filter.blocks("pagead2.googlesyndication.com.")).isTrue()
    }
}
