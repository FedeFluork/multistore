package com.multistore.core.download

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.result.AppError
import com.multistore.core.model.Sha256
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.core.network.http.StoreHttpClients
import com.multistore.core.network.http.StoreNetworkProfile
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response

/**
 * The download engine: OkHttp, streaming digest, `Range` resumption.
 *
 * ### The two things that have to be designed together, or they do not work
 *
 * **Streaming hash and partial resumption.** A digest is computed forwards, one byte after another,
 * and cannot "jump" to the offset being resumed from. Resuming a 40 MB file at byte 30,000,000 and
 * feeding the digest with only the 10 new MB yields the SHA-256 of the **tail**, which will never
 * match the published one — and the diagnosis will be "corrupt download" on a perfectly intact file.
 * So, before reopening the connection, the bytes already on disk are re-read and fed to the digest:
 * it costs a sequential read from local cache, i.e. nothing compared with re-downloading them.
 *
 * **`Range` and `If-Range`.** Asking "from byte N on" without saying *of which file* is safe only
 * while the server does not publish a different version. With `If-Range`, a server whose content has
 * changed answers `200` with the whole file instead of `206`, and the resumption degrades by itself
 * into a download from scratch. Without it, one would get a file stitched from two versions, which no
 * check would catch except the final hash — where there is one.
 *
 * The third case is the `416`: the server says the range makes no sense, usually because the file has
 * changed and is shorter. We start from zero, which is the only correct answer.
 */
