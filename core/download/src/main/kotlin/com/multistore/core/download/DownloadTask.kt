package com.multistore.core.download

import kotlinx.coroutines.flow.Flow

/** How much has been transferred, for the progress bar and the notification. */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val bytesTotal: Long?,
    /** `true` when the row has reached a state that no longer changes by itself. */
    val terminal: Boolean,
) {
    val fraction: Float?
        get() = bytesTotal?.takeIf { it > 0 }
            ?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * What the worker has to be able to do, without knowing who does it.
 *
 * It exists for the same reason as [DownloadNetworkProfiles]: the transfer and its state live in
 * `:core:data`, which this module **cannot see** — the dependency goes the other way. Without this
 * interface the worker would have to live in `:core:data`, and with it the notification, the channel
 * and the permissions: all download material ending up in the repositories' module.
 *
 * The three functions are what a worker needs and nothing else: what is this thing I am downloading
 * called, do it, and tell me how far you are.
 */
interface DownloadTask {

    /**
     * The name to show in the notification.
     *
     * `null` when the catalogue no longer knows it — the sync may have deleted a withdrawn package's
     * listing while it was being downloaded. In that case the notification says something generic
     * instead of showing a `packageName` to the user.
     */
    suspend fun label(id: Long): String?

    /**
     * Runs the transfer to the end. `true` if the file is ready and verified.
     *
     * It is called `transfer` and not `run` because its implementor is the same object exposing
     * `DownloadRepository.run`, with a different return type: two functions with the same name and the
     * same parameters cannot coexist on the JVM.
     */
    suspend fun transfer(id: Long): Boolean

    fun observeProgress(id: Long): Flow<DownloadProgress?>
}
