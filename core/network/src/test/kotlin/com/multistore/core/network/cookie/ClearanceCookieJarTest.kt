package com.multistore.core.network.cookie

import com.google.common.truth.Truth.assertThat
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The jar that makes rung 3 useful.
 *
 * What these tests defend is not "cookies are kept" — OkHttp does that — but the three decisions
 * taken writing [ClearanceCookieJar], each of which, wrong, produces a **silent** failure: a
 * valid `cf_clearance` not presented, presented where it should not be, or presented after
 * expiry.
 */
@DisplayName("ClearanceCookieJar — the transit permit, and where it applies")
class ClearanceCookieJarTest {

    private val jar = ClearanceCookieJar()

    private val challengedPage = "https://liteapks.com/spotify-2.html".toHttpUrl()
    private val otherPage = "https://liteapks.com/telegram-3.html".toHttpUrl()
    private val otherStore = "https://uptodown.com/android".toHttpUrl()

    /**
     * A listing **inside a directory**, and the download page that sits outside it.
     *
     * These are an1's, and they exist to genuinely exercise `Path=/`: without that attribute
     * `Cookie.parse` derives the path from the URL, and for a page at the root — as liteapks'
     * are — the derived path *is already* `/`. Tested there, the defence protected nothing and
     * the test stayed green even with it removed.
     */
    private val nestedPage = "https://an1.com/games/1234-telegram.html".toHttpUrl()
    private val downloadPage = "https://an1.com/file_1234-dw.html".toHttpUrl()

    @Nested
    @DisplayName("what arrives from the WebView")
    inner class FromWebView {

        @Test
        @DisplayName("comes back on the next request to that store")
        fun theClearanceComesBack() {
            jar.acceptFromWebView(challengedPage, "cf_clearance=abc123")

            assertThat(jar.loadForRequest(challengedPage).map { it.name to it.value })
                .containsExactly("cf_clearance" to "abc123")
        }

        @Test
        @DisplayName("applies to the whole host, not to the directory it was obtained in")
        fun theClearanceIsHostWide() {
            jar.acceptFromWebView(nestedPage, "cf_clearance=abc123")

            // Without `Path=/` the cookie would apply to `/games` only, and the download page
            // — which on an1 sits at the root — would redo the challenge from scratch. Rung 3
            // would cost one WebView per directory instead of one per store.
            assertThat(jar.loadForRequest(downloadPage)).hasSize(1)
        }

        @Test
        @DisplayName("the page next door does not redo the challenge")
        fun theSiblingPageIsCovered() {
            jar.acceptFromWebView(challengedPage, "cf_clearance=abc123")

            assertThat(jar.loadForRequest(otherPage)).hasSize(1)
        }

        @Test
        @DisplayName("it does not leave the host it came from")
        fun theClearanceStaysOnItsHost() {
            jar.acceptFromWebView(challengedPage, "cf_clearance=abc123")

            assertThat(jar.loadForRequest(otherStore)).isEmpty()
        }

        @Test
        @DisplayName("a line with several cookies accepts them all")
        fun everyPairIsKept() {
            val accepted = jar.acceptFromWebView(challengedPage, "cf_clearance=abc123; __cf_bm=xyz")

            assertThat(accepted).isEqualTo(2)
            assertThat(jar.loadForRequest(challengedPage).map { it.name })
                .containsExactly("cf_clearance", "__cf_bm")
        }

        @Test
        @DisplayName("an empty line is not a transfer: it answers zero")
        fun nothingHarvestedIsZero() {
            // This is the value rung 3 uses to decide **not** to retry. Were it to answer one,
            // the ladder would repeat the very request that just got a 403.
            assertThat(jar.acceptFromWebView(challengedPage, null)).isEqualTo(0)
            assertThat(jar.acceptFromWebView(challengedPage, "")).isEqualTo(0)
            assertThat(jar.acceptFromWebView(challengedPage, "   ")).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("the transfer counter")
    inner class Harvests {

        @Test
        @DisplayName("grows only when it was a WebView")
        fun onlyWebViewHarvestsCount() {
            jar.saveFromResponse(
                challengedPage,
                listOf(Cookie.parse(challengedPage, "session=1; Path=/")!!),
            )

            // An ordinary `Set-Cookie` is not a freshly won transit permit: were it counted, a
            // second caller in the queue would conclude "someone already did it" and retry with
            // no WebView ever having run.
            assertThat(jar.harvestCount(challengedPage.host)).isEqualTo(0)

            jar.acceptFromWebView(challengedPage, "cf_clearance=abc123")

            assertThat(jar.harvestCount(challengedPage.host)).isEqualTo(1)
        }

        @Test
        @DisplayName("it is per host: one store's does not speak for another's")
        fun theCounterIsPerHost() {
            jar.acceptFromWebView(challengedPage, "cf_clearance=abc123")

            assertThat(jar.harvestCount(otherStore.host)).isEqualTo(0)
        }
    }

    @Test
    @DisplayName("an expired cookie is no longer presented")
    fun expiredCookiesAreDropped() {
        // The expiry is built by hand rather than via `Max-Age`: OkHttp computes that against
        // the **system** clock, so with a fake clock the cookie would be valid for the next sixty
        // years and the test would pass without proving anything.
        var now = 1_000_000L
        val clocked = ClearanceCookieJar(now = { now })
        clocked.saveFromResponse(
            challengedPage,
            listOf(
                Cookie.Builder()
                    .name("cf_clearance")
                    .value("abc123")
                    .domain(challengedPage.host)
                    .path("/")
                    .expiresAt(now + 60_000)
                    .build(),
            ),
        )

        assertThat(clocked.loadForRequest(challengedPage)).hasSize(1)

        now += 61_000

        assertThat(clocked.loadForRequest(challengedPage)).isEmpty()
    }

    @Test
    @DisplayName("a Set-Cookie with a past date withdraws the one that was there")
    fun aBackdatedCookieRemovesTheStoredOne() {
        val now = 1_000_000L
        val clocked = ClearanceCookieJar(now = { now })
        clocked.acceptFromWebView(challengedPage, "cf_clearance=abc123")

        // This is how a site withdraws a permit it no longer wants presented.
        clocked.saveFromResponse(
            challengedPage,
            listOf(Cookie.parse(challengedPage, "cf_clearance=abc123; Path=/; Max-Age=0")!!),
        )

        assertThat(clocked.loadForRequest(challengedPage)).isEmpty()
    }
}
