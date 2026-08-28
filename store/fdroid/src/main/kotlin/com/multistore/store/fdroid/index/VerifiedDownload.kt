package com.multistore.store.fdroid.index

import com.multistore.core.model.Sha256
import java.io.Closeable
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import okio.BufferedSource
import okio.GzipSource
import okio.buffer
import okio.source

/**
 * A file downloaded and **already verified** against the hash published in the signed `entry.json`.
 *
 * ### Why a temporary file instead of parsing while downloading
 *
 * Parsing in streaming and verifying the hash at the end means having already written unverified
 * data to the database: if the hash does not match, either a transaction is held open for the whole
 * download — blocking every other write for a minute — or we are already exposed. Verifying first
 * costs a temporary file and solves both problems.
 *
 * The file is kept **compressed**: 17.8 MB instead of 57. It costs one extra decompression (one to
 * verify the hash, one to parse), which is about two seconds of CPU against 39 MB of `cacheDir`
 * space. On a phone, space is the tighter constraint.
 */
class VerifiedDownload internal constructor(
    private val file: File,
    private val gzipped: Boolean,
    val expectedSha256: Sha256,
) : Closeable {

    /** A stream over the **plaintext** content, decompressing on the fly if needed. */
    fun source(): BufferedSource {
        val raw = file.source()
        return if (gzipped) GzipSource(raw).buffer() else raw.buffer()
    }

    val compressedSizeBytes: Long get() = file.length()

    override fun close() {
        file.delete()
    }

    /** The two ways a downloaded file can fail to be what the signed document describes. */
    sealed interface Mismatch {

        /** A plausibly identical length, different content. */
        data class Digest(val actual: Sha256) : Mismatch

        /**
         * The **plaintext** content exceeds the declared bytes.
         *
         * It is a case of its own and not a different digest, because it is discovered halfway
         * through and the reason to stop is different: a file longer than declared can never have
         * the right hash, so reading it to the end is certainly wasted work. On the compressed path
         * it is also the only defence there is: an 18 MB gzip archive can expand into gigabytes, and
         * the cap on transferred bytes does not see that.
         */
        data class TooLarge(val limitBytes: Long) : Mismatch
    }

    companion object {

        /**
         * Computes the plaintext content's SHA-256 and compares it with the expected one.
         *
         * @param maxPlainBytes the plaintext bytes the signed document declares. One byte beyond is
         * read, so that "exactly as long as declared" can be told from "longer" without reading the
         * rest.
         * @return `null` if it matches, otherwise how it does not.
         */
        fun verify(file: File, gzipped: Boolean, expected: Sha256, maxPlainBytes: Long): Mismatch? {
            val digest = MessageDigest.getInstance("SHA-256")
            val plain = if (gzipped) {
                GzipSource(file.source()).buffer().inputStream()
            } else {
                file.inputStream()
            }
            var read = 0L
            DigestInputStream(plain.buffered(), digest).use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    read += n
                    if (read > maxPlainBytes) return Mismatch.TooLarge(maxPlainBytes)
                }
            }
            val actual = Sha256.ofBytes(digest.digest())
            return if (actual == expected) null else Mismatch.Digest(actual)
        }

        private const val BUFFER_BYTES = 64 * 1024
    }
}
