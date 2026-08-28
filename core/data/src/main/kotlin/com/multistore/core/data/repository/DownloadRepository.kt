package com.multistore.core.data.repository

import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.model.DownloadState
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.store.api.DownloadResolution
import java.io.File
import kotlinx.coroutines.flow.Flow

/** A download as whoever draws a screen sees it. */
data class DownloadStatus(
    val id: Long,
    val storeId: StoreId,
    val ref: StoreAppRef,
    val versionRef: VersionRef,
    val packageName: String?,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long?,
    val file: File?,
    val error: AppError?,
    /**
     * The app's name, when the listing that originated the download is still in the catalogue.
     *
     * It is populated **only** by [DownloadRepository.observeActive], the only consumer that needs
     * it: the progress card above the screens has to say *what* is being downloaded, and
     * "`2971-telegram`" is nobody's name. Elsewhere it stays `null` because whoever is looking at a
     * specific download is already on the listing describing it.
     */
    val title: String? = null,
) {
    /** `null` when the total size is not known: an indeterminate bar is shown. */
    val fraction: Float?
        get() = bytesTotal?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * The download queue and its execution.
 *
 * The state lives in Room and not in memory for the same reason as the circuit breaker: the process
 * dies, and an interrupted 80 MB download has to be able to resume from where it was instead of from
 * the start. It is also what makes it possible to recognise at startup the downloads left halfway,
 * which would otherwise occupy staging space and stay "in progress" forever in the UI.
 */
interface DownloadRepository {

    fun observeActive(): Flow<List<DownloadStatus>>

    fun observe(id: Long): Flow<DownloadStatus?>

    suspend fun get(id: Long): DownloadStatus?

    /**
     * Queues a download, or returns the one already in progress for the same version.
     *
     * Reusing the existing download is not an optimisation: two concurrent downloads on the same
     * staging file would overwrite each other, and the winner would be random.
     */
    suspend fun enqueue(
        storeId: StoreId,
        ref: StoreAppRef,
        versionRef: VersionRef,
        packageName: String?,
        listingId: Long?,
        resolution: DownloadResolution.Direct,
    ): Long

    /** The download in progress for **this app**, if there is one. `null` when there is none. */
    fun observeFor(storeId: StoreId, ref: StoreAppRef): Flow<DownloadStatus?>

    /**
     * Runs the download **here**, suspending until the end.
     *
     * It is what the worker calls. A normal caller uses [start] and [awaitCompletion]: running it in
     * a screen's scope means that leaving the screen cancels it, and eighteen megabytes start over.
     */
    suspend fun run(id: Long): Outcome<File>

    /**
     * Sets the worker in motion, and returns immediately.
     *
     * From here on the transfer no longer belongs to whoever started it: it survives the screen, the
     * app going to the background and — thanks to the foreground service — the ten-minute cap
     * WorkManager imposes on ordinary workers.
     *
     * @param requireUnmetered wait for a **non**-metered network before starting. It has no default
     * value, and that is not an oversight: it is the same choice already made in
     * `DownloadScheduler.start`, whose KDoc explains why the compiler should demand it. The rule is
     * `false` when the user has just pressed something — they have already decided to spend that
     * traffic, and postponing it to Wi-Fi would be deciding for them — and `true` when the transfer is
     * born by itself and the user has not allowed metered networks.
     */
    suspend fun start(id: Long, requireUnmetered: Boolean)

    /**
     * Suspends until the download reaches a state that no longer changes by itself.
     *
     * It observes Room, not the worker: it is what lets a screen reopened halfway through a transfer
     * reattach to a download it did not start.
     */
    suspend fun awaitCompletion(id: Long): Outcome<File>

    /**
     * Stops the transfer and leaves what is there paused.
     *
     * The partial file is **not** thrown away: cancelling is not giving up, and next time resumption
     * restarts from where it was. Throwing it away is [discard]'s job, called on a successful install.
     */
    suspend fun cancel(id: Long)

    /** Throws away the staged file and the row. To be called after a successful installation. */
    suspend fun discard(id: Long)

    /**
     * Closes the download **keeping** the file: [discard]'s counterpart with `keep_apk_after_install`
     * on.
     *
     * The row stays, in [com.multistore.core.model.DownloadState.DONE], and that is not a
     * bookkeeping detail: it is what makes the file **reusable**. `filesDir` is private to the app and
     * no file manager opens it, so an APK kept without a row naming it would be space occupied by
     * bytes nobody can read — a setting whose only effect is waste. With the row, a second [enqueue]
     * for the same version finds it again and the installation restarts from a file that is already
     * there.
     *
     * `DONE` takes it out of the lists anyway: [observeActive], [observeFor] and the search for an
     * in-progress download have excluded that state from the start.
     */
    suspend fun retire(id: Long)

    /**
     * Re-queues what the process left halfway.
     *
     * It has to be called at startup: without it, a download interrupted by the process dying stays
     * `RUNNING` forever — the UI shows it in progress and nobody carries it forward.
     */
    suspend fun requeueInterrupted()

    /** A version's expected SHA-256, if the store publishes it. Needed by pre-install verification. */
    suspend fun expectedHash(id: Long): Sha256?
}
