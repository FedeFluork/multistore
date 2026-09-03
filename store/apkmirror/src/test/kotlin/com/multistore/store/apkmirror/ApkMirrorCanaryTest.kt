package com.multistore.store.apkmirror

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
 * apkmirror for **real**, not the fixtures. Runs only in the nightly canary.
 *
 * On this store the canary is worth more than elsewhere, and not because of the markup: the
 * conditions that make it work are **Cloudflare's** decisions, not apkmirror's. Which User-Agent is
 * allowed, and which combination of TLS and protocol passes without a challenge, can change from
 * one day to the next without a line of HTML moving — i.e. the case no fixture can catch, by
 * definition.
 *
 * **It was this canary that disproved the protocol finding**: the curl measurement said "HTTP/1.1
 * is needed", with OkHttp the opposite holds. See the note atop `ApkMirrorConfig`.
 *
 * ### What a red here does **not** mean, and why this class has no `abort`
 *
 * uptodown's canary can skip itself: when the whole egress is refused it receives a 404 on every
 * address, and asking the language root separates "one address moved" from "this network cannot
 * reach the store". That remedy does not port here, and the reason is measured rather than
 * assumed: **apkmirror's refusal wears a 403** — `cf-mitigated: challenge` — which arrives as
 * [StoreError.Blocked] and already has a branch of its own below. A 404 from this store therefore
 * means what a 404 is supposed to mean, so aborting on one would silence the single cause it does
 * indicate: a re-slugged listing, or an app that has left the store.
 */
@Tag("canary")
@DisplayName("Canary — apkmirror (real network)")
class ApkMirrorCanaryTest {

    private lateinit var clients: StoreHttpClients
    private lateinit var apkmirror: ApkMirrorStoreAdapter

    @BeforeEach
    fun setUp() {
        val work = Files.createTempDirectory("apkmirror-canary").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
        apkmirror = ApkMirrorStoreAdapter(config = ApkMirrorConfig(), clients = clients)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
    }

    @Test
    fun `search still gets through, i e UA and protocol are still fine`() = runTest {
        val page = apkmirror.search(QUERY).orFail("search")

        assertThat(page.items).isNotEmpty()
        page.items.forEach { assertThat(it.title).isNotEmpty() }
    }

    /**
     * The four-request chain holds and the download modal is still readable.
     *
     * The file hash and the certificate are why this store is worth having: without them apkmirror
     * would drop to apkcombo's level and the verification card would say "hash not verified" on
     * every installation. But the two are **not** published on the same terms, and conflating them
     * is what made this check red on 03/09/2026 with nothing broken — see the note on the
     * conditional below.
     *
     * **The gap this leaves, stated so nobody believes it is covered.** If apkmirror renamed the
     * file-hash label *specifically* while a bundle-only release was the newest, nothing here would
     * notice: the conditional would not run and the certificate would still be found. What is
     * covered is the collapse of the mechanism — both hashes come from the same modal through the
     * same `sha256After(modal, label)` helper, so a lost modal or a broken lookup takes the
     * certificate down with it, and that is asserted unconditionally.
     */
    @Test
    fun `the listing-release-variant chain holds, and the download modal is still readable`() = runTest {
        val detail = apkmirror.getAppDetails(StoreAppRef(APP_PATH)).orFail("detail")

        assertThat(detail.summary.packageName).isEqualTo(PACKAGE_NAME)
        assertThat(detail.versions).isNotEmpty()
        assertThat(detail.versions.map { it.ref }.toSet()).hasSize(detail.versions.size)

        // Exactly one variant is hydrated, and this is how it is recognised from the outside:
        // `sizeBytes` is only ever copied from the variant's own page — `toAppVersion` reads it
        // from `hydratedFor` and from nowhere else — so a version carrying a size is a version
        // whose page was opened. Asserting it matters because **hydration is deliberately
        // tolerant**: `variantDetail(...).getOrNull()` discards `Blocked`, `RateLimited` and
        // `ParseFailure` alike, so a challenge or a 429 on the fourth request of a chain against a
        // store declaring `Crawl-delay: 3` would never reach [orFail]. Without this line the
        // symptom would be a bare Truth message naming none of the jobs.
        val hydrated = detail.versions.filter { it.sizeBytes != null }
        assertThat(hydrated).hasSize(1)
        val best = hydrated.single()
        assertThat(best.versionCode).isNotNull()

        // **The certificate fingerprint is the half that is always published**, bundles included,
        // and it comes from the same `#safeDownload .modal-body` through the same label-driven
        // helper as the file hash. So this one assertion is what says the modal was found and that
        // the lookup still works, and it says it on every release whatever the artefact is.
        assertThat(detail.preferredSignerSha256).isNotNull()

        // **The file hash is conditional, and the condition is the store's to choose.** apkmirror
        // publishes it on single APKs only: `providesHash = SOMETIMES` says exactly that, and
        // `ApkMirrorVariantParser`'s own note says the file-hash section is "present only on single
        // APKs — on a bundle the second is missing, and rightly: there is no single file to hash".
        // Requiring it unconditionally therefore forbade a shape the store legitimately publishes,
        // and it was not hypothetical: measured through this adapter on 03/09/2026, Firefox 155.0
        // came back as **one variant and an APKM**, so `versions.any { it.sha256 != null }` was
        // false and the nightly went red for an adapter that has installed that container
        // correctly since M5/5. Both assertions were bare Truth, so the issue carried the literal
        // text "expected to be true" and named no job at all.
        if (best.artifactType == ArtifactType.APK) {
            assertThat(best.sha256).isNotNull()
            // Both sections of that modal carry a `SHA-256:` followed by 64 hex characters, which
            // is why the parser finds them by label and not by position. Equal values are the
            // signature of that mix-up, and nothing else would catch it: each hash on its own
            // looks perfectly well-formed, and pre-install verification would spend every
            // download comparing a file against a key's fingerprint.
            assertThat(best.sha256).isNotEqualTo(detail.preferredSignerSha256)
        }
    }

