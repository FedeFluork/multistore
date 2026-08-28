package com.multistore.store.fdroid.index

import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.Sha256
import com.multistore.core.network.http.NetworkEnvironment
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreIndexSnapshot
import com.multistore.store.api.StoreResult
import com.multistore.store.fdroid.FdroidConfig
import com.multistore.store.fdroid.FdroidStoreAdapter
import com.multistore.store.fdroid.FdroidTestServer
import com.multistore.store.fdroid.Fixtures
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The index's chain of trust, end to end, against a fake server.
 *
 * `JarSignatureVerifierTest` proves the signature on the files; here what surrounds it is proven:
 * that the hash declared in the signed document really is compared, that a tampered index never
 * reaches the parser, and that a mirror cannot roll the index back by serving an old but authentic
 * version.
 */
@DisplayName("Index client — hash, signature, anti-rollback")
class FdroidIndexClientTest {

    private lateinit var server: MockWebServer
    private lateinit var fake: FdroidTestServer
    private lateinit var clients: StoreHttpClients
    private lateinit var workDir: File
    private lateinit var config: FdroidConfig
    private lateinit var client: FdroidIndexClient
    private lateinit var adapter: FdroidStoreAdapter

    /** The timestamp of the real `entry.json` committed as a fixture. */
    private val entryTimestamp = 1_787_316_712_615L

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fake = FdroidTestServer(server)
        workDir = Files.createTempDirectory("fdroid-index").toFile()
        clients = StoreHttpClients(NetworkEnvironment(cacheDirectory = File(workDir, "cache")))
        config = FdroidConfig(baseUrl = fake.baseUrl, searchApiUrl = fake.searchApiUrl)
        client = FdroidIndexClient(
            config = config,
            http = clients.forStore(
                com.multistore.core.model.StoreId.FDROID,
                com.multistore.core.network.http.StoreNetworkProfile(userAgent = config.userAgent),
            ),
            workDir = workDir,
        )
        adapter = FdroidStoreAdapter(config = config, clients = clients, workDir = workDir)
    }

    @AfterEach
    fun tearDown() {
        clients.shutdown()
        server.close()
        workDir.deleteRecursively()
    }

    private fun sha256Of(file: File): Sha256 =
        Sha256.ofBytes(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

    private fun failure(result: StoreResult<*>): StoreError.ParseFailure {
        val error = (result as StoreResult.Failure).error
        assertThat(error).isInstanceOf(StoreError.ParseFailure::class.java)
        return error as StoreError.ParseFailure
    }

    private fun sliceEntryFile(sha: Sha256 = sha256Of(Fixtures.file(Fixtures.INDEX_SLICE))) = EntryFile(
        name = "/${Fixtures.INDEX_SLICE}",
        sha256 = sha.hex,
        size = Fixtures.file(Fixtures.INDEX_SLICE).length(),
        numPackages = 12,
    )

    // --- entry.jar ---------------------------------------------------------------------------

    @Test
    @DisplayName("the authentic entry.jar is verified and its content read")
    fun authenticEntryIsAccepted() = runTest {
        val result = client.fetchEntry()

        val entry = (result as StoreResult.Success).value
        assertThat(entry.timestamp).isEqualTo(entryTimestamp)
        assertThat(entry.diffs).hasSize(10)
    }

    @Test
    @DisplayName("an entry.jar signed by another key never reaches the parser")
    fun foreignlySignedEntryIsRejected() = runTest {
        fake.overrides["/repo/entry.jar"] = Fixtures.ENTRY_JAR_FOREIGN

        val result = client.fetchEntry()

        val error = (result as StoreResult.Failure).error
        assertThat(error).isInstanceOf(StoreError.ParseFailure::class.java)
        assertThat((error as StoreError.ParseFailure).selector).contains("signer")
    }

    @Test
    @DisplayName("a tampered entry.jar is rejected")
    fun tamperedEntryIsRejected() = runTest {
        fake.overrides["/repo/entry.jar"] = Fixtures.ENTRY_JAR_TAMPERED

        val error = (client.fetchEntry() as StoreResult.Failure).error

        assertThat(error).isInstanceOf(StoreError.ParseFailure::class.java)
    }

    @Test
    @DisplayName("an unreachable entry.jar is NotFound, not a crash")
    fun missingEntryIsNotFound() = runTest {
        fake.missing += "/repo/entry.jar"

        val error = (client.fetchEntry() as StoreResult.Failure).error

        assertThat(error).isEqualTo(StoreError.NotFound)
    }

    // --- Index verification -------------------------------------------------------------------

    @Test
    @DisplayName("the index with the right hash passes and is read")
    fun indexWithMatchingHashIsAccepted() = runTest {
        val result = client.download(sliceEntryFile())

        val download = (result as StoreResult.Success).value
        download.use {
            val text = it.source().use { source -> source.readUtf8() }
            assertThat(text).contains(Fixtures.PKG_FDROID)
        }
    }

    @Test
    @DisplayName("a single different bit and the index is discarded")
    fun indexWithWrongHashIsRejected() = runTest {
        val wrong = requireNotNull(Sha256.parseOrNull("cd".repeat(32)))

        val error = (client.download(sliceEntryFile(wrong)) as StoreResult.Failure).error

        // The hash comes from a signed document. If it does not match, either the mirror is serving
        // something else or the file was tampered with: in neither case do we look inside.
        assertThat(error).isInstanceOf(StoreError.ParseFailure::class.java)
        assertThat((error as StoreError.ParseFailure).selector).contains("sha256")
    }

    @Test
    @DisplayName("the hash is computed on the plaintext content, even when it arrives compressed")
    fun hashIsComputedOnTheDecompressedStream() = runTest {
        fake.gzipIndex = true

        val result = client.download(sliceEntryFile())

        // The real server compresses: 17.8 MB on the wire against 57 in plaintext. Computing the
        // digest on the compressed bytes would give a value that never matches, and the sync would
        // be impossible forever — with an error message pointing at the wrong place.
        assertThat(result).isInstanceOf(StoreResult.Success::class.java)
        (result as StoreResult.Success).value.use {
            assertThat(it.source().use { s -> s.readUtf8() }).contains(Fixtures.PKG_FDROID)
        }
    }

    @Test
    @DisplayName("the two caps are two distinct checks, and which one fires is visible")
    fun theTwoSizeCapsAreDistinct() = runTest {
        // `entry.json` is signed and declares how much the index weighs. Without a cap, `writeAll`
        // writes for as long as the server sends, and the SHA-256 check comes **afterwards**: a
        // broken or hostile mirror would fill the disk before anyone could say no.
        //
        // The caps are two, though, and cover different things, so one of them firing is not enough:
        //  - **on what is transferred**, which stops the disk write halfway;
        //  - **on what is decompressed**, the only one that sees a gzip bomb, because expansion
        //    happens after the wire — but which acts when the file is already all on disk.
        // Testing them together risks testing only one: here they are checked to be two different
        // paths by comparing the snippet each produces.
        fake.gzipIndex = false
        val onTheWire = failure(client.download(sliceEntryFile().copy(size = 1_024)))

        fake.gzipIndex = true
        // The compressed slice weighs ~63 KB and ~204 KB in plaintext: by declaring 70,000, the 63 KB
        // transferred stay under the first cap and only the second is left to stop the read.
        val whenExpanded = failure(client.download(sliceEntryFile().copy(size = 70_000)))

        // Deliberately different selectors: the selector is what ends up in `health_events`, and
        // "the transfer overran" has to be told from "the expanded document is larger than it
        // declares", which is the shape of a gzip bomb.
        assertThat(onTheWire.selector).isEqualTo("entry.json/index.size")
        assertThat(whenExpanded.selector).isEqualTo("index-v2.json/size")
    }

    @Test
    @DisplayName("an index exactly as long as declared passes")
    fun exactDeclaredSizeIsAccepted() = runTest {
        // The cap is "beyond", not "at": reading one extra byte to tell the two cases apart would be
        // pointless if the exact length were then refused, which is the normal one.
        val result = client.download(sliceEntryFile())

        assertThat(result).isInstanceOf(StoreResult.Success::class.java)
        (result as StoreResult.Success).value.close()
    }

    @Test
    @DisplayName("a mirror stalled beyond the declared maxAge is reported, not blocked")
    fun staleMirrorIsReported() = runTest {
        // `entry.json` declares `maxAge: 14` days. It was the only field of the signed document
        // that was deserialised and never read.
        val snapshot = openWithClockAt(entryTimestamp + 30.days.inWholeMilliseconds)

        snapshot.use {
            val staleness = requireNotNull(it.staleness)
            assertThat(staleness.maxAge).isEqualTo(14.days)
            assertThat(staleness.age).isAtLeast(30.days)
            assertThat(staleness.exceeded).isTrue()
        }
    }

    @Test
    @DisplayName("a fresh index is not stale, and the sync starts anyway")
    fun freshMirrorIsNotStale() = runTest {
        val snapshot = openWithClockAt(entryTimestamp + 2.days.inWholeMilliseconds)

        // Not blocking is the point: the anti-rollback protects whoever already has an index, this
        // one looks at the clock and serves whoever does not have one yet — but an old, authentic
        // index remains more useful than no index.
        snapshot.use { assertThat(requireNotNull(it.staleness).exceeded).isFalse() }
    }

    /**
     * Opens the index with the clock stopped at a chosen instant, offering the token that
     * **matches**.
     *
     * Offering the matching token is not a shortcut to avoid the download: it is precisely the case
     * that counts. A stalled mirror goes on serving the same `entry.json`, so every subsequent sync
     * ends on the "nothing to do" path — and it is there that staleness must still surface, otherwise
     * the case that makes it useful is the only one where it is invisible.
     */
    private suspend fun openWithClockAt(epochMillis: Long): StoreIndexSnapshot {
        val fixed = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(epochMillis)
        }
        val dated = FdroidStoreAdapter(
            config = config,
            clients = clients,
            workDir = workDir,
            clock = fixed,
        )
        val opened = dated.openIndex(IndexToken(entryTimestamp.toString()))
        return (opened as StoreResult.Success).value
    }

    // --- openIndex: token, rollback, incremental ----------------------------------------------

    @Test
    @DisplayName("index already up to date: nothing to download")
    fun sameTimestampMeansNothingToDo() = runTest {
        val snapshot = (adapter.openIndex(IndexToken(entryTimestamp.toString())) as StoreResult.Success).value

        snapshot.use {
            assertThat(it.expectedRecords).isEqualTo(0)
            assertThat(it.token.value).isEqualTo(entryTimestamp.toString())
        }
    }

    @Test
    @DisplayName("an index older than the one we have is refused")
    fun rollbackIsRefused() = runTest {
        val future = IndexToken((entryTimestamp + 1).toString())

        val error = (adapter.openIndex(future) as StoreResult.Failure).error

        // The signature does not stop a mirror re-serving an old but authentic index: it is the
        // attack that freezes security updates without breaking anything. The only defence is
        // remembering how far we had got.
        assertThat(error).isInstanceOf(StoreError.ParseFailure::class.java)
        assertThat((error as StoreError.ParseFailure).selector).contains("timestamp")
    }

    @Test
    @DisplayName("on first launch the whole index is requested, and if the hash does not match we stop")
    fun firstSyncAsksForTheFullIndex() = runTest {
        // The real entry.json declares the hash of the real 57 MB index; the fake server answers
        // with the slice. It is exactly the "the file is not the signed one" scenario.
        fake.overrides["/repo/index-v2.json"] = Fixtures.INDEX_SLICE

        val error = (adapter.openIndex(current = null) as StoreResult.Failure).error

        assertThat(error).isInstanceOf(StoreError.ParseFailure::class.java)
        assertThat(fake.received.map { it.url.encodedPath }).contains("/repo/index-v2.json")
    }

    @Test
    @DisplayName("with a matching timestamp the diff is requested, not the whole index")
    fun knownTimestampAsksForTheDiff() = runTest {
        val entry = (client.fetchEntry() as StoreResult.Success).value
        val diffTimestamp = entry.diffs.keys.first()
        val diffPath = "/repo${entry.diffs.getValue(diffTimestamp).name}"
        fake.overrides[diffPath] = Fixtures.DIFF_SLICE
        fake.received.clear()

        adapter.openIndex(IndexToken(diffTimestamp))

        // 252 KB instead of 17.8 MB. The hash failure is expected — the real diff is not our fixture
        // — but what is being checked here is *which* file is requested.
        assertThat(fake.received.map { it.url.encodedPath }).contains(diffPath)
        assertThat(fake.received.none { it.url.encodedPath == "/repo/index-v2.json" }).isTrue()
    }

    // --- Streaming ----------------------------------------------------------------------------

    @Test
    @DisplayName("the stream emits the catalogue first, then one entry per package")
    fun snapshotStreamsCatalogThenPackages() = runTest {
        val copy = File(workDir, "slice.json").also { Fixtures.file(Fixtures.INDEX_SLICE).copyTo(it) }
        val download = VerifiedDownload(copy, gzipped = false, expectedSha256 = sha256Of(copy))
        val snapshot = FdroidIndexSnapshot(
            download = download,
            token = IndexToken(entryTimestamp.toString()),
            mode = IndexSyncMode.FULL,
            expectedRecords = 12,
            expectedBytes = copy.length(),
            projection = PackageProjection(repoUrl = config.repoUrl),
        )

        val records = snapshot.use { it.records().toList() }

        val catalog = records.filterIsInstance<com.multistore.store.api.IndexRecord.Catalog>()
        val full = records.filterIsInstance<com.multistore.store.api.IndexRecord.Full>()
        assertThat(catalog).hasSize(1)
        assertThat(requireNotNull(catalog.single().info).antiFeatures).isNotEmpty()
        assertThat(full).hasSize(12)
        // The zip-only package is stored all the same — the payload is needed for future diffs — but
        // produces no listing.
        assertThat(full.count { it.detail == null }).isEqualTo(1)
        assertThat(full.count { it.detail != null }).isEqualTo(11)
    }

    @Test
    @DisplayName("in incremental mode a null package becomes a removal")
    fun incrementalSnapshotEmitsRemovals() = runTest {
        val copy = File(workDir, "diff.json").also { Fixtures.file(Fixtures.DIFF_SLICE).copyTo(it) }
        val download = VerifiedDownload(copy, gzipped = false, expectedSha256 = sha256Of(copy))
        val snapshot = FdroidIndexSnapshot(
            download = download,
            token = IndexToken((entryTimestamp + 1).toString()),
            mode = IndexSyncMode.INCREMENTAL,
            expectedRecords = 2,
            expectedBytes = copy.length(),
            projection = PackageProjection(repoUrl = config.repoUrl),
        )

        val records = snapshot.use { it.records().toList() }

        val removals = records.filterIsInstance<com.multistore.store.api.IndexRecord.Remove>()
        val patches = records.filterIsInstance<com.multistore.store.api.IndexRecord.Patch>()
        assertThat(removals.map { it.ref.value }).containsExactly(Fixtures.PKG_SNAKE)
        assertThat(patches.map { it.ref.value }).containsExactly(Fixtures.PKG_CATIMA)
        // On an incremental sync the `repo` block is itself a merge patch: projecting it alone would
        // give a truncated taxonomy, so the catalogue arrives without a projection.
        val catalog = records.filterIsInstance<com.multistore.store.api.IndexRecord.Catalog>()
        assertThat(catalog.single().info).isNull()
    }
}
