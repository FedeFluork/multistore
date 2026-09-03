package com.multistore.store.an1

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.html.PageFetcher
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * an1 for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network. This is the only one in the module that does, and it is
 * excluded from `test`. Run it with `./gradlew :store:an1:canaryTest`.
 *
 * A canary's value is in its message: "an1 is unreachable", "it changed markup" and "it is rate
 * limiting us" lead to three different jobs, and whoever reads the issue at four in the morning
 * should be able to tell from the first line.
 */
@Tag("canary")
@DisplayName("Canary — an1 (real network)")
class An1CanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var config: An1Config
    private lateinit var an1: An1StoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("an1-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        config = An1Config()
        an1 = An1StoreAdapter(config = config, clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results`() = runTest {
        val page = an1.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        assertThat(page.items.first().title).isNotEmpty()
        // Modified entries carry an extra class (`class="item_app mod"`) and are about half the
        // results: if the selector became an exact attribute comparison, search would keep working
        // while **silently losing the modified half** of the catalogue — which on this store is
        // precisely why people use it.
        //
        // **The threshold has to be high enough to notice that**, and at 5 it was not. Measured
        // 03/09/2026: the live page returns 10 rows, of which 4 are titled `(MOD …)`, so an
        // exact-attribute selector would leave 5 or 6 — and `isAtLeast(5)` would have stayed green
        // through the exact regression its own comment describes. A caption, in this repository's
        // vocabulary. Eight keeps the whole page's worth of margin against a thin day while going
        // red the moment half the rows vanish.
        //
        // What was **considered and rejected**: asserting that at least one title contains "MOD",
        // which would test the invariant directly. `StoreListingSummary` carries no
        // modified-or-not flag, so the only available signal is an1's own naming — and asserting a
        // store's vocabulary is the shape that reddened apkcombo's feed check on a real app name.
        // A count is about our selector; a title is about their copywriting.
        assertThat(page.items.size).isAtLeast(MIN_RESULTS)
    }

    @Test
    fun `search still paginates`() = runTest {
        val first = an1.search(QUERY, page = 0).orFail("search p.1")
        val second = an1.search(QUERY, page = 1).orFail("search p.2")

        // DLE wants **two** parameters, and sending only one returns the first page. The way this
        // breaks is insidious: no error, just an infinite scroll always showing the same ten
        // apps.
        assertThat(second.items.map { it.ref }).containsNoneIn(first.items.map { it.ref })
    }

    @Test
    fun `the listing still exposes its microdata`() = runTest {
        val detail = an1.getAppDetails(StoreAppRef(APP_REF)).orFail("detail")

        // The title comes from the microdata name, not from the `<h1>` that reads "Download
        // Telegram 12.4.3 free on android" — that sentence would end up in "My apps" and in the
        // update notification.
        //
        // **The invariant is that the headline did not win, not that the name is "Telegram".** The
        // equality that stood here asserted an1's chosen display name, which they may change at
        // will, and it was justified by a mechanism that **does not exist**: the comment said the
        // fallback would produce the `<h1>`, and it cannot — `An1DetailParser` falls back from
        // `attrOrNull(detailName, content)` to `text(detailName)`, the *same* selector, and
        // `HtmlPage.text` fails on blank text. A vanished microdata name is therefore already a
        // `ParseFailure` with a selector in it, which is a better message than any equality here
        // could produce. This is the store's own offline invariant, from `An1ParsersTest`.
        assertThat(detail.summary.title).doesNotContain("Download")
        assertThat(detail.summary.title).isNotEmpty()
        assertThat(detail.summary.developer).isNotEmpty()
        assertThat(detail.versions).hasSize(1)
        assertThat(detail.versions.single().versionName).isNotEmpty()
    }

    @Test
    fun `the download is still the app's file, and still carries the hash`() = runTest {
        val resolution = an1.getDownloadLink(StoreAppRef(APP_REF)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("an1 declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(An1Config.DEFAULT_DOWNLOAD_HOSTS).contains(java.net.URI(direct.url).host)
        // Next to the real file, on the same page and on the **same host**, sits an1's own store
        // app. If the precise anchor disappeared and the parser fell back to a broader selector,
        // the user would install that.
        assertThat(direct.url).doesNotContain("an1store")

        // The hash is the only thing that makes an1 verifiable, and it lives in an S3 header on
        // **some** objects. If it disappeared from here the verification card would quietly switch
        // to "hash not published" with nobody noticing — so it is checked, but the check has to
        // say *which* absence it found. Both fields come from a single un-retried `HEAD` whose
        // every failure collapses to `null`, so "an1 stopped publishing checksums" and "that one
        // request did not answer" used to arrive as the same bare `expected not to be null`, under
        // the heading `canary.yml` reserves for the first. See [an1HeadVerdict].
        when (an1HeadVerdict(direct.expectedSha256, direct.expectedSize)) {
            HeadVerdict.ANSWERED -> Unit

            // Real news, and a failure: an1 with no hash is an an1 with no integrity verification.
            // The message stops short of the conclusion, because one object re-uploaded through
            // the older path looks exactly like the header being gone store-wide.
            HeadVerdict.HASH_NOT_PUBLISHED -> error(
                "download: **the CDN answered, and published no checksum for this object.** " +
                    "Size came back (${direct.expectedSize}), so this is not a network problem " +
                    "and not a markup change: `$CHECKSUM_NOTE` is simply absent from " +
                    "`${direct.fileName}`. Do not conclude the capability yet — an1 has two " +
                    "uploader paths and the older one publishes nothing (0 of 8 sampled older " +
                    "on-host listings carry the header, against 12 of 12 linked from the " +
                    "homepage), so `$APP_REF` may just have been re-uploaded. **Check another " +
                    "recent listing first.** If those still carry it, re-anchor this canary's " +
                    "reference; if none do, then it is the news `canary.yml` describes and " +
                    "`providesHash` has to go back to `NONE`.",
            )

            // The case that was being reported as the one above. It says nothing about checksums.
            HeadVerdict.HEAD_DID_NOT_ANSWER -> {
                val code = (fetcher().head(direct.url) as? StoreResult.Success)?.value?.code
                if (an1HeadIsSharedBudget(code)) {
                    abort<Nothing>(
                        "download: **the file host refused with $SHARED_BUDGET_CODE, and that " +
                            "budget is not ours.** `files.an1.net` publishes `x-ratelimit-*` " +
                            "headers that do not describe us — measured, `remaining` stayed put " +
                            "across three identical requests, fell by three while we made one, " +
                            "and once *rose* between measurements — so it is a shared, " +
                            "recharging budget and a 429 can arrive without our having consumed " +
                            "anything. Lowering `permitsPerSecond` would not help and would slow " +
                            "every user down. This check is **skipped**: it verified nothing " +
                            "about the checksum either way, which is exactly what the old " +
                            "assertion got wrong. It appears in the run's step summary.",
                    )
                }
                error(
                    "download: **the single `HEAD` on the file did not answer** " +
                        "(${code ?: "nothing readable"}), so neither the size nor the checksum " +
                        "could be read and **this says nothing about whether an1 still publishes " +
                        "hashes**. It is also not a markup change: the page parsed, the anchor " +
                        "was found, the URL is on an1's own host. `getDownloadLink` makes that " +
                        "request once and swallows every failure into `null` " +
                        "(`as? Success` drops an IOException, `takeIf { isSuccessful }` drops a " +
                        "429 and every 5xx), which is why nothing reached the `RateLimited` " +
                        "branch below. A 5xx here is the CDN erroring on the object; retry by " +
                        "hand before changing anything.",
                )
            }
        }
    }

    private suspend fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `An1Selectors` needs updating " +
                    "and the matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **an1 is blocking us** (${e.kind}). This had never happened — an1 answers " +
                    "identically to any User-Agent, including none. Check whether it has " +
                    "introduced anti-bot protection and reassess its tier and risk in the store " +
                    "table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **an1 is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: before touching the adapter, look at " +
                    "`permitsPerSecond`. Note that `x-ratelimit-remaining` on the CDN is a " +
                    "**shared** budget, not ours: a 429 can arrive without us having consumed " +
                    "it.",
            )
            // Three causes, and the third was not merely missing: the old sentence **closed the
            // question** against it. It ended "Opening the page by hand says which of the two",
            // and on the third cause opening the page shows a working green download button — so
            // the reader concludes the canary is broken and stops trusting it.
            StoreError.NotFound -> error(
                "$what: **NotFound**, and there are three readings, not two. (1) `$APP_REF` is no " +
                    "longer on an1, or (2) the slug has changed — an1 renames slugs, and it does " +
                    "so halfway through the download redirect chain too. (3) **On `download`: " +
                    "the anchor was found and points off an1's hosts.** `An1DownloadParser` " +
                    "returns this same error rather than following a link that leaves " +
                    "`${An1Config.DEFAULT_DOWNLOAD_HOSTS.joinToString(", ")}` — a deliberate " +
                    "refusal, because on a store that publishes no package name the host list is " +
                    "the last structural control the pre-install pipeline has. It is measured, " +
                    "not hypothetical: 2 of 10 minecraft listings resolve to a `bit.ly` ending " +
                    "at Google Drive. **If this is (3), opening the page by hand is misleading**: " +
                    "the button works, it just goes somewhere we refuse to follow, and the fix is " +
                    "either a new reference app or — if an1 has moved its own files to a new host " +
                    "— `An1Config.DEFAULT_DOWNLOAD_HOSTS`. Check where the button actually " +
                    "points before deciding between the three.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    /**
     * The adapter's own client, for the one diagnostic request the adapter does not expose.
     *
     * Built from [An1Config]'s User-Agent and rate limit rather than from a fresh OkHttp client: a
     * canary measuring with a second client would be measuring that client.
     */
    private fun fetcher(): PageFetcher = PageFetcher(
        clients.forStore(
            StoreId.AN1,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private companion object {
        const val QUERY = "minecraft"
        const val APP_REF = "2971-telegram"

        /**
         * Eight of ten, not five.
         *
         * See the search test: at 5 this threshold could not notice the loss of the modified half
         * it was written to protect.
         */
        const val MIN_RESULTS = 8

        /** Named in a message rather than parsed: the S3 header carrying an1's only hash. */
        const val CHECKSUM_NOTE = "x-amz-meta-checksum-sha256"
    }
}