@Singleton
class OkHttpDownloadEngine @Inject constructor(
    private val clients: StoreHttpClients,
    private val profiles: DownloadNetworkProfiles,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DownloadEngine {

    override suspend fun download(
        request: DownloadRequest,
        listener: DownloadListener,
    ): DownloadOutcome = withContext(io) {
        val client = clients.forStore(request.storeId, profiles.profileFor(request.storeId))
        try {
            transfer(client, request, listener)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            DownloadOutcome.Interrupted(
                error = AppError.Network(failure),
                partial = PartialDownload(request.destination.length(), request.resume?.validator),
            )
        } catch (failure: SecurityException) {
            DownloadOutcome.Failed(AppError.Storage(failure))
        }
    }

    private suspend fun transfer(
        client: StoreHttpClient,
        request: DownloadRequest,
        listener: DownloadListener,
    ): DownloadOutcome {
        request.destination.parentFile?.mkdirs()
        val alreadyOnDisk = if (request.destination.isFile) request.destination.length() else 0L
        // Only what has really been written is resumed: a stored `bytesDownloaded` larger than the
        // file (a truncation, a cleared cache) would ask the server for a range that does not exist on
        // our disk, and the hole would remain.
        val resumeFrom = minOf(request.resume?.bytesDownloaded ?: 0L, alreadyOnDisk)

        val response = client.executeUncached(buildRequest(request, resumeFrom))
        response.use { return receive(it, request, resumeFrom, listener) }
    }

    private fun buildRequest(request: DownloadRequest, resumeFrom: Long): Request {
        val builder = Request.Builder().url(request.url).get()
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        if (resumeFrom > 0) {
            builder.header(HEADER_RANGE, "bytes=$resumeFrom-")
            request.resume?.validator?.let { builder.header(HEADER_IF_RANGE, it) }
        }
        return builder.build()
    }

    private suspend fun receive(
        response: Response,
        request: DownloadRequest,
        resumeFrom: Long,
        listener: DownloadListener,
    ): DownloadOutcome {
        if (response.code == CODE_RANGE_NOT_SATISFIABLE) {
            // The server says the range does not exist: nearly always the file has changed and is
            // shorter than the one we had. Starting again is the only correct answer.
            request.destination.delete()
            return DownloadOutcome.Interrupted(
                error = AppError.Network(null),
                partial = PartialDownload(bytesDownloaded = 0, validator = null),
            )
        }
        if (!response.isSuccessful) {
            return DownloadOutcome.Failed(httpError(response.code))
        }

        val validator = response.header(HEADER_ETAG) ?: response.header(HEADER_LAST_MODIFIED)
        // 206 = resumption was granted. Any other successful response carries the whole file, even
        // when we asked for it partially: it is what `If-Range` does when the content has changed, and
        // it is also what servers that do not support `Range` at all do. In both cases we start from
        // zero.
        val resumed = response.code == CODE_PARTIAL && resumeFrom > 0 &&
            rangeStartOf(response) == resumeFrom
        val startAt = if (resumed) resumeFrom else 0L

        val body = response.body ?: return DownloadOutcome.Failed(AppError.Network(null))
        val remaining = body.contentLength().takeIf { it >= 0 }
        val total = when {
            remaining == null -> request.expectedSize
            resumed -> startAt + remaining
            else -> remaining
        }
        listener.onStarted(totalBytes = total, validator = validator, resumedFrom = startAt)

        val digest = MessageDigest.getInstance(SHA_256)
        var written = startAt
        if (startAt > 0) {
            // The piece that makes the hash work on a resumption. Without it, the digest would cover
            // only the tail and the final comparison would fail on an intact file.
            feedExistingPrefix(request.destination, startAt, digest)
        }

        RandomAccessFile(request.destination, "rw").use { file ->
            if (startAt == 0L) file.setLength(0) else file.setLength(startAt)
            file.seek(startAt)
            val buffer = ByteArray(BUFFER_BYTES)
            body.byteStream().use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    file.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    written += read
                    listener.onProgress(written, total)
                }
            }
        }

        return verify(request, digest, written, validator)
    }

    private fun verify(
        request: DownloadRequest,
        digest: MessageDigest,
        written: Long,
        validator: String?,
    ): DownloadOutcome {
        request.expectedSize?.let { expected ->
            if (written != expected) {
                // A file shorter than expected is a dropped connection, not a wrong file: what is there
                // stays valid and next time it resumes from there.
                return if (written < expected) {
                    DownloadOutcome.Interrupted(
                        error = AppError.Network(null),
                        partial = PartialDownload(written, validator),
                    )
                } else {
                    DownloadOutcome.Failed(AppError.IntegrityFailed("dimensione"))
                }
            }
        }

        val actual = Sha256.ofBytes(digest.digest())
        request.expectedSha256?.let { expected ->
            if (expected != actual) {
                // Not resumable, and the file has to be thrown away: a hash that does not match means
                // those bytes are not the promised ones, and keeping them around only serves to try
                // installing them again.
                request.destination.delete()
                return DownloadOutcome.Failed(AppError.IntegrityFailed("sha256"))
            }
        }
        return DownloadOutcome.Success(request.destination, actual, written)
    }

    /** Re-reads the already downloaded bytes from disk and feeds them to the digest, in order. */
    private suspend fun feedExistingPrefix(file: File, upTo: Long, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_BYTES)
        var read = 0L
        file.inputStream().buffered().use { stream ->
            while (read < upTo) {
                currentCoroutineContext().ensureActive()
                val wanted = minOf(buffer.size.toLong(), upTo - read).toInt()
                val got = stream.read(buffer, 0, wanted)
                if (got < 0) break
                digest.update(buffer, 0, got)
                read += got
            }
        }
    }

    /** The first byte served, read from `Content-Range: bytes 1000-2000/2001`. */
    private fun rangeStartOf(response: Response): Long? =
        response.header(HEADER_CONTENT_RANGE)
            ?.substringAfter("bytes ", "")
            ?.substringBefore('-')
            ?.trim()
            ?.toLongOrNull()

    private fun httpError(code: Int): AppError = when (code) {
        CODE_NOT_FOUND, CODE_GONE -> AppError.NotFound
        CODE_FORBIDDEN -> AppError.Blocked("HTTP $code")
        CODE_TOO_MANY_REQUESTS -> AppError.RateLimited(null)
        else -> AppError.Network(null)
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val BUFFER_BYTES = 64 * 1024

        const val HEADER_RANGE = "Range"
        const val HEADER_IF_RANGE = "If-Range"
        const val HEADER_ETAG = "ETag"
        const val HEADER_LAST_MODIFIED = "Last-Modified"
        const val HEADER_CONTENT_RANGE = "Content-Range"

        const val CODE_PARTIAL = 206
        const val CODE_FORBIDDEN = 403
        const val CODE_NOT_FOUND = 404
        const val CODE_GONE = 410
        const val CODE_RANGE_NOT_SATISFIABLE = 416
        const val CODE_TOO_MANY_REQUESTS = 429
    }
}

/**
 * Where the download engine gets a store's User-Agent and rate limit from.
 *
 * `:core:download` cannot see the adapters — they are concrete `:store:*` — but the request still has
 * to go out with the right UA: on apkmirror OkHttp's default is a guaranteed 403. The implementation
 * is provided by `:app`, which does know the adapters.
 */
fun interface DownloadNetworkProfiles {
    fun profileFor(storeId: com.multistore.core.model.StoreId): StoreNetworkProfile
}
