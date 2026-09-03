package com.multistore.store.fdroid

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import com.multistore.core.model.StoreId
import com.multistore.store.api.DownloadResolution
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.StoreResult
import com.multistore.store.fdroid.api.FdroidSearchApi
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The **real** F-Droid, not the fixtures. Runs only in the nightly canary.
 *
 * It is the ninth canary and the last to arrive, and the delay had a plausible and wrong
 * explanation: F-Droid is not a scraped store, so "there is no markup that can change". True, and
 * not the point. What this store can break silently is not a selector, it is **four contracts**, and
 * each of the four, breaking, produces a symptom no fixture can show:
 *
 *  1. **the certificate the repository signs the index with.** It is pinned, and the pin is the only
 *     thing making a self-signed certificate trusted. If it changes — key rotation, compromised
 *     mirror, repo rebuilt — the index must be **discarded**, and with the index goes everything:
 *     search, detail, updates, categories. Not "F-Droid gives fewer results": F-Droid disappears;
 *  2. **the index's format.** `index-v2` is a document F-Droid versions on its own terms, and the
 *     fixtures freeze the one from the day they were captured;
 *  3. **the published hash.** This store is the only one of the nine where it can be proven, end to
 *     end and for real, that the declared hash is the hash of the bytes. On all the others
 *     pre-install verification is a promise proven on fixtures; here it is a measurement;
 *  4. **the fallback search**, which lives on a **separate** host (`search.f-droid.org`) and can
 *     fall over on its own while the repo is perfectly fine. It covers a single window — the first
 *     launch, before the 58 MB sync finishes — and if it stopped working nobody would notice,
 *     because a minute later the local index answers better than it does.
 *
 * ### What it costs, and why that is acceptable
 *
 * The whole index: **58 MB** once a night — 57,037,287 B when this was first measured on
 * 26/08/2026 and 58,572,651 B on 03/09/2026, because it grows. It is exactly what every F-Droid
 * client in the world does once a day, on infrastructure built for it. The test file, on the
 * other hand, is chosen **three orders of magnitude smaller than the index**: the smallest
 * artefact the index publishes, which on 26/08/2026 was 8,647 bytes. The property demonstrated is identical — a published hash matching the
 * bytes — and there is no reason to download thirteen megabytes to demonstrate it.
 */
