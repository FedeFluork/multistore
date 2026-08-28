package com.multistore.core.remoteconfig

import com.multistore.core.network.http.await
import java.io.IOException
import javax.inject.Qualifier
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/** The address `parsers.json` is downloaded from. Qualified because it is a `String`. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ParsersUrl

/** The address `index.json` is downloaded from. Same reason, different document. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IndexUrl

/** The `parsers.json` fetcher. Two instances of the same class: the document distinguishes them. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ParsersFetcher

/** The `index.json` fetcher. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IndexFetcher

/**
 * Downloads a signed document and hands it to a [SignedDocumentSink] to be verified.
 *
 * It decides nothing about validity: that belongs to the signature, and the signature is checked by
 * whoever knows how. Only what concerns the network lives here — when it is worth asking, how much
 * we are willing to download, and how a host that does not answer is described.
 */
class RemoteConfigFetcher(
    private val calls: Call.Factory,
    /**
     * Who verifies and stores. **It is the only thing distinguishing the two instances**: the same
     * class downloads `parsers.json` and `index.json`, with the same cap and the same window.
     */
    private val store: SignedDocumentSink,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val url: String,
) {

    /**
     * Asks for a new document only if the cached one has passed [REFRESH_INTERVAL].
     *
     * It is what app startup calls. The window exists because an app opened ten times in an hour has
     * no reason to ask ten times for the same file: the document changes when a store changes
     * markup, not every few minutes. The date is the cached file's, so a **refused** attempt does not
     * move it and the next launch retries — which is right, because a refused document is a document
     * somebody is about to correct.
     */
    suspend fun refreshIfStale(): FetchAttempt? {
        val storedAt = store.storedAt()
        val stale = storedAt == null || clock.now() - storedAt >= REFRESH_INTERVAL
        return if (stale) refresh() else null
    }

    /** Asks now, without looking at the cache's age. It is the button in Settings. */
    suspend fun refresh(): FetchAttempt = withContext(io) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        val bytes = try {
            calls.newCall(request).await().use { response ->
                when {
                    response.code == NOT_MODIFIED -> return@withContext record(FetchAttempt.NotModified(clock.now()))
                    !response.isSuccessful -> return@withContext record(
                        FetchAttempt.Unreachable(clock.now(), response.code),
                    )
                    // The body is read with a cap. A configuration document fits in a few tens of
                    // kilobytes; without a limit, a host answering with an infinite stream would
                    // fill the process's memory — and the host is no more under our control than
                    // the network carrying us to it.
                    else -> response.body.byteStream().readAtMost(MAX_DOCUMENT_BYTES)
                        ?: return@withContext record(FetchAttempt.Unreachable(clock.now(), response.code))
                }
            }
        } catch (_: IOException) {
            return@withContext record(FetchAttempt.Unreachable(clock.now(), httpCode = null))
        }

        store.accept(bytes)
    }

    private fun record(attempt: FetchAttempt): FetchAttempt {
        store.note(attempt)
        return attempt
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = read(chunk)
            if (read < 0) return buffer.toByteArray()
            if (buffer.size() + read > limit) return null
            buffer.write(chunk, 0, read)
        }
    }

    companion object {
        /** Six hours: the same scale as a scraped listing's TTL. */
        val REFRESH_INTERVAL: Duration = 6.hours

        const val MAX_DOCUMENT_BYTES: Int = 512 * 1024
        private const val CHUNK_BYTES: Int = 8 * 1024
        private const val NOT_MODIFIED: Int = 304
    }
}
