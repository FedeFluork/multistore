package com.multistore.core.download

import com.multistore.core.common.result.AppError
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreId
import java.io.File

/**
 * What to download, and what is already known about the file before starting.
 *
 * [expectedSha256] is not optional out of convenience: where the store publishes it, a download that
 * does not match must not reach an installer. Where it does not publish it — which happens on several
 * of the nine — integrity is established by the archive's signature, further along the pipeline.
 */
data class DownloadRequest(
    val storeId: StoreId,
    val url: String,
    /** Extra Referer, Cookie, UA: what *that* server answers 403 without. */
    val headers: Map<String, String> = emptyMap(),
    /** The destination file. We generate the name: a name chosen by a server is a path chosen by a server. */
    val destination: File,
    val expectedSha256: Sha256? = null,
    val expectedSize: Long? = null,
    /** What is known about a previous attempt, if resuming. */
    val resume: PartialDownload? = null,
)

/**
 * An interrupted download's state.
 *
 * [validator] is the `ETag` (or the `Last-Modified`) seen on the first attempt. Without it, a
 * resumption asks "give me from byte N on" without saying *of which file*, and if the server has
 * published a different version in the meantime the new bytes are glued to the old.
 */
data class PartialDownload(
    val bytesDownloaded: Long,
    val validator: String? = null,
)

sealed interface DownloadOutcome {

    data class Success(
        val file: File,
        val sha256: Sha256,
        val bytes: Long,
    ) : DownloadOutcome

    /**
     * The download stopped, but what is on disk is reusable.
     *
     * Distinct from [Failed] because it carries how to resume: a network error halfway through an
     * 80 MB file must not cost 80 MB next time.
     */
    data class Interrupted(val error: AppError, val partial: PartialDownload) : DownloadOutcome

    /** Not resumable: wrong hash, wrong size, 404. The partial file must be thrown away. */
    data class Failed(val error: AppError) : DownloadOutcome
}

/** What happens during a download, for the bar and for persisting the state. */
interface DownloadListener {

    /**
     * Called once, when the server has answered and it is known what is being downloaded.
     *
     * [validator] has to be **persisted**: it is what makes a resumption safe after the process has
     * died. [resumedFrom] is 0 when resumption was not granted and we start over.
     */
    fun onStarted(totalBytes: Long?, validator: String?, resumedFrom: Long) = Unit

    fun onProgress(bytesDownloaded: Long, totalBytes: Long?) = Unit

    companion object {
        val NONE: DownloadListener = object : DownloadListener {}
    }
}

/**
 * Whoever downloads a file.
 *
 * It is an interface for one reason, but a sufficient one: streaming hash computation interleaved
 * with partial resumption is the easiest part of the whole path to get wrong, and it has to be
 * testable against a fake server without dragging WorkManager along.
 */
interface DownloadEngine {
    suspend fun download(
        request: DownloadRequest,
        listener: DownloadListener = DownloadListener.NONE,
    ): DownloadOutcome
}
