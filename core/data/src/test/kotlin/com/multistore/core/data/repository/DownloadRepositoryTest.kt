package com.multistore.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.database.MultiStoreDatabase
import com.multistore.core.database.entity.DownloadEntity
import com.multistore.core.download.DownloadEngine
import com.multistore.core.download.DownloadListener
import com.multistore.core.download.DownloadOutcome
import com.multistore.core.download.DownloadRequest
import com.multistore.core.download.DownloadScheduler
import com.multistore.core.download.PartialDownload
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.DownloadHistoryLimit
import com.multistore.core.model.DownloadState
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadResolution
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The download queue, now that the transfer lives in a worker.
 *
 * The two tests that count are the first and the second, and they come from the same consequence: the
 * download no longer belongs to whoever started it. From there follow an already-ready file that must
 * not be re-downloaded, and a cancellation that has to stop something running elsewhere.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DownloadRepositoryTest {

    private lateinit var db: MultiStoreDatabase
    private lateinit var repository: DownloadRepositoryImpl

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings = LocalSettings()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_787_316_712_615L)
    }

    /** It records what it is asked: no WorkManager runs here. */
    private class RecordingScheduler : DownloadScheduler {
        val started = mutableListOf<Long>()
        val unmeteredOnly = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        override fun start(downloadId: Long, requireUnmetered: Boolean) {
            started += downloadId
            if (requireUnmetered) unmeteredOnly += downloadId
        }
        override fun cancel(downloadId: Long) { cancelled += downloadId }
    }

    /** An engine that counts how many times it is called, and can succeed or break off. */
    private class CountingEngine(private val bytes: Long) : DownloadEngine {
        var calls = 0
        var succeeds = true

        override suspend fun download(
            request: DownloadRequest,
            listener: DownloadListener,
        ): DownloadOutcome {
            calls++
            request.destination.parentFile?.mkdirs()
            if (!succeeds) {
                // Half a file and no digest: it is what is left after a dropped network.
                request.destination.writeBytes(ByteArray((bytes / 2).toInt()))
                return DownloadOutcome.Interrupted(
                    error = AppError.Network(null),
                    partial = PartialDownload(bytes / 2, "etag"),
                )
            }
            request.destination.writeBytes(ByteArray(bytes.toInt()))
            return DownloadOutcome.Success(request.destination, SHA, bytes)
        }
    }

    private lateinit var scheduler: RecordingScheduler
    private lateinit var engine: CountingEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MultiStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingScheduler()
        engine = CountingEngine(SIZE)
        repository = DownloadRepositoryImpl(
            context = context,
            dao = db.downloadDao(),
            catalogDao = db.catalogDao(),
            engine = engine,
            scheduler = scheduler,
            settings = settings,
            clock = clock,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun enqueue(): Long = repository.enqueue(
        storeId = StoreId.FDROID,
        ref = StoreAppRef("org.example.app"),
        versionRef = VersionRef("v1"),
        packageName = "org.example.app",
        listingId = null,
        resolution = DownloadResolution.Direct(
            url = "https://example.test/app.apk",
            headers = emptyMap(),
            fileName = "app.apk",
            artifactType = ArtifactType.APK,
            expectedSha256 = null,
            expectedSize = SIZE,
        ),
        pendingInstall = true,
    )

    @Test
    fun `an already ready download is not re-downloaded`() = runTest {
        val id = enqueue()
        assertThat(repository.run(id)).isInstanceOf(Outcome.Success::class.java)
        assertThat(engine.calls).isEqualTo(1)

        // Returning to a listing whose download finished while looking elsewhere must not start over.
        // Without the short-circuit branch the engine would ask for a `Range` beginning past the end
        // of the file, the server would answer 416, and eighteen megabytes would restart from zero.
        assertThat(repository.run(id)).isInstanceOf(Outcome.Success::class.java)
        assertThat(engine.calls).isEqualTo(1)
    }

    @Test
    fun `a row kept by 'keep the APKs' is reused instead of re-downloaded`() = runTest {
        val id = enqueue()
        repository.run(id)
        repository.retire(id)

        // Without reuse, `keep_apk_after_install` would keep bytes nobody can read: `filesDir` is
        // private to the app, and a second `enqueue` would create a new row and re-download
        // everything. It is why `retire` exists instead of a skipped `delete`.
        val riuso = enqueue()

        assertThat(riuso).isEqualTo(id)
        assertThat(repository.run(riuso)).isInstanceOf(Outcome.Success::class.java)
        assertThat(engine.calls).isEqualTo(1)
    }

    @Test
    fun `a kept row is not reused if the store declares a different hash`() = runTest {
        val id = enqueue()
        repository.run(id)
        repository.retire(id)

        // A `versionRef` ought to identify a precise artefact, but on eight stores out of nine it is
        // a slug or a page id: nothing stops that store republishing different bytes under it. Where
        // a hash exists, it is the only thing that notices.
        val other = repository.enqueue(
            storeId = StoreId.FDROID,
            ref = StoreAppRef("org.example.app"),
            versionRef = VersionRef("v1"),
            packageName = "org.example.app",
            listingId = null,
            resolution = DownloadResolution.Direct(
                url = "https://example.test/app.apk",
                headers = emptyMap(),
                fileName = "app.apk",
                artifactType = ArtifactType.APK,
                expectedSha256 = Sha256.parseOrNull("b".repeat(64)),
                expectedSize = SIZE,
            ),
            pendingInstall = true,
        )

        assertThat(other).isNotEqualTo(id)
    }

    // --- the row after the installation: history, not a deletion --------------------------------

    @Test
    fun `a successful installation deletes the file and keeps the row as history`() = runTest {
        val id = enqueue()
        repository.run(id)
        val file = requireNotNull(repository.get(id)?.file)

        repository.recordInstalled(id)

        // The file goes: that is what the default of `keep_apk_after_install` promises. The row
        // stays, and until M5/7 it did not — which made a history impossible, since nothing older
        // than the transfer in flight existed to show.
        assertThat(file.exists()).isFalse()
        val row = requireNotNull(repository.get(id))
        assertThat(row.file).isNull()
        assertThat(row.state).isEqualTo(DownloadState.DONE)
        // And it says **when**: without this, a closed row with no file would mean both "installed"
        // and "deleted before it was ever installed".
        assertThat(row.installedAt).isNotNull()
    }

    @Test
    fun `deleting a staged APK leaves a history entry that was never installed`() = runTest {
        val id = enqueue()
        repository.run(id)
        val file = requireNotNull(repository.get(id)?.file)

        repository.deleteStaged(id)

        assertThat(file.exists()).isFalse()
        val row = requireNotNull(repository.get(id))
        assertThat(row.file).isNull()
        // The absence of a date is the entry's whole content: this download never became an
        // installed app. The screen reads exactly that difference.
        assertThat(row.installedAt).isNull()
    }

    @Test
    fun `the right to carry on to the installation is handed over exactly once`() = runTest {
        val id = enqueue()

        // Two candidates for the same file at the same instant — the listing still on screen and the
        // shell's coordinator — and two `PackageInstaller` sessions would be two confirmation
        // dialogs for one app. A read followed by a write would not settle it; the `UPDATE … WHERE`
        // does, because SQLite decides.
        assertThat(repository.claimPendingInstall(id)).isTrue()
        assertThat(repository.claimPendingInstall(id)).isFalse()
    }

    @Test
    fun `the history is trimmed to the ceiling, and a live transfer is not history`() = runTest {
        settings.storage.value = settings.storage.value.copy(
            downloadHistoryLimit = DownloadHistoryLimit.LAST_50,
        )
        val dao = db.downloadDao()
        // Fifty-two concluded downloads, oldest first, plus one that is still going.
        repeat(52) { index ->
            dao.upsert(
                DownloadEntity(
                    listingId = 0,
                    storeId = StoreId.FDROID,
                    storeAppRef = "app.$index",
                    versionRef = "v$index",
                    packageName = null,
                    state = DownloadState.DONE,
                    createdAt = clock.now() + index.seconds,
                    updatedAt = clock.now() + index.seconds,
                ),
            )
        }
        val live = dao.upsert(
            DownloadEntity(
                listingId = 0,
                storeId = StoreId.FDROID,
                storeAppRef = "in-corso",
                versionRef = "in-corso",
                packageName = null,
                state = DownloadState.READY,
                filePath = "/tmp/in-corso.apk",
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )

        assertThat(repository.pruneHistory()).isEqualTo(2)

        // The ready-to-install row is untouched, and it is not counted towards the ceiling either:
        // pruning it would leave an APK on disk that no row claims and no button can install.
        assertThat(repository.get(live)).isNotNull()
        // The two dropped are the **oldest**, not an arbitrary pair.
        assertThat(dao.get(1L)).isNull()
        assertThat(dao.get(2L)).isNull()
        assertThat(dao.get(3L)).isNotNull()
    }

    @Test
    fun `keeping them all prunes nothing`() = runTest {
        settings.storage.value = settings.storage.value.copy(
            downloadHistoryLimit = DownloadHistoryLimit.KEEP_ALL,
        )
        val id = enqueue()
        repository.run(id)
        repository.recordInstalled(id)

        assertThat(repository.pruneHistory()).isEqualTo(0)
        assertThat(repository.get(id)).isNotNull()
    }

    @Test
    fun `emptying the history spares what is still going`() = runTest {
        val concluso = enqueue()
        repository.run(concluso)
        repository.recordInstalled(concluso)
        val dao = db.downloadDao()
        val vivo = dao.upsert(
            DownloadEntity(
                listingId = 0,
                storeId = StoreId.FDROID,
                storeAppRef = "in-corso",
                versionRef = "in-corso",
                packageName = null,
                state = DownloadState.READY,
                filePath = "/tmp/in-corso.apk",
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )

        assertThat(repository.clearHistory()).isEqualTo(1)

        assertThat(repository.get(concluso)).isNull()
        // Somebody emptying a list must not thereby lose the row saying an APK is waiting to be
        // installed — nor cancel a transfer that is running.
        assertThat(repository.get(vivo)).isNotNull()
    }

    @Test
    fun `a truncated file does not count as ready`() = runTest {
        val id = enqueue()
        repository.run(id)
        // `filesDir` is not emptied by the system, but a full disk or an interrupted installation can
        // leave a short file with the row saying READY. Trusting the column alone would send that file
        // to pre-install verification, which would discard it on size with a message that explains
        // nothing.
        File(requireNotNull(repository.get(id)?.file?.absolutePath)).writeBytes(ByteArray(3))

        repository.run(id)

        assertThat(engine.calls).isEqualTo(2)
    }

    @Test
    fun `start on a half-finished transfer re-queues it and sets the worker going`() = runTest {
        engine.succeeds = false
        val id = enqueue()
        repository.run(id)
        assertThat(repository.get(id)?.state).isEqualTo(DownloadState.PAUSED)

        repository.start(id, requireUnmetered = false)

        // Without the return to `QUEUED`, whoever is waiting would immediately find the row in
        // `PAUSED` — i.e. in an already final state — and would conclude this round had failed before
        // the worker even started.
        assertThat(repository.get(id)?.state).isEqualTo(DownloadState.QUEUED)
        assertThat(scheduler.started).containsExactly(id)
        // Whoever has just pressed "Install" has already decided to spend that traffic: postponing
        // the transfer until they find Wi-Fi would be deciding for them. The `UNMETERED` constraint
        // concerns what starts by itself, and today nothing starts by itself.
        assertThat(scheduler.unmeteredOnly).isEmpty()
    }

    @Test
    fun `start on an already ready file starts no worker`() = runTest {
        val id = enqueue()
        repository.run(id)

        repository.start(id, requireUnmetered = false)

        // The regression found on the emulator, and the reason the short circuit does **not** look at
        // the row's state: `start` puts it back to `QUEUED`, so a check based on the column never saw
        // a complete file and nineteen megabytes already on disk were re-downloaded. What decides are
        // the facts about the file, not the state.
        assertThat(scheduler.started).isEmpty()
        assertThat(repository.get(id)?.state).isEqualTo(DownloadState.READY)
        assertThat(engine.calls).isEqualTo(1)
    }

    @Test
    fun `cancelling stops the worker and leaves the bytes on disk`() = runTest {
        val id = enqueue()
        repository.run(id)
        val file = requireNotNull(repository.get(id)?.file)

        repository.cancel(id)

        assertThat(scheduler.cancelled).containsExactly(id)
        assertThat(repository.get(id)?.state).isEqualTo(DownloadState.PAUSED)
        // Cancelling is not giving up: throwing the partial file away would make the user pay again
        // for the megabytes already fetched. `discard` throws it away, after a successful install.
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `whoever is waiting wakes up when the file is ready`() = runTest {
        val id = enqueue()
        repository.run(id)

        val outcome = repository.awaitCompletion(id)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat((outcome as Outcome.Success).value.length()).isEqualTo(SIZE)
    }

    @Test
    fun `the download in progress is observable per app, not only by id`() = runTest {
        val id = enqueue()

        val status = repository.observeFor(StoreId.FDROID, StoreAppRef("org.example.app")).first()

        // It is what lets a listing reopened halfway through a transfer reattach to a download it did
        // not start: without it, it would show "Install" above a system notification saying the
        // opposite.
        assertThat(status?.id).isEqualTo(id)
    }

    private companion object {
        const val SIZE = 128L
        val SHA: Sha256 = requireNotNull(Sha256.parseOrNull("aa".repeat(32)))
    }
}