    /**
     * The last hop reaches the download endpoint, with what that endpoint demands.
     *
     * **The hash is deliberately not asserted here, and that is a change of subject rather than a
     * relaxation.** It is a property of the variant page, and it is checked where the variant page
     * is the subject — above, where the artefact kind is visible and the conditional can be
     * honest. Here it cannot be: the resolution does not say whether the file is a bundle, because
     * `artifactType` is inferred from the resolved URL's file name and that name is
     * **`download.php`**. A PHP endpoint carries no suffix, so `artifactTypeOf` falls through to
     * its `APK` default and `fallbackFileName` — which would have known better — is never reached.
     * Measured 03/09/2026: the resolution said `artifactType=APK` for a release whose only variant
     * is an APKM. It is harmless in production, and only because the install path does not trust
     * it: `ContainerReader` decides by looking **inside** the file, which is the rule CLAUDE.md
     * states as "the extension decides what to show, the content decides what to do". It is
     * written down here because it makes `direct.artifactType` unusable as a signal on this store,
     * and a reader reaching for it would find a constant wearing the shape of a measurement.
     */
    @Test
    fun `the last hop still reaches the download endpoint`() = runTest {
        val resolution = apkmirror.getDownloadLink(StoreAppRef(APP_PATH)).orFail("download")

        val direct = resolution as? DownloadResolution.Direct
            ?: error("apkmirror declares DIRECT but returned ${resolution::class.simpleName}")
        assertThat(direct.url).startsWith("https://")
        // The chain has to end on apkmirror's own host. The adapter refuses a foreign one as a
        // hijack rather than following it, so the way this breaks is a `NotFound` and not a wrong
        // URL — see the branch in [orFail].
        assertThat(java.net.URI(direct.url).host).isEqualTo(ApkMirrorConfig.HOST)

        // **The Referer is not decoration: `download.php` refuses without it**, and the value has
        // to be the interstitial's own URL rather than the variant page's — the two keys differ,
        // which is why the interstitial is opened instead of composed. Nothing else in the suite
        // asserted this, and losing it would produce a resolution that looks perfectly good and a
        // download that 403s for every user, on the one store where a 403 is also what a block
        // looks like.
        assertThat(direct.headers[REFERER]).isNotNull()
        assertThat(direct.headers.getValue(REFERER)).startsWith("https://${ApkMirrorConfig.HOST}")

        // Size to the byte is this store's, and it comes from the variant page: null here means
        // that page stopped being readable, not that apkmirror stopped publishing it.
        assertThat(direct.expectedSize).isNotNull()
    }