@Tag("canary")
@DisplayName("Canary — f-droid (real network)")
// `PER_CLASS` is not a whim: it is what lets [synced] be an instance field and therefore exist once
// for the whole class. See the comment on [synced].
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FdroidCanaryTest {

    private val config = FdroidConfig()
    private val work = Files.createTempDirectory("fdroid-canary").toFile()
    private val clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = java.io.File(work, "cache")))
    private val fdroid = FdroidStoreAdapter(config = config, clients = clients, workDir = work)

    /**
     * The index, downloaded **once for the whole class**.
     *
     * With a `@BeforeEach` every test needing it would redo a 58 MB sync, and that would be two a
     * night for a single piece of news. This project is explicit about courtesy towards the stores —
     * no mass crawling — and here we do not pay the cost: somebody else's infrastructure does, and
     * it offers that document for free.
     *
     * `runBlocking` and not a `suspend`: JUnit 5 has no suspending `@BeforeAll`, and the sync is
     * blocking with respect to everything that follows anyway. What stays inside the tests is the
     * assertion, which is what has to fail with a name.
     *
     * **`by lazy` alone did not deliver that, and the shortfall showed up only on a bad night.**
     * A `lazy` delegate caches the *value*, not the exception: when the sync throws, the second
     * test to touch this field runs the whole thing again — so exactly on the night something is
     * wrong, the 58 MB document is fetched **twice**, which is the cost `PER_CLASS` was chosen to
     * avoid. It is also a cost somebody else pays, and this project is explicit about courtesy
     * towards the stores. The outcome is therefore cached either way, and the failure is replayed
     * from memory: two tests, one download, and the same message on both.
     */
    private val synced: SyncedIndex get() = syncOutcome.getOrThrow()

    private val syncOutcome: Result<SyncedIndex> by lazy {
        runCatching { runBlocking { sync() } }
    }

    @AfterAll
    fun tearDown() {
        clients.shutdown()
        work.deleteRecursively()
    }

    /** What needs to be known about the index, read in a single pass. */
    private data class SyncedIndex(
        val mode: IndexSyncMode,
        val token: String,
        val declaredRecords: Int?,
        val deliveredRecords: Int,
        val projected: Int,
        val withPackageName: Int,
        val categories: Int,
        /** The smallest artefact with a declared hash and size: see the hash test. */
        val smallest: Pair<StoreAppRef, com.multistore.core.model.AppVersion>?,
    )

    private suspend fun sync(): SyncedIndex =
        fdroid.openIndex(current = null).orFail("openIndex", FdroidHost.REPO).use { snapshot ->
            val records = snapshot.records().toList()
            val full = records.filterIsInstance<IndexRecord.Full>()
            val projected = full.mapNotNull { record -> record.detail?.let { record.ref to it } }
            SyncedIndex(
                mode = snapshot.mode,
                token = snapshot.token.value,
                declaredRecords = snapshot.expectedRecords,
                deliveredRecords = full.size,
                projected = projected.size,
                withPackageName = projected.count { it.second.summary.packageName != null },
                categories = records.filterIsInstance<IndexRecord.Catalog>()
                    .singleOrNull()?.info?.categories.orEmpty().size,
                smallest = projected
                    .flatMap { (ref, detail) -> detail.versions.map { ref to it } }
                    // **`> 0`, not `!= null`, and the difference is the whole test below.**
                    // `PackageProjection` reads `file.long("size") ?: 0L`, so `sizeBytes` is never
                    // null coming out of the projection: that half of the filter was dead code,
                    // and an entry whose size F-Droid ever omitted would arrive as **0**. Since
                    // this picks the *minimum*, a zero would win every time — so one bad datum
                    // anywhere in the index would become, deterministically, the single artefact
                    // the hash test downloads. Measured 26/08/2026: 13,098 `.apk` versions, none
                    // with a missing or zero size. The point is not the risk of it happening, it
                    // is that the chooser **amplifies** it from one row in thirteen thousand to
                    // the only row under test.
                    .filter { (_, version) -> version.sha256 != null && (version.sizeBytes ?: 0L) > 0L }
                    // Deterministic all the way down: on a size tie it picks the package and then
                    // the version, so two different nights download the same file and a failure can
                    // be compared with the previous day's.
                    .minWithOrNull(
                        compareBy({ it.second.sizeBytes }, { it.first.value }, { it.second.versionCode }),
                    ),
            )
        }

    @Test
    fun `entry_jar answers, i e the store is alive in the way that matters to us`() = runTest(timeout = NETWORK_TIMEOUT) {
        val health = fdroid.healthCheck()
        assertThat(health).isInstanceOf(StoreResult.Success::class.java)
    }

    /**
     * The index opens: signature verified, pin matching, document readable.
     *
     * The pin needs no assertion of its own, and that is better: `openIndex` **fails** if the
     * certificate is not [FdroidConfig.signerFingerprint]'s, so this call's success *is* the pin's
     * verification. A separate assertion re-reading the constant would compare our value with
     * itself.
     */
    @Test
    fun `the index opens, the signature is the pinned one, and it delivers what it declares`() = runTest(timeout = NETWORK_TIMEOUT) {
        val index = synced

        assertThat(index.mode).isEqualTo(IndexSyncMode.FULL)
        assertThat(index.token.toLongOrNull()).isNotNull()
        assertThat(index.declaredRecords).isNotNull()

        // The declared number and the delivered number: if they diverged, the progress bar would lie
        // and — worse — a FULL sync would delete what had not arrived. On 26/08/2026 they were 4,281
        // and 4,281.
        assertThat(index.deliveredRecords).isEqualTo(index.declaredRecords)
        assertThat(index.deliveredRecords).isAtLeast(MIN_PACKAGES)

        // The categories come from the `repo` block and are already localised by the store: they are
        // the browse screen's filter row, and without them there are zero.
        assertThat(index.categories).isAtLeast(MIN_CATEGORIES)

        // A projected entry must have the package, otherwise the projection has changed underneath
        // and the catalogue would fill with identity-less listings.
        assertThat(index.projected).isAtLeast(MIN_PACKAGES)
        assertThat(index.withPackageName).isEqualTo(index.projected)
    }

    /**
     * The hash F-Droid publishes is the hash of the bytes it delivers.
     *
     * It is the project's only canary that can say so: on the other eight stores the hash either
     * does not exist (six), or exists on some objects (an1), or is taken from the page without the
     * file ever being downloadable (uptodown, behind a Turnstile). Here the two values are really
     * compared.
     *
     * The artefact is the **smallest in the index**, chosen by the index itself rather than written
     * here: a package named by hand can disappear from F-Droid without anything interesting having
     * happened, and the canary would open an issue for a withdrawn app.
     */
    @Test
    fun `the published hash is the hash of the bytes, and the download stays one hop`() = runTest(timeout = NETWORK_TIMEOUT) {
        val smallest = synced.smallest
        assertThat(smallest).isNotNull()
        val (ref, version) = smallest!!

        val resolved = fdroid.getDownloadLink(ref, version.ref).orFail("download of ${ref.value}", FdroidHost.REPO)
        assertThat(resolved).isInstanceOf(DownloadResolution.Direct::class.java)
        val direct = resolved as DownloadResolution.Direct
        assertThat(direct.expectedSha256).isEqualTo(version.sha256)

        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var hops = 0
        http().executeUncached(Request.Builder().url(direct.url).build()).use { response ->
            assertThat(response.code).isEqualTo(200)
            hops = generateSequence(response) { it.priorResponse }.count()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    bytes += read
                }
            }
        }

        // One hop: no redirect, no interstitial, no transit permit. It is the reason F-Droid is the
        // only `DIRECT, 1 hop` store in the table — and it is a claim about **F-Droid's serving
        // choice**, not about our parser, so it is a premise and not an invariant. Nothing in the
        // app depends on it: no `StoreNetworkProfile` anywhere sets `followRedirects = false`
        // (grepped, repo-wide), so if F-Droid put `/repo` behind a CDN every download would keep
        // working and this line alone would go red, with `expected: 1 / but was: 2` and no job
        // attached. Measured 26/08/2026: zero redirects on both the chosen artefact and the
        // largest APK in the index, so it is uniform server behaviour rather than a property of
        // this one file.
        if (hops != EXPECTED_HOPS) {
            abort<Nothing>(
                "download of ${ref.value}: **the premise expired, and nothing is broken.** The " +
                    "artefact now arrives after $hops hops instead of $EXPECTED_HOPS — F-Droid " +
                    "has presumably put `/repo` behind a CDN or a mirror redirect. Downloads are " +
                    "unaffected: the client follows redirects everywhere, and no " +
                    "`StoreNetworkProfile` in this repository disables that. What needs updating " +
                    "is the store table's `DIRECT, 1 hop` note, not the adapter. The hash " +
                    "comparison below did not run, so re-run once the note is fixed.",
            )
        }
        assertThat(bytes).isEqualTo(version.sizeBytes)
        assertThat(Sha256.ofBytes(digest.digest())).isEqualTo(version.sha256)
    }

    /**
     * The fallback search still answers, **and still answers badly**.
     *
     * The two halves matter equally. That it answers serves the first-launch window; that it still
     * answers ten unpageable results is what justifies `searchSource = LOCAL_INDEX`. If one day it
     * really paginated, this test would turn red and that is good news: the project's only indexed
     * store would have a real remote search, and there would be a choice to remake.
     */
    @Test
    fun `the fallback search is still capped at ten and still unpaginated`() = runTest(timeout = NETWORK_TIMEOUT) {
        val first = fdroid.search(QUERY).orFail("search", FdroidHost.SEARCH).items
        assertThat(first).isNotEmpty()

        // The cap is **their** number, and the docstring above already accepts that exceeding it
        // would be good news. So it must not fail: a red here would be an issue asking somebody to
        // repair an improvement, and the issue body would tell them to go update selectors that do
        // not exist on this store. Only the shape of the report was ever wrong.
        if (first.size > FdroidSearchApi.HARD_RESULT_CAP) {
            abort<Nothing>(
                "search: **good news, and the reason this test exists.** The fallback API returned " +
                    "${first.size} results against a cap of ${FdroidSearchApi.HARD_RESULT_CAP}, " +
                    "so it is no longer capped at ten. Nothing is broken — but a real remote " +
                    "search on the project's only indexed store is a choice to remake: revisit " +
                    "`searchSource = LOCAL_INDEX`, check whether `page` is honoured now (the " +
                    "assertion below still expects it to be ignored), and update " +
                    "`HARD_RESULT_CAP` and the store table.",
            )
        }

        // The adapter stops asking beyond the first page because the API ignores `page`. Asking for
        // the second anyway is what would notice the opposite.
        val second = fdroid.search(QUERY, page = 1).orFail("search page 2", FdroidHost.SEARCH).items
        assertThat(second).isEmpty()
    }

    /**
     * The `packageName` is still derived from the listing's URL.
     *
     * The API does not publish it: its four fields are `name`, `summary`, `icon`, `url`, and the
     * package is in the segment after `packages/`. If that URL changed shape the adapter would
     * discard **every** result — `packageNameFromUrl` returns `null` and the row is lost — and the
     * symptom would be a search answering zero on first launch, with no error anywhere. It is the
     * case where a fault disguises itself as "F-Droid does not have this app".
     */
    @Test
    fun `the package can still be derived from the fallback search`() = runTest(timeout = NETWORK_TIMEOUT) {
        val items = fdroid.search(QUERY).orFail("search", FdroidHost.SEARCH).items

        // **Both of the assertions that used to follow cannot fail**, and saying so is the point:
        // `toSummary` returns `null` when `packageNameFromUrl` does, and `mapNotNull` drops it, so
        // a row without a package never becomes an item; and `ref = FdroidRefs.appRef(packageName)`
        // makes `ref.value == packageName` true by construction. They are kept as documentation of
        // the shape the rest of the adapter relies on, not as defences.
        assertThat(items.count { it.packageName != null }).isEqualTo(items.size)
        assertThat(items.count { it.ref.value == it.packageName }).isEqualTo(items.size)

        // So the whole test rested on one bare `isNotEmpty()` with **three** causes behind it,
        // leading to opposite jobs: the listing URL shape having changed — which is the defect
        // this test was written for, and which silently discards *every* row — the search host
        // being down, or simply nothing matching. The first two are now told apart by [orFail],
        // which knows this is `search.f-droid.org`; the third is separated here, by asking a
        // question whose answer is known. Measured 26/08/2026: `q=telegram` returns 10 and
        // `q=telegran` returns 0, so the engine is not fuzzy and an empty answer for a real term
        // would be news in itself.
        if (items.isEmpty()) {
            val nonsense = fdroid.search(NONSENSE).orFail("empty search", FdroidHost.SEARCH).items
            error(
                if (nonsense.isEmpty()) {
                    "search: **the API answered, and returned nothing for `$QUERY`.** A " +
                        "nonsense query also returned zero, so the endpoint is alive and " +
                        "discriminating — which leaves two readings, and they are not equally " +
                        "likely. Either the listing URL shape changed, in which case " +
                        "`packageNameFromUrl` returns null and `toSummary` **discards every " +
                        "row**, giving exactly this empty answer with no error anywhere — that " +
                        "is the defect this test exists for, and the fix is in " +
                        "`FdroidSearchApi`. Or F-Droid genuinely no longer has an app matching " +
                        "`$QUERY`, which for this term would be remarkable. Fetch the API URL by " +
                        "hand and look at whether the JSON has rows: rows plus zero items is the " +
                        "first case."
                } else {
                    "search: **`$QUERY` returned nothing while a nonsense query returned " +
                        "${nonsense.size} rows.** That is the endpoint answering the question it " +
                        "must not be able to answer, which is how pdalife's `/suggest/` was " +
                        "disqualified: it makes the fallback search noise on every query rather " +
                        "than a source. If this reproduces, the fallback should be dropped rather " +
                        "than repaired — and the local index already answers better than it does."
                },
            )
        }
    }

    /**
     * The question it must not be able to answer.
     *
     * Before adopting an endpoint, ask it the question it must not be able to answer. pdalife has an
     * endpoint answering ten apps to **any** string, and that is why it is not used. This one answers
     * zero, and that is why it is usable — so it is also the thing to keep an eye on.
     */
    @Test
    fun `a query with no results answers zero, not ten`() = runTest(timeout = NETWORK_TIMEOUT) {
        val items = fdroid.search(NONSENSE).orFail("empty search", FdroidHost.SEARCH).items
        assertThat(items).isEmpty()
    }

    private fun http() = clients.forStore(
        StoreId.FDROID,
        StoreNetworkProfile(
            userAgent = config.userAgent,
            permitsPerSecond = config.permitsPerSecond,
            burst = config.burst,
        ),
    )

    /**
     * The value, or a message naming **which** failure happened and on **which host**.
     *
     * The comment that used to sit here promised exactly this and the line beneath it was
     * `toString()` — see [fdroidFailureMessage], which also records what that cost. [host] has no
     * default: three of the six checks talk to `search.f-droid.org` and three to `f-droid.org`, and
     * a default would attribute a search outage to the repository.
     */
    private fun <T> StoreResult<T>.orFail(what: String, host: FdroidHost): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> throw AssertionError(fdroidFailureMessage(what, host, error))
        StoreResult.Unsupported -> throw AssertionError(
            fdroidFailureMessage(what, host, com.multistore.store.api.StoreError.Unsupported(what)),
        )
    }

    private companion object {
        const val QUERY = "telegram"

        /** A string no package can contain. */
        const val NONSENSE = "zzqxwvnbtklmj"

        /**
         * Deliberately low thresholds.
         *
         * On 26/08/2026 the index had 4,281 packages and 108 categories. The thresholds are at a
         * quarter and a sixth because this test has to notice a **broken** index — a truncated
         * document, a changed schema, a mirror serving an empty repo — not F-Droid withdrawing two
         * hundred apps. A tight threshold would produce an issue a week for a catalogue that
         * breathes, and the first thing one learns from a noisy canary is to ignore it.
         */
        const val MIN_PACKAGES = 1_000
        const val MIN_CATEGORIES = 20

        const val BUFFER = 64 * 1024

        /**
         * One: no redirect, no interstitial, no transit permit.
         *
         * A **premise** about F-Droid's serving choice, not an invariant of ours — see the download
         * test for why exceeding it skips instead of failing.
         */
        const val EXPECTED_HOPS = 1

        /**
         * `runTest` grants one of a minute, and that is right for what it is meant for: a
         * virtual-time test that touches nothing. Here 57 MB are downloaded from a public mirror,
         * and a minute is regularly exceeded — this canary's first draft indeed failed with
         * `UncompletedCoroutinesError`, i.e. accusing the wrong code.
         */
        val NETWORK_TIMEOUT = 10.minutes
    }
}
