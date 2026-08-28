package com.multistore.core.remoteconfig

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.remoteconfig.di.RemoteConfigClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * Downloads MultiStore's APK from our host, computing the SHA-256 as the bytes arrive.
 *
 * ### Why it does not go through `:core:download`, and what is *not* being duplicated
 *
 * The download engine exists for a problem that does not arise here. A store's APK can weigh a
 * gigabyte, comes from a host we do not control, and the transfer has to survive the process dying:
 * hence the row in Room, resumption with `Range` tied to an `ETag`, the foreground service, the
 * worker the periodic check re-enqueues. Every piece of that machinery is indexed on
 * `(storeId, ref, versionRef)`, and MultiStore has none of the three: it does not come from a store,
 * and `StoreId` is a closed enum of nine values precisely so that none can be invented.
 *
 * Adding a fake tenth would have meant a value `StoreCatalogTest` demands has a module, a description
 * and five translations; making them nullable would have meant three nullable columns in
 * `downloads`, a migration, and every reader of that table forced to handle a case only this path
 * produces.
 *
 * What is **not** duplicated is the only thing that matters: the verification. The APK downloaded
 * here goes through the same seven `PreInstallVerifier` steps as any other, including the comparison
 * of the signer with the **installed** one — which is the check that makes harmless an index signed
 * with our key but pointing at somebody else's package.
 *
 * The client is the remote configuration's: it is the same host, and it already has the right
 * timeouts for a file of ours.
 */
/**
 * Whoever brings MultiStore's APK down to earth.
 *
 * It is an interface for the same reason `DownloadEngine` is: hash computation interleaved with
 * writing the file is the easiest part of the path to get wrong, and it has to be testable against a
 * fake server — and a ViewModel assembling the use case must not have to drag OkHttp along to do it.
 */
interface SelfUpdateSource {

    /** What happened. No resumption: a failed attempt is redone from scratch. */
    sealed interface Outcome {
        data class Success(val file: File, val sha256: String, val bytes: Long) : Outcome
        data class Failed(val httpCode: Int?, val cause: Throwable?) : Outcome
        /** Downloaded in full, but it is not the file the index declared. */
        data class Mismatch(val expected: String, val actual: String) : Outcome
    }

    /** @param onProgress bytes received so far, and the total if the server declares it. */
    suspend fun download(
        release: SelfUpdateRelease,
        destination: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Outcome
}

@Singleton
class SelfUpdateDownloader @Inject constructor(
    @param:RemoteConfigClient private val calls: Call.Factory,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : SelfUpdateSource {

    override suspend fun download(
        release: SelfUpdateRelease,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ): SelfUpdateSource.Outcome = withContext(io) {
        val request = Request.Builder().url(release.url).build()
        val digest = MessageDigest.getInstance(SHA_256)
        var received = 0L

        try {
            calls.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext SelfUpdateSource.Outcome.Failed(response.code, null)
                val total = response.body.contentLength().takeIf { it >= 0 } ?: release.size
                destination.parentFile?.mkdirs()
                response.body.byteStream().use { source ->
                    destination.outputStream().use { sink ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            sink.write(buffer, 0, read)
                            received += read
                            onProgress(received, total)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            destination.delete()
            return@withContext SelfUpdateSource.Outcome.Failed(httpCode = null, cause = e)
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = release.sha256?.lowercase()
        // The comparison happens **here**, before the file reaches an installer, and a mismatch
        // deletes the file instead of leaving it in staging: an APK that is not the declared one
        // must not be collectable by any other path.
        if (expected != null && expected != actual) {
            destination.delete()
            return@withContext SelfUpdateSource.Outcome.Mismatch(expected = expected, actual = actual)
        }
        SelfUpdateSource.Outcome.Success(file = destination, sha256 = actual, bytes = received)
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val CHUNK_BYTES = 64 * 1024
    }
}