    @Test
    fun `the latest-releases feed still exists`() = runTest {
        val page = apkmirror.getRecent().orFail("news feed")

        assertThat(page.items).isNotEmpty()
        // apkmirror is the only one of the four new-release sources that publishes the developer,
        // and that is what the inferred app key uses together with the title on a store that does
        // not give the package. If the title format changed, it would be lost silently.
        assertThat(page.items.count { it.developer != null }).isEqualTo(page.items.size)

        // **The version must not stay in the title** — it changes on every release, and to the
        // identity matcher that is a new app every time. This is the invariant the comment here
        // has always claimed, and until 03/09/2026 the line underneath it checked something else:
        // `filter { it.title.contains(" by ") }.isEmpty()`, which is issue #4 in another store's
        // clothing. The raw feed title is `{Name} {version} by {Dev}` and the parser splits on the
        // **last** occurrence, so a name containing " by " survives correctly — and
        // `ApkMirrorFeedParserTest` asserts that as a contract, with `"Words by Post"` kept whole.
        // The offline test therefore required exactly what the canary forbade, and a window
        // holding `Speedtest by Ookla` or `Sudoku by Brainium` would have reddened the nightly for
        // a parser doing what its own test demands. Intermittently, too: the feed is a rotating
        // ten-entry window.
        //
        // The dot guard is what keeps the replacement from becoming the shape it replaces. A
        // version with no separator is a bare token a real name could contain — an app called
        // "Office 365" at version "365" would redden this — whereas a dotted version is specific
        // enough that a collision is not a thing to design around. Measured through the adapter on
        // 03/09/2026: 10 entries of 10 carry a developer and a dotted version, none has its
        // version left in the title, and none contains " by " at all.
        val stillVersioned = page.items.filter { item ->
            val version = item.latestVersionName?.takeIf { it.contains('.') }
            version != null && item.title.contains(version)
        }
        assertThat(stillVersioned.map { it.title }).isEmpty()
    }

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        StoreResult.Unsupported -> error("$what: the adapter now declares it unsupported")
        is StoreResult.Failure -> when (val e = error) {
            is StoreError.ParseFailure -> error(
                "$what: **the markup has changed**. Selector with no match: " +
                    "'${e.selector}' (snippet ${e.snippetHash}). Update `ApkMirrorSelectors` " +
                    "and recapture the fixture.",
            )
            is StoreError.Blocked -> error(
                "$what: **blocked by Cloudflare** (${e.kind}). Retry by hand, in " +
                    "this order: (1) is the declared User-Agent still accepted? (2) does the " +
                    "same request pass with `curl`? (3) does it pass forcing HTTP/1.1? If none " +
                    "of the three, there is nothing to fix in the selectors: tier 2 (Cronet) or " +
                    "3 (WebView) of the escalation ladder is needed, that is a network engine " +
                    "that *is* a browser rather than resembling one.",
            )
            is StoreError.RateLimited -> error(
                "$what: **apkmirror is rate-limiting us** (429" +
                    (e.retryAfter?.let { r -> ", retry in $r" } ?: "") +
                    "). Not a fault and not a markup change: it is the canary asking too much, or " +
                    "another client from the same egress. Before touching the adapter, look at " +
                    "`permitsPerSecond`.",
            )
            // Until 03/09/2026 a 404 fell through to the catch-all below, which called it a
            // "network or site fault": the one message in this class naming neither a job nor a
            // next step, for the one error that is not a fault at all. And `canary.yml` reopens
            // the same issue nightly, so a miscategorised 404 is a standing invitation to go
            // rewrite selectors that never moved.
            //
            // Two producers, and they lead to opposite jobs. Note what this branch must **not**
            // do: abort. See the class note — apkmirror's egress refusal arrives as a 403, so
            // unlike uptodown a 404 here really is about one address.
            StoreError.NotFound -> error(
                "$what: **404, and on this store that is not a network fault.** Two causes, and " +
                    "the one to check first depends on `$what`. (1) **The address is gone**: for " +
                    "`detail`, `download` or `history` the listing `$APP_PATH` has been " +
                    "re-slugged or has left apkmirror — re-anchor the constant, do not touch " +
                    "`ApkMirrorSelectors`. (2) **The chain refused a foreign host**: the adapter " +
                    "returns this same error when the variant's download link, or the final URL " +
                    "after the interstitial, is not on `${ApkMirrorConfig.HOST}` — a deliberate " +
                    "refusal of a hijack, and it is what would happen if apkmirror moved the file " +
                    "to a CDN on another domain. That second one is **not** an app that " +
                    "disappeared and is not repaired by picking another reference app: it is " +
                    "`isOwnHost` needing to learn the new host. Opening the download page by " +
                    "hand tells the two apart in one look — a working green button means it is " +
                    "the second.",
            )
            else -> error("$what: network or site fault ($e). If it recurs, investigate.")
        }
    }

    private companion object {
        const val QUERY = "firefox"
        const val APP_PATH = "mozilla/firefox"
        const val PACKAGE_NAME = "org.mozilla.firefox"

        /** The header `download.php` demands; see the download test. */
        const val REFERER = "Referer"
    }
}
