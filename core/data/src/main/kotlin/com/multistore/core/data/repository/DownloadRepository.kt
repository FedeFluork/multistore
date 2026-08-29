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
import kotlin.time.Instant
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
     * It is populated **only** by [DownloadRepository.observeActive] and
     * [DownloadRepository.observeAll], the two consumers that need it: a list of downloads has to
     * say *what* is being downloaded, and "`2971-telegram`" is nobody's name. Elsewhere it stays
     * `null` because whoever is looking at a specific download is already on the listing
     * describing it.
     */
    val title: String? = null,
    /** The app's icon, from the same two queries and `null` for the same reason. */
    val iconUrl: String? = null,
    /**
     * When the app was installed from this download, or `null` if it never was.
     *
     * It is what keeps two histories apart that the state alone conflates: `DONE` with no file is
     * both "installed, and the APK deleted afterwards" and "downloaded, then deleted without ever
     * being installed".
     */
    val installedAt: Instant? = null,
    /**
     * An installation was meant to follow, and has not happened yet.
     *
     * `false` on a transfer the periodic check started to leave ready for later: that one was asked
     * to stop at the file.
     */
    val pendingInstall: Boolean = false,
    val createdAt: Instant = Instant.DISTANT_PAST,
    val updatedAt: Instant = Instant.DISTANT_PAST,
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

    /**
     * Every download the app remembers, newest first: in flight, waiting, and concluded.
     *
     * One flow and not three, because the Downloads screen draws the three groups together and a
     * row crosses between them while it is being looked at. Three flows would emit at three
     * moments, and the same app would flicker in two groups or in none.
     */
    fun observeAll(): Flow<List<DownloadStatus>>

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
        /**
         * Whether an installation is meant to follow this transfer.
         *
         * No default value, and that is the same choice as `requireUnmetered` on [start]: the two
         * answers belong to two different origins and guessing one of them is exactly the mistake.
         * `true` when the user pressed Install; `false` when the periodic check was asked to
         * download and stop, because `auto_install_updates` is off.
         */
        pendingInstall: Boolean,
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
     * restarts from where it was. Throwing it away is [recordInstalled]'s job, called on a successful install.
     */
    suspend fun cancel(id: Long)

    /**
     * Throws away the staged file and closes the row as installed.
     *
     * ### The row survives, and until M5/7 it did not
     *
     * It used to be deleted outright, which made the history impossible: nothing older than the
     * transfer in flight existed to show. What has to go is the **file** — that is what the
     * default of `keep_apk_after_install` promises — and what has to stay is the record that this
     * app was downloaded from this store and installed. The row is bounded by
     * `download_history_limit`, so keeping it is not an unbounded cost.
     *
     * `installedAt` is what makes the entry readable afterwards: without it a closed row with no
     * file would mean both "installed" and "deleted before being installed".
     */
    suspend fun recordInstalled(id: Long)

    /**
     * Closes the download **keeping** the file: [recordInstalled]'s counterpart with
     * `keep_apk_after_install` on.
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
     * Deletes the staged APK at the user's request, without installing it.
     *
     * It is the Downloads screen's "Delete" next to "Install", and it is the only gesture in the
     * app that throws away a **whole, verified** file on purpose — hence the confirmation in front
     * of it. The row stays, as history, with `installedAt` left `null`: that is what tells this
     * entry apart from one whose APK was deleted *after* being installed.
     *
     * A container's opened directory goes with it. It is derived data and [Staging] knows the
     * correspondence; leaving it would be two hundred megabytes nobody ever looks at again.
     */
    suspend fun deleteStaged(id: Long)

    /**
     * Takes the right to carry this download on to the installation, atomically.
     *
     * @return `true` if this caller won it. The case it settles is two candidates for the same
     * file at the same instant — the listing still on screen and the shell's coordinator — which
     * without it would be two confirmation dialogs for one app.
     */
    suspend fun claimPendingInstall(id: Long): Boolean

    /**
     * Trims the history to the ceiling the user chose, and returns how many rows went.
     *
     * Only concluded rows count and only concluded rows go: a live transfer is not history, and
     * pruning a ready-to-install one would leave an APK on disk with no row to install it from.
     */
    suspend fun pruneHistory(): Int

    /** Empties the history at the user's request. Live transfers and ready files are untouched. */
    suspend fun clearHistory(): Int

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
