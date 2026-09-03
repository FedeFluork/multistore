package com.multistore.store.apkcombo

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * apkcombo for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * The fixtures freeze the markup of the day they were captured: a green suite over them says
 * nothing about what the site answers today. This is the only test in the module that touches the
 * network, and it is excluded from `test`. Run it with `./gradlew :store:apkcombo:canaryTest`,
 * which CI runs overnight **without blocking** anything.
 *
 * ### What it must say when it fails
 *
 * A canary's value is entirely in its message: "apkcombo is unreachable" and "apkcombo changed its
 * markup" lead to two different jobs, and a bare success assertion does not tell them apart. Each
 * check therefore separates the two cases — a parse failure names the selector to rewrite, a
 * network error does not.
 */
@Tag("canary")
@DisplayName("Canary — apkcombo (real network)")
class ApkComboCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var apkcombo: ApkComboStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("apkcombo-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkcombo = ApkComboStoreAdapter(config = ApkComboConfig(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results with a packageName`() = runTest {
        val page = apkcombo.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        // The `packageName` comes from the URL's second segment: if it disappeared, the
        // `providesPackageName = true` capability would become a lie and cross-store identity would
        // rest on the title alone.
        assertThat(page.items.mapNotNull { it.packageName }).isNotEmpty()
    }

    @Test
    fun `the listing still exposes version and version code`() = runTest {
        val detail = apkcombo.getAppDetails(StoreAppRef(APP_PATH)).orFail("detail")

        assertThat(detail.summary.title).isNotEmpty()
        assertThat(detail.summary.packageName).isEqualTo(PACKAGE_NAME)
        // The version code is only in the information table, inside a blurred span. It is the
        // listing's most fragile field and the one without which the anti-downgrade rule does not
        // work.
        assertThat(detail.summary.latestVersionCode).isNotNull()
        assertThat(detail.versions).isNotEmpty()
    }

    @Test
    fun `an app with several variants keeps one entry per variant`() = runTest {
        val detail = apkcombo.getAppDetails(StoreAppRef(MULTI_VARIANT_PATH)).orFail("detail")

        // The regression this guards: one app publishes several APKs and an XAPK **all with the
        // same version code**. While the version ref was derived from that code, the unique
        // constraint on `(listing_id, version_ref)` kept a single row — and the survivor was the
        // XAPK, which at the time the app could not install: the listing said "no installable
        // package" in front of a page offering five. It passed every fixture-based test and was
        // found by the device.
        //
        // The premise is a fact about the **store**, and it expires. Measured 31/08/2026: the
        // previous anchor, `com.duckduckgo.mobile.android`, shipped 5.294.0 as a single universal
        // APK where 5.292.x still offered three variants — so `isGreaterThan(1)` reddened the
        // nightly with "expected to be greater than: 1", which names none of the three jobs this
        // class exists to tell apart and sent the reader hunting a selector that had not moved.
        // Hence the premise is checked apart from the invariant, and says which of the two it is.
        val codes = detail.versions.map { it.versionCode }.toSet()
        if (detail.versions.size < 2) {
            error(
                "several variants: **the reference app now publishes a single variant**. Not a " +
                    "markup change, not a block, not a fault — apkcombo is serving " +
                    "'$MULTI_VARIANT_PATH' as one artifact, so this guard has lost its subject " +
                    "and proves nothing. Re-anchor `MULTI_VARIANT_PATH` to an app whose latest " +
                    "release still offers several variants sharing one version code. Note that " +
                    "**zero** variants is a different case and not this one: it is covered by the " +
                    "version-list fallback in `getAppDetails`, and on 31/08/2026 it was the shape " +
                    "of both `com.zhiliaoapp.musically` and `com.instagram.android`.",
            )
        }
        if (codes.size != 1 || codes.single() == null) {
            error(
                "several variants: **the variants no longer share one version code** " +
                    "(codes: $codes). The collapse this test guards needed refs derived from a " +
                    "code that repeats; with one code per variant they cannot collide whatever " +
                    "we derive them from, so a green here would be green for the wrong reason. " +
                    "Re-anchor `MULTI_VARIANT_PATH`.",
            )
        }

        // The invariant: as many distinct refs as there are variants. This is what the collapse
        // broke, and the only assertion here that is about us rather than about the store.
        assertThat(detail.versions.map { it.ref }.toSet()).hasSize(detail.versions.size)
        // And at least one installable without opening a container: the survivor of the original
        // defect was the XAPK, so an all-XAPK answer would hide the same symptom again.
        assertThat(detail.versions.any { it.artifactType == ArtifactType.APK }).isTrue()
    }

    @Test
    fun `the download still resolves a signed URL`() = runTest {
        val resolution = apkcombo.getDownloadLink(StoreAppRef(APP_PATH)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("apkcombo declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(direct.fileName).isNotEmpty()
        // The R2 signature lasts four hours. If it disappeared, a cached resolution would come
        // back 403 and look like a store block.
        assertThat(direct.expiresAt).isNotNull()
    }

    @Test
    fun `the new-releases feed still exists and carries the packageName`() = runTest {
        val page = apkcombo.getRecent().orFail("new-releases feed")

        // If the feed disappeared, the published document would lose its richest source and Home
        // would only notice at the next publication. The minimum count is deliberately low: a feed
        // is a window, and how wide it is depends on how much the store published that day.
        assertThat(page.items.size).isAtLeast(MIN_FEED_ITEMS)
        // The property that makes this source different from the other three: each entry's URL is
        // `/{slug}/{packageName}/`. If its shape changed, the entries would remain but without the
        // package — and step 4 of pre-install verification would lose its comparison.
        assertThat(page.items.count { it.packageName != null }).isEqualTo(page.items.size)
        // `stripPrefix` must go on removing the feed's **own** marker: were it to survive, every
        // row would carry it, every title would differ from the one on the listing, and to the
        // identity matcher Home would be ninety-six apps nobody has ever seen.
        //
        // The reading is **breadth, not brackets**, and it deliberately is not `startsWith("[")` —
        // that line reddened this nightly on 01/09/2026 over an app legitimately called
        // `[Official] Atomy shop`. Why, and why the decision is a function with an offline test
        // rather than four lines here, is in `survivingFeedMarker`: on a healthy day the live feed
        // contains neither of the two cases it exists to tell apart, so this call is the one thing
        // in the class that a green run cannot vouch for.
        survivingFeedMarker(page.items.map { it.title })?.let { marker ->
            error(
                "new-releases feed: **the feed's marker is no longer being stripped**. " +
                    "${marker.count} of ${marker.of} titles begin with a bracketed token keyed " +
                    "'${marker.key}' (for example '${marker.token}'). At that ratio it is a " +
                    "marker and not a name: apkcombo has changed the shape of the prefix and " +
                    "`ApkComboFeedParser.FEED_PREFIX` no longer matches it. This is not a " +
                    "selector, and nothing else in this class will go red — the entries are all " +
                    "there with their refs and their packages, and only their titles are wrong. " +
                    "Read the raw `<title>` of `latest-updates/feed` and widen that regex. If " +
                    "instead the ratio looks like a publisher that brackets its own app names, " +
                    "the threshold is what is wrong: see `MARKER_SHARE` and `MARKER_FLOOR`, and " +
                    "the 01/09/2026 note above.",
            )
        }
    }

    /**
     * The value, or a message saying **which** of the failures happened.
     *
     * This is what makes a canary useful: whoever reads the issue opened overnight must be able to
     * tell from the first line whether to rewrite a selector or just retry tomorrow.
     */
    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `ApkComboSelectors` needs " +
                    "updating and the matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **apkcombo is blocking us** (${e.kind}). This had never happened: check " +
                    "whether it has introduced anti-bot protection and reassess its tier and " +
                    "risk in the store table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **apkcombo is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: it is the canary asking too much, or " +
                    "another client from the same egress. Before touching the adapter, look at " +
                    "`permitsPerSecond`.",
            )
            // Until 03/09/2026 a 404 fell through to the catch-all below, which called it a
            // "network or site fault" — the one message in this class naming neither a job nor a
            // next step, for an error that is not a network fault at all. And with
            // `update_existing`, `canary.yml` reopens that same issue every night.
            //
            // Three producers, and on this store the **middle one is the interesting one**,
            // because apkcombo searches by substring: a query returning nothing is rare here, so a
            // 404 is far more likely to be a listing than a bad search term.
            StoreError.NotFound -> error(
                "$what: **404, and on this store that is not a network fault.** Three causes, " +
                    "and which to check depends on `$what`. (1) **The listing is gone or has " +
                    "moved**: refs are `{slug}/{package}`, and apkcombo answers **301** for a " +
                    "non-canonical slug — which the adapter follows — so a 404 means the app has " +
                    "left the store rather than been renamed. Re-anchor the constant. (2) **The " +
                    "page contradicted itself about the package**: `ApkComboDetailParser` " +
                    "returns this error, and not a `ParseFailure`, when the package declared on " +
                    "the page is not the one the ref asked for — a deliberate refusal, since the " +
                    "ref's second segment *is* the package on this store and a mismatch would " +
                    "mean serving another app's file. That one is not repaired by re-anchoring: " +
                    "check the listing by hand first. (3) **On `download`: no variant was " +
                    "left to choose.** The adapter returns this when the variants list is empty " +
                    "after parsing, which is the vicious-circle shape already documented for " +
                    "`com.iMe.android` — and the fallback that saves it reads the version list " +
                    "off the very page that had no `/r2?` anchors, so if that fallback stopped " +
                    "working this is what it would look like.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        /** A feed is a window: how wide it is depends on how much the store published. */
        const val MIN_FEED_ITEMS = 10

        const val QUERY = "telegram"
        const val APP_PATH = "telegram/org.telegram.messenger"
        const val PACKAGE_NAME = "org.telegram.messenger"

        /**
         * Several variants sharing **one version code**: the test above says why that shape is the
         * point, and why it belongs to the store rather than to us.
         *
         * Measured 31/08/2026, canonical slug — no redirect. The page carries **three**
         * `a.variant` anchors and the adapter keeps **two**: one `apk` and one `xapk`, both
         * version code 145505446, from `#variants-tab`. The third is the `#best-variant-tab`
         * duplicate of the recommended file, which `downloadBestTab` scopes out — so a count read
         * off the raw HTML is one higher than the version list, and that is not a dropped variant.
         *
         * Two is therefore the premise's exact margin: should spotify drop to a single artifact,
         * the test says so in those words rather than failing on a bare count.
         *
         * It is deliberately **not** [APP_PATH]: keeping the two anchors on different apps means
         * one app changing shape reddens one test, with a message about that app, not three.
         */
        const val MULTI_VARIANT_PATH = "spotify/com.spotify.music"
    }
}
