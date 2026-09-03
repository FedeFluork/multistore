package com.multistore.store.fdroid.index

import com.multistore.core.model.Sha256
import com.multistore.core.network.http.StoreHttpClient
import com.multistore.store.api.StoreError
import com.multistore.store.api.StoreResult
import com.multistore.store.common.StoreErrors
import com.multistore.store.common.storeCall
import com.multistore.store.fdroid.FdroidConfig
import com.multistore.store.fdroid.FdroidPaths
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.Request
import okio.buffer
import okio.sink

/**
 * The index's network side: download, verify, deliver.
 *
 * The chain of trust, in full and in the order it has to be walked:
 *
 * 1. `entry.jar` (2.6 KB) is a signed JAR. The signature is verified and the certificate's
 *    fingerprint compared with the **pinned** one. If it does not match, everything is discarded:
 *    there is no "accept with a warning" mode.
 * 2. Inside is `entry.json`, which declares the index's name, size and **SHA-256**.
 * 3. The index is downloaded and verified against that hash before anything looks inside it.
 *
 * The result is that 57 MB are covered by the same signature that covers 2 KB. There is no need to
 * sign the index: signing its hash is enough.
 */
class FdroidIndexClient(
    private val config: FdroidConfig,
    private val http: StoreHttpClient,
    /** Where the temporaries go. On Android that is `cacheDir`: whoever knows Android decides. */
    private val workDir: File,
    private val verifier: JarSignatureVerifier = JarSignatureVerifier(config.signerFingerprint),
    private val json: Json = DEFAULT_JSON,
) {

    /** Downloads and verifies `entry.jar`, returning the document it contains. */
    suspend fun fetchEntry(): StoreResult<EntryDocument> = storeCall {
        val jar = File.createTempFile("fdroid-entry", ".jar", workDir)
        try {
            when (val downloaded = downloadTo(config.entryJarUrl, jar, maxBytes = ENTRY_JAR_MAX_BYTES)) {
                is DownloadOutcome.Failed -> return@storeCall StoreResult.Failure(downloaded.error)
                DownloadOutcome.Ok -> Unit
            }
            when (val verified = verifier.readVerifiedEntry(jar, FdroidPaths.ENTRY_JSON_ENTRY)) {
                is JarSignatureVerifier.Result.Verified ->
                    StoreResult.Success(json.decodeFromString<EntryDocument>(verified.content.decodeToString()))

                is JarSignatureVerifier.Result.WrongSigner -> StoreResult.Failure(
                    StoreErrors.parseFailure(
                        selector = SELECTOR_SIGNER,
                        snippet = "expected=${config.signerFingerprint.hex} found=${verified.actual?.hex}",
                    ),
                )

                is JarSignatureVerifier.Result.Tampered -> StoreResult.Failure(
                    StoreErrors.parseFailure(SELECTOR_SIGNATURE, verified.reason),
                )

                JarSignatureVerifier.Result.Unsigned -> StoreResult.Failure(
                    StoreErrors.parseFailure(SELECTOR_SIGNATURE, "entry.json is not signed"),
                )

                is JarSignatureVerifier.Result.Malformed -> StoreResult.Failure(
                    StoreErrors.parseFailure(SELECTOR_ENTRY_JAR, verified.reason),
                )
            }
        } finally {
            jar.delete()
        }
    }

    /**
     * Downloads an index file and verifies the SHA-256 declared in the signed `entry.json`.
     *
     * Whoever receives the [VerifiedDownload] must close it: behind it is a file in `cacheDir`.
     */
    suspend fun download(entryFile: EntryFile): StoreResult<VerifiedDownload> = storeCall {
        val expected = Sha256.parseOrNull(entryFile.sha256)
            ?: return@storeCall StoreResult.Failure(
                StoreErrors.parseFailure(SELECTOR_ENTRY_HASH, entryFile.sha256),
            )
        val target = File.createTempFile("fdroid-index", ".bin", workDir)
        var keep = false
        try {
            val url = config.repoFile(entryFile.name)
            val gzipped = when (
                val outcome = downloadTo(url, target, maxBytes = transferCap(entryFile.size), requestGzip = true)
            ) {
                is DownloadOutcome.Failed -> return@storeCall StoreResult.Failure(outcome.error)
                DownloadOutcome.Ok -> lastResponseWasGzipped
            }
            when (val mismatch = VerifiedDownload.verify(target, gzipped, expected, entryFile.size)) {
                null -> Unit

                // The hash is in a signed document: if it does not match, either the mirror is
                // serving something else or the file has been tampered with. In neither case do we
                // even look at it.
                is VerifiedDownload.Mismatch.Digest -> return@storeCall StoreResult.Failure(
                    StoreErrors.parseFailure(
                        selector = SELECTOR_INDEX_HASH,
                        snippet = "expected=${expected.hex} found=${mismatch.actual.hex}",
                    ),
                )

                is VerifiedDownload.Mismatch.TooLarge -> return@storeCall StoreResult.Failure(
                    StoreErrors.parseFailure(
                        selector = SELECTOR_PLAIN_SIZE,
                        snippet = "plaintext beyond the ${mismatch.limitBytes} declared bytes",
                    ),
                )
            }
            keep = true
            StoreResult.Success(VerifiedDownload(target, gzipped, expected))
        } finally {
            if (!keep) target.delete()
        }
    }

    private sealed interface DownloadOutcome {
        data object Ok : DownloadOutcome
        data class Failed(val error: StoreError) : DownloadOutcome
    }

    /**
     * True if the last response arrived compressed.
     *
     * By asking for `Accept-Encoding: gzip` explicitly, OkHttp stops decompressing by itself and
     * hands us the bytes as they are: it is the only way to **save** the file compressed instead of
     * re-expanding it to 57 MB on disk.
     */
    private var lastResponseWasGzipped: Boolean = false

    /**
     * Downloads into [target], **stopping** if the body exceeds [maxBytes].
     *
     * Without the cap, `writeAll` writes for as long as the server sends: the SHA-256 check comes
     * afterwards, and a broken or hostile mirror would fill the disk before anyone could say no. The
     * cap is not our estimate — for the index and the diffs it is the size the **signed document**
     * declares, so refusing what exceeds it is merely heeding the signature we have just verified.
     */
    private suspend fun downloadTo(
        url: String,
        target: File,
        maxBytes: Long,
        requestGzip: Boolean = false,
    ): DownloadOutcome {
        val builder = Request.Builder().url(url)
        if (requestGzip) builder.header("Accept-Encoding", "gzip")
        val response = http.executeUncached(builder.build())
        response.use {
            if (!it.isSuccessful) return DownloadOutcome.Failed(StoreErrors.fromResponse(it))
            lastResponseWasGzipped = it.header("Content-Encoding")?.equals("gzip", ignoreCase = true) == true
            val body = it.body ?: return DownloadOutcome.Failed(StoreError.Network(null, it.code))
            // Early exit when the server declares it itself: no bytes written. This is not the
            // defence — a server can lie or stay silent — it is just the honest case handled well.
            val declared = body.contentLength()
            if (declared > maxBytes) return oversize(url, declared, maxBytes)
            var written = 0L
            target.sink().buffer().use { sink ->
                val source = body.source()
                while (true) {
                    val read = source.read(sink.buffer, COPY_CHUNK_BYTES)
                    if (read == -1L) break
                    written += read
                    if (written > maxBytes) return oversize(url, written, maxBytes)
                    sink.emitCompleteSegments()
                }
            }
        }
        return DownloadOutcome.Ok
    }

    private fun oversize(url: String, seen: Long, limit: Long): DownloadOutcome =
        DownloadOutcome.Failed(
            StoreErrors.parseFailure(
                selector = SELECTOR_TRANSFER_SIZE,
                snippet = "${url.substringAfterLast('/')}: $seen bytes beyond the $limit cap",
            ),
        )

    /**
     * The cap on **transferred** bytes, given the plaintext size declared.
     *
     * `entry.json` declares the file's plaintext size (57,037,287 bytes for `index-v2.json`), while
     * we ask for `gzip` and receive about 18 MB. The margin covers the only case where the
     * compressed form can exceed the plain one: already incompressible data, where gzip adds a
     * header and a little overhead instead of removing anything.
     */
    private fun transferCap(plainSizeBytes: Long): Long =
        plainSizeBytes + plainSizeBytes / 100 + 1024

    /**
     * `internal` and not `private` because the **canary branches on these selectors**.
     *
     * A rotated pin arrives as `ParseFailure(SELECTOR_SIGNER, "expected=… found=…")`, and telling
     * that apart from a changed index schema is the difference between "confirm the rotation
     * outside this channel before widening the pin" and "teach the projection a new field". The
     * alternative was copies of these strings in the test source set, which would drift silently:
     * a renamed selector would fall through to the generic branch and the pin message would simply
     * stop appearing, with nothing going red. See `FdroidFailureDiagnosis`.
     */
    internal companion object {
        const val SELECTOR_ENTRY_JAR = "entry.jar"
        const val SELECTOR_SIGNER = "entry.jar/signer"
        const val SELECTOR_SIGNATURE = "entry.jar/signature"
        const val SELECTOR_ENTRY_HASH = "entry.json/index.sha256"
        const val SELECTOR_INDEX_HASH = "index-v2.json/sha256"
        /**
         * The two caps have two selectors, and that is not pedantry: the selector is what
         * `health_events` records to say *what* broke. "The transfer exceeded the declared size" and
         * "the served document, once expanded, is larger than it declares" are two different faults
         * — the second is consistent with a gzip bomb, the first is not — and telling them apart
         * costs one constant.
         */
        const val SELECTOR_TRANSFER_SIZE = "entry.json/index.size"
        const val SELECTOR_PLAIN_SIZE = "index-v2.json/size"

        /**
         * The cap for `entry.jar`, the only file in the chain whose size **nobody declares** — it is
         * the one declaring everyone else's. The real file is under 3 KB (`entry.json` is 1,924
         * bytes plus signature and manifest): a megabyte is a margin of over 300 times, so it is not
         * a limit that can be hit by accident.
         */
        const val ENTRY_JAR_MAX_BYTES = 1L * 1024 * 1024

        const val COPY_CHUNK_BYTES = 64L * 1024

        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}
