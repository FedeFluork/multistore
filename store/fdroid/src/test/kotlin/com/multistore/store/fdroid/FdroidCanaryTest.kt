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
 *     launch, before the 57 MB sync finishes — and if it stopped working nobody would notice,
 *     because a minute later the local index answers better than it does.
 *
 * ### What it costs, and why that is acceptable
 *
 * The whole index: 57 MB once a night. It is exactly what every F-Droid client in the world does
 * once a day, on infrastructure built for it. The test file, on the other hand, is chosen **three
 * orders of magnitude smaller than the index**: the smallest artefact the index publishes, which on
 * 26/08/2026 was 8,647 bytes. The property demonstrated is identical — a published hash matching the
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
     * With a `@BeforeEach` every test needing it would redo a 57 MB sync, and that would be two a
     * night for a single piece of news. This project is explicit about courtesy towards the stores —
     * no mass crawling — and here we do not pay the cost: somebody else's infrastructure does, and
     * it offers that document for free.
     *
     * `runBlocking` and not a `suspend`: JUnit 5 has no suspending `@BeforeAll`, and the sync is
     * blocking with respect to everything that follows anyway. What stays inside the tests is the
     * assertion, which is what has to fail with a name.
     */
    private val synced: SyncedIndex by lazy { runBlocking { sync() } }

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
        fdroid.openIndex(current = null).orFail("openIndex").use { snapshot ->
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
                    .filter { (_, version) -> version.sha256 != null && version.sizeBytes != null }
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

        val resolved = fdroid.getDownloadLink(ref, version.ref).orFail("download of ${ref.value}")
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
        // only `DIRECT, 1 hop` store in the table.
        assertThat(hops).isEqualTo(1)
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
        val first = fdroid.search(QUERY).orFail("search").items
        assertThat(first).isNotEmpty()
        assertThat(first.size).isAtMost(FdroidSearchApi.HARD_RESULT_CAP)

        // The adapter stops asking beyond the first page because the API ignores `page`. Asking for
        // the second anyway is what would notice the opposite.
        val second = fdroid.search(QUERY, page = 1).orFail("search page 2").items
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
        val items = fdroid.search(QUERY).orFail("search").items
        assertThat(items).isNotEmpty()
        assertThat(items.count { it.packageName != null }).isEqualTo(items.size)
        // The ref **is** the packageName on this store, and the listing opens from it.
        assertThat(items.count { it.ref.value == it.packageName }).isEqualTo(items.size)
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
        val items = fdroid.search(NONSENSE).orFail("empty search").items
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

    private fun <T> StoreResult<T>.orFail(what: String): T = when (this) {
        is StoreResult.Success -> value
        // The message separates the three cases that lead to three different jobs, and it is the
        // first line whoever opens the issue will read: see `.github/workflows/canary.yml`.
        is StoreResult.Failure -> throw AssertionError("$what: F-Droid answered $error")
        StoreResult.Unsupported -> throw AssertionError("$what: the adapter declares it unsupported")
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
         * `runTest` grants one of a minute, and that is right for what it is meant for: a
         * virtual-time test that touches nothing. Here 57 MB are downloaded from a public mirror,
         * and a minute is regularly exceeded — this canary's first draft indeed failed with
         * `UncompletedCoroutinesError`, i.e. accusing the wrong code.
         */
        val NETWORK_TIMEOUT = 10.minutes
    }
}
