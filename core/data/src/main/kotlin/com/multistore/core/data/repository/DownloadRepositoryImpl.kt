package com.multistore.core.data.repository

import android.content.Context
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.DownloadDao
import com.multistore.core.database.entity.DownloadEntity
import com.multistore.core.download.DownloadEngine
import com.multistore.core.download.DownloadProgress
import com.multistore.core.download.DownloadScheduler
import com.multistore.core.download.DownloadTask
import com.multistore.core.download.DownloadListener
import com.multistore.core.download.DownloadOutcome
import com.multistore.core.download.DownloadRequest
import com.multistore.core.download.PartialDownload
import com.multistore.core.model.DownloadState
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadResolution
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
internal class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val catalogDao: CatalogDao,
    private val engine: DownloadEngine,
    private val scheduler: DownloadScheduler,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DownloadRepository, DownloadTask {

    /**
     * Staging sits in `filesDir`, not in `cacheDir`.
     *
     * Both are private to the app, but the system can empty the cache **at any moment**, including
     * the one between an APK's verification and its commit into the installation session. A file
     * vanishing there is not a rare fault: it is the fault that shows up when the device is full, i.e.
     * precisely when something is being downloaded.
     */
    private val stagingDir: File get() = Staging.dir(context)

    override fun observeActive(): Flow<List<DownloadStatus>> =
        dao.observeActive().map { rows -> rows.map { it.download.toStatus(it.listingTitle) } }

    override fun observe(id: Long): Flow<DownloadStatus?> = dao.observe(id).map { it?.toStatus() }

    override fun observeFor(storeId: StoreId, ref: StoreAppRef): Flow<DownloadStatus?> =
        dao.observeFor(storeId, ref.value).map { it?.toStatus() }

    override suspend fun get(id: Long): DownloadStatus? = withContext(io) { dao.get(id)?.toStatus() }

    override suspend fun enqueue(
        storeId: StoreId,
        ref: StoreAppRef,
        versionRef: VersionRef,
        packageName: String?,
        listingId: Long?,
        resolution: DownloadResolution.Direct,
    ): Long = withContext(io) {
        // One download per version: two concurrent runs would write to the same staging file, and
        // which of the two wins would be decided by chance.
        dao.activeFor(versionRef.value)?.let { return@withContext it.id }

        // The file `keep_apk_after_install` left in staging, if it is still the same one.
        //
        // With the switch off this search never finds anything: `discard` deletes row and file as soon
        // as the installation succeeds. On, it is what makes the setting a feature instead of a waste
        // — see `retire`.
        //
        // **The hash comparison is the part that counts.** A `versionRef` ought to identify a precise
        // artefact, but on eight stores out of nine it is a slug or a page id, and nothing stops that
        // store republishing different bytes under it. When the store declares a hash and it is not
        // the kept file's, the old row is not reused; where no hash exists — the majority — the same
        // risk is accepted that `run` already accepts today by resuming a complete file.
        dao.completedFor(storeId, ref.value, versionRef.value)
            ?.takeIf { resolution.expectedSha256 == null || resolution.expectedSha256 == it.expectedSha256 }
            ?.takeIf { row -> row.filePath?.let { File(it).isFile } == true }
            ?.let { return@withContext it.id }
        val now = clock.now()
        dao.upsert(
            DownloadEntity(
                listingId = listingId ?: catalogDao.listingId(storeId, ref.value) ?: 0L,
                storeId = storeId,
                storeAppRef = ref.value,
                versionRef = versionRef.value,
                packageName = packageName,
                state = DownloadState.QUEUED,
                bytesTotal = resolution.expectedSize,
                resolvedUrl = resolution.url,
                // The headers must be **kept**, not recomputed: the `Referer` apkmirror demands is the
                // URL of the interstitial crossed just now, and the assisted path's `Cookie` belongs to
                // the WebView session in which the user has just tapped. Neither exists any more when
                // the worker starts, or when a resumption rebuilds the request after a restart.
                requestHeaders = resolution.headers.takeIf { it.isNotEmpty() },
                expectedSha256 = resolution.expectedSha256,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun run(id: Long): Outcome<File> = withContext(io) {
        val row = dao.get(id) ?: return@withContext Outcome.Failure(AppError.NotFound)

        // Already ready: nothing is re-downloaded.
        //
        // It is not an optimisation, it is a fix. Asking the engine to resume an already complete file
        // means asking for a `Range` beginning past the end, i.e. a 416, and a 416 restarts from
        // scratch: without this branch, returning to a listing whose download finished while looking
        // elsewhere would start eighteen megabytes over again.
        completedFile(row)?.let { return@withContext Outcome.Success(it) }

        val url = row.resolvedUrl ?: return@withContext Outcome.Failure(AppError.NotFound)

        // The file name is generated by us, from the row's id. A name chosen by a server is a path
        // chosen by a server, and `../` inside a `Content-Disposition` is not theory.
        val destination = File(stagingDir, "$id.apk")
        dao.upsert(
            row.copy(
                state = DownloadState.RUNNING,
                filePath = destination.absolutePath,
                updatedAt = clock.now(),
            ),
        )

        // Progress writes go through a **conflated** channel consumed by a single coroutine. The
        // reason is arithmetic: an 80 MB APK crosses 1,250 buffers of 64 KB, and writing to SQLite on
        // every buffer would mean 1,250 transactions for a value that only moves a bar. With
        // conflation, what is there when the writer is free gets written, and the intermediate states
        // are lost — which is exactly what should happen to a progress value.
        val updates = Channel<ProgressUpdate>(Channel.CONFLATED)
        val writer = launch {
            for (update in updates) {
                when (update) {
                    is ProgressUpdate.Started ->
                        dao.setValidator(id, update.validator, update.total, clock.now())

                    is ProgressUpdate.Bytes ->
                        dao.setProgress(id, update.done, update.total, clock.now())
                }
            }
        }

        val outcome = try {
            engine.download(
                DownloadRequest(
                    storeId = row.storeId,
                    url = url,
                    headers = row.requestHeaders.orEmpty(),
                    destination = destination,
                    expectedSha256 = row.expectedSha256,
                    expectedSize = row.bytesTotal,
                    resume = row.takeIf { it.bytesDownloaded > 0 }
                        ?.let { PartialDownload(it.bytesDownloaded, it.validator) },
                ),
                object : DownloadListener {
                    override fun onStarted(totalBytes: Long?, validator: String?, resumedFrom: Long) {
                        // The validator has to be stored **during**, not at the end: if the process
                        // dies halfway, it is the only thing making the next resumption safe.
                        updates.trySend(ProgressUpdate.Started(validator, totalBytes))
                    }

                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long?) {
                        updates.trySend(ProgressUpdate.Bytes(bytesDownloaded, totalBytes))
                    }
                },
            )
        } finally {
            updates.close()
            writer.join()
        }

        val current = dao.get(id) ?: return@withContext Outcome.Failure(AppError.NotFound)
        when (outcome) {
            is DownloadOutcome.Success -> {
                dao.upsert(
                    current.copy(
                        state = DownloadState.READY,
                        bytesDownloaded = outcome.bytes,
                        actualSha256 = outcome.sha256,
                        errorCode = null,
                        updatedAt = clock.now(),
                    ),
                )
                Outcome.Success(outcome.file)
            }

            is DownloadOutcome.Interrupted -> {
                // `PAUSED` and not `FAILED`: the file on disk is still valid and next time it resumes
                // from there. Marking it failed would throw away what has already been downloaded.
                dao.upsert(
                    current.copy(
                        state = DownloadState.PAUSED,
                        bytesDownloaded = outcome.partial.bytesDownloaded,
                        validator = outcome.partial.validator,
                        errorCode = outcome.error.code(),
                        updatedAt = clock.now(),
                    ),
                )
                Outcome.Failure(outcome.error)
            }

            is DownloadOutcome.Failed -> {
                dao.upsert(
                    current.copy(
                        state = DownloadState.FAILED,
                        errorCode = outcome.error.code(),
                        updatedAt = clock.now(),
                    ),
                )
                Outcome.Failure(outcome.error)
            }
        }
    }

    override suspend fun start(id: Long, requireUnmetered: Boolean) {
        val alreadyDone = withContext(io) {
            val row = dao.get(id) ?: return@withContext false
            if (completedFile(row) != null) {
                // The file is already there and whole: no foreground service is started to transfer
                // nothing. The row goes back to declaring itself ready, and whoever is waiting wakes
                // up immediately.
                dao.setState(id, DownloadState.READY, clock.now())
                return@withContext true
            }
            // Otherwise the row goes back into the queue **before** the worker starts. It serves
            // whoever is waiting: a row left in `PAUSED` by a previous attempt would look already
            // finished, and `awaitCompletion` would immediately return a failure from another round.
            dao.setState(id, DownloadState.QUEUED, clock.now())
            false
        }
        // The constraint is decided by the caller, and reaches here with no default: from a tap it is
        // `false`, from the periodic check it is `true` unless the user has allowed metered networks.
        // Deciding it here would mean guessing where we came from.
        if (!alreadyDone) scheduler.start(id, requireUnmetered = requireUnmetered)
    }

    override suspend fun awaitCompletion(id: Long): Outcome<File> {
        val terminal = dao.observe(id)
            .first { it == null || it.state.isSettled }
            ?: return Outcome.Failure(AppError.NotFound)

        return when (terminal.state) {
            DownloadState.READY, DownloadState.DONE ->
                terminal.filePath?.let { Outcome.Success(File(it)) }
                    ?: Outcome.Failure(AppError.Storage(null))

            // `PAUSED` is not a permanent fault — the partial file stays and the next resumption uses
            // it — but for whoever was waiting on *this* download it is an ending all the same.
            else -> Outcome.Failure(terminal.errorCode?.toAppError() ?: AppError.Cancelled)
        }
    }

    override suspend fun cancel(id: Long) {
        scheduler.cancel(id)
        withContext(io) {
            val row = dao.get(id) ?: return@withContext
            // `PAUSED`, not `FAILED` and not deleting the row: what is on disk is still worth
            // something, and throwing it away would mean making the user pay again for the megabytes
            // they already downloaded, for having pressed Cancel.
            dao.upsert(row.copy(state = DownloadState.PAUSED, updatedAt = clock.now()))
        }
    }

    // --- DownloadTask: what the worker asks for, without seeing this module ----------------

    override suspend fun label(id: Long): String? = withContext(io) {
        dao.get(id)?.listingId?.takeIf { it > 0 }?.let { catalogDao.listingTitle(it) }
    }

    override suspend fun transfer(id: Long): Boolean = run(id) is Outcome.Success

    override fun observeProgress(id: Long): Flow<DownloadProgress?> = dao.observe(id).map { row ->
        row?.let {
            DownloadProgress(
                bytesDownloaded = it.bytesDownloaded,
                bytesTotal = it.bytesTotal,
                terminal = it.state.isSettled,
            )
        }
    }

    override suspend fun discard(id: Long) = withContext(io) {
        dao.get(id)?.filePath?.let { path ->
            val file = File(path)
            file.delete()
            // And what came out of it: a container leaves a directory with base and splits next to
            // itself, which without this line would outlive the file it came from. The startup sweep
            // would find it anyway — it is [Staging]'s rule — but only at the next launch, and
            // meanwhile they are the device's two hundred most useless megabytes.
            Staging.splitsOf(file).deleteRecursively()
        }
        dao.delete(id)
    }

    override suspend fun retire(id: Long) = withContext(io) {
        dao.setState(id, DownloadState.DONE, clock.now())
    }

    override suspend fun requeueInterrupted() = withContext(io) {
        val now = clock.now()
        for (row in dao.interrupted()) {
            // What was in `INSTALLING` does not go back into the queue: the installation may have
            // succeeded without our knowing, and re-downloading would achieve nothing. How it ended
            // will be told by reconciliation with the `PackageManager`.
            val next = if (row.state == DownloadState.INSTALLING) DownloadState.READY else DownloadState.PAUSED
            dao.upsert(row.copy(state = next, updatedAt = now))
        }
    }

    override suspend fun expectedHash(id: Long): Sha256? = withContext(io) { dao.get(id)?.expectedSha256 }

    /**
     * A completed download's file, if it exists and is really whole.
     *
     * **It does not look at the row's state, and the first version did.** That was wrong in a way
     * only visible on a device: [start] puts the row back to `QUEUED` before the worker starts, so
     * when the worker gets round to checking, the column says `QUEUED` even for a complete file — and
     * nineteen megabytes already on disk were being re-downloaded.
     *
     * What makes a file "completed" are two facts about the file, not a state column: `actualSha256`
     * exists only if a transfer reached the end and computed its digest, and the length has to match
     * the expected one. The second check exists because `filesDir` is not emptied by the system, but a
     * full disk can leave a truncated file next to a row remembering an earlier success.
     *
     * With an unknown expected size we stop at the digest: it is the best that can be said, and
     * downstream there is pre-install verification anyway.
     */
    private fun completedFile(row: DownloadEntity): File? {
        if (row.actualSha256 == null) return null
        val file = row.filePath?.let(::File)?.takeIf { it.isFile } ?: return null
        val expected = row.bytesTotal
        if (expected != null && file.length() != expected) return null
        return file
    }

    private sealed interface ProgressUpdate {
        data class Started(val validator: String?, val total: Long?) : ProgressUpdate
        data class Bytes(val done: Long, val total: Long?) : ProgressUpdate
    }

    /** A stable code to put in a column: the class name would change with R8. */
    private fun AppError.code(): String = when (this) {
        is AppError.Network -> "network"
        is AppError.RateLimited -> "rate_limited"
        is AppError.Blocked -> "blocked"
        is AppError.Parse -> "parse"
        AppError.NotFound -> "not_found"
        is AppError.IntegrityFailed -> "integrity:$what"
        is AppError.Storage -> "storage"
        is AppError.InstallFailed -> "install"
        AppError.Cancelled -> "cancelled"
            // No download is born with this error today: it is produced by `ResolveDownloadUseCase`
            // before a row exists. It lives here so the encode/decode pair stays total in both
            // directions — a code that is written and never read back would come back as "unexpected
            // error", which is the wrong diagnosis.
        AppError.UserAssistanceDisabled -> "user_assistance_disabled"
        is AppError.Unexpected -> "unexpected"
    }

    private fun String.toAppError(): AppError = when {
        this == "network" -> AppError.Network(null)
        this == "rate_limited" -> AppError.RateLimited(null)
        this == "blocked" -> AppError.Blocked(null)
        this == "not_found" -> AppError.NotFound
        startsWith("integrity:") -> AppError.IntegrityFailed(removePrefix("integrity:"))
        this == "storage" -> AppError.Storage(null)
        this == "cancelled" -> AppError.Cancelled
        this == "user_assistance_disabled" -> AppError.UserAssistanceDisabled
        else -> AppError.Unexpected(null)
    }

    private fun DownloadEntity.toStatus(title: String? = null) = DownloadStatus(
        id = id,
        storeId = storeId,
        ref = StoreAppRef(storeAppRef),
        versionRef = VersionRef(versionRef),
        packageName = packageName,
        state = state,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        file = filePath?.let(::File),
        error = errorCode?.toAppError(),
        title = title,
    )
}
