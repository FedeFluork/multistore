package com.multistore.store.apkmody

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
 * apkmody for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * Unit tests never touch the network. This is the only one in the module that does, and it is
 * excluded from `test`. Run it with `./gradlew :store:apkmody:canaryTest`.
 *
 * A canary's value is in its message: "apkmody is unreachable", "it changed markup" and "it is rate
 * limiting us" lead to three different jobs, and a bare success assertion does not tell them apart.
 */
@Tag("canary")
@DisplayName("Canary — apkmody (real network)")
class ApkModyCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var config: ApkModyConfig
    private lateinit var apkmody: ApkModyStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("apkmody-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        config = ApkModyConfig()
        apkmody = ApkModyStoreAdapter(config = config, clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still returns results`() = runTest {
        val page = apkmody.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        assertThat(page.items.first().title).isNotEmpty()
        // The content kind comes from the path's first segment: were it to disappear, so would the
        // only app/game distinction this store publishes.
        assertThat(page.items.any { it.contentKind != com.multistore.core.model.ContentKind.UNKNOWN }).isTrue()
    }

    @Test
    fun `the listing still exposes the packageName`() = runTest {
        val detail = apkmody.getAppDetails(StoreAppRef(APP_PATH)).orFail("detail")

        assertThat(detail.summary.title).isNotEmpty()
        // **It is the field holding verification up on this store.** apkmody redistributes
        // modified APKs: no hash, no original signature to compare against. The `packageName` match
        // is the only pre-install step that can still say no here, and without this table row it is
        // gone.
        //
        // So the invariant is that the field is **populated**, and that is what is asserted first.
        // The equality underneath is a **premise** about apkmody's catalogue and is kept apart from
        // it deliberately: the declared id of a repackaged build is authored by whoever uploaded
        // it, so it is theirs to change, and a rename would otherwise redden this check under a
        // message about pre-install verification having been lost — which would be false, since
        // the field would still be there doing its job.
        assertThat(detail.summary.packageName).isNotNull()
        if (detail.summary.packageName != PACKAGE_NAME) {
            abort<Nothing>(
                "detail: **the premise expired, and the invariant held.** The package is " +
                    "populated — which is the part that matters, and the only pre-install " +
                    "control this store leaves — but it now reads " +
                    "`${detail.summary.packageName}` instead of `$PACKAGE_NAME`. On a store that " +
                    "redistributes modified builds the declared id belongs to the uploader, so " +
                    "this is theirs to change, not a parser fault. Re-anchor `PACKAGE_NAME` (and " +
                    "`APP_PATH` if the listing moved too) after checking the listing by hand.",
            )
        }
        assertThat(detail.versions).isNotEmpty()
    }

    @Test
    fun `the history still carries the current version's version code`() = runTest {
        val versions = apkmody.getVersions(StoreAppRef(APP_PATH)).orFail("cronologia")

        assertThat(versions).isNotEmpty()
        assertThat(versions.map { it.ref }.toSet()).hasSize(versions.size)
        // The version code lives **only** inside the file name. If apkmody changed its naming
        // scheme, the anti-downgrade rule on this store would lose its point of comparison — and
        // no other part of the page would notice.
        assertThat(versions.mapNotNull { it.versionCode }).isNotEmpty()
    }

    @Test
    fun `the download still points at the CDN and not at the advert`() = runTest {
        val resolution = apkmody.getDownloadLink(StoreAppRef(APP_PATH)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("apkmody declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        assertThat(java.net.URI(direct.url).host).isEqualTo(ApkModyConfig.DEFAULT_DOWNLOAD_HOST)
        // Next to the real file, in the same list and with the same markup, sits apkmody's own
        // installer. If the host filter fell away, the download would offer that.
        //
        // **Two separate claims used to be pinned by one line.** `/packages/{pkg}/` is *our*
        // convention — `packageNameFromDownloadUrl` reads the package back out of that segment, so
        // the shape is an invariant we depend on — while *which* package sits in it is apkmody's
        // to change. Only the first belongs in an assertion; conflating them made a re-upload
        // under a different id look like the host filter having fallen away.
        assertThat(direct.url).contains("/packages/")
        assertThat(ApkModyRefs.packageNameFromDownloadUrl(direct.url)).isNotNull()
    }

    @Test
    fun `the chart still exists, and comes from the structured-data list`() = runTest {
        val page = apkmody.getTrending().orFail("classifica")

        assertThat(page.items).isNotEmpty()
        // The SEO suffix belongs to the page, not to the app: were it kept, every entry on Home
        // would have a title that does not match its own listing's.
        //
        // **Case-insensitively, because the parser is.** `stripSuffix` runs `IGNORE_CASE` and the
        // live page mixes casings, so the exact-case check that stood here would have let a
        // surviving "mod apk" through unnoticed — green while the defect it names was present.
        // Note what this can and cannot catch: `stripSuffix` is anchored at the end, so a single
        // suffix is always removed and only a **doubled** one can reach here. That makes this a
        // cheap guard rather than a likely red, which is why it is not hedged any further.
        //
        // And the mirror hazard, written down because nothing here can see it: a real app name ending
        // in "Mod APK" would be amputated by the same anchored strip, exactly as apkcombo's
        // `FEED_PREFIX` would amputate a name beginning with a bracket. Nothing left over, nothing
        // to count, no assertion possible.
        val surviving = page.items.map { it.title }
            .filter { it.endsWith(SEO_SUFFIX, ignoreCase = true) }
        assertThat(surviving).isEmpty()
    }

    private suspend fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). `ApkModySelectors` needs " +
                    "updating and the matching fixture recapturing.",
            )
            is StoreError.Blocked -> error(
                "$what: **apkmody is blocking us** (${e.kind}). This had never happened: check " +
                    "whether it has introduced anti-bot protection and reassess its tier and " +
                    "risk in the store table.",
            )
            is StoreError.RateLimited -> error(
                "$what: **apkmody is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: before touching the adapter, look at " +
                    "`permitsPerSecond`.",
            )
            // `StoreError.NotFound` has **five** producers on this adapter and the sentence that
            // stood here named one and a half of them, sending every reader to "pick another app".
            // Three of the five are a one-field edit in `ApkModyConfig` instead. See
            // [ApkModyRootDiagnosis] for the census, and note the extra question asked first:
            // apkmody's history is one of **domains moving**, and its own `healthCheck` cannot see
            // that because `.map { }` discards the URL it resolved to.
            StoreError.NotFound -> {
                val resolved = fetcher().resolveRedirect(ApkModyConfig.DEFAULT_BASE_URL)
                val landedOn = (resolved as? StoreResult.Success)?.value?.url?.let { hostOf(it) }
                val expected = hostOf(ApkModyConfig.DEFAULT_BASE_URL)
                error(
                    when (apkModyRootReading(landedOn, expected)) {
                        RootReading.MOVED_AWAY ->
                            "$what: **404, and apkmody's root now answers from `$landedOn`.** " +
                                "This is the event this store's history says to expect and it is " +
                                "the whole explanation: `apkmody.com` already redirects deep " +
                                "paths to `wokogames.com` and `.fun` to an IPTV site, and " +
                                "`.mobi` was the last one standing. **Do not touch a selector " +
                                "and do not pick another app.** Verify by hand what `$landedOn` " +
                                "actually serves before trusting it — a parked domain and a " +
                                "moved store look identical from here — then move " +
                                "`ApkModyConfig.DEFAULT_BASE_URL`, re-check the blocklist note " +
                                "in the store table, and confirm `downloadHost` still resolves."

                        RootReading.OWN_HOST ->
                            "$what: **404, and the root still answers on `$expected`.** So this " +
                                "is one address, and which one depends on the check. For " +
                                "`download`: the likeliest cause is that the page carried no " +
                                "link on `${ApkModyConfig.DEFAULT_DOWNLOAD_HOST}` — " +
                                "`ApkModyDownloadParser` goes through `parseHtmlOrNotFound`, so " +
                                "a moved CDN arrives as this error and **not** as a " +
                                "`ParseFailure`, and the repair is the one field " +
                                "`ApkModyConfig.downloadHost`. For `classifica`: `/popular` has " +
                                "been renamed — `POPULAR_SEGMENT` — and no listing is involved " +
                                "at all. For `detail`, `cronologia` or `download` on a listing: " +
                                "either `$APP_PATH` is gone, or the listing and the file " +
                                "contradict each other on the package and the adapter is " +
                                "refusing the substitution on purpose. Opening `$APP_PATH` by " +
                                "hand separates the last two, and only the last two."

                        RootReading.NO_ANSWER ->
                            "$what: **404, and the root did not answer either** " +
                                "(${(resolved as? StoreResult.Failure)?.error ?: "no host readable"}). " +
                                "Read the root's own answer first and treat this line as its " +
                                "symptom. Note this is **not** the uptodown case: apkmody has no " +
                                "measured egress refusal, so nothing here is skipped and the " +
                                "possibility that the domain has gone for good stays on the table."
                    },
                )
            }
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    /**
     * The adapter's own client, for the root probe the adapter does not expose usefully.
     *
     * Built from [ApkModyConfig]'s User-Agent and rate limit, because a canary measuring with a
     * second client would be measuring that client.
     */
    private fun fetcher(): PageFetcher = PageFetcher(
        clients.forStore(
            StoreId.APKMODY,
            StoreNetworkProfile(
                userAgent = config.userAgent,
                permitsPerSecond = config.permitsPerSecond,
                burst = config.burst,
            ),
        ),
    )

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    private companion object {
        const val QUERY = "spotify"
        const val APP_PATH = "apps/spotify-pro"
        const val PACKAGE_NAME = "com.spotify.music"

        /** The page's SEO tail, which must not survive into a title. */
        const val SEO_SUFFIX = "Mod APK"
    }
}
