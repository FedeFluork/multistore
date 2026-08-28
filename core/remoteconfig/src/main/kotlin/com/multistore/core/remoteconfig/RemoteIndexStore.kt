package com.multistore.core.remoteconfig

import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * The active index, and **it changes when a new one arrives**.
 *
 * ### The asymmetry with [RemoteConfigStore], which is this class's point
 *
 * `RemoteConfigStore` reads once at startup and does not change again for that process, and its note
 * explains why: the configuration reaches the adapters from their constructor, and making it
 * observable would let an adapter change selectors halfway through a search — with the page
 * downloaded under one configuration and interpreted under another.
 *
 * Here the opposite holds, and it is not inconsistency. The index is not *how* things are read, it
 * is *what* is shown: nobody interprets it, it is rendered. A screen showing the old list after
 * having just downloaded the new one is not cautious, it is broken — and the Home already has the
 * mechanism for receiving updates, because it is the one it receives F-Droid's local index with.
 *
 * The cached document is therefore read **lazily and observably**: a `StateFlow` starting from what
 * is on disk and which [accept] updates.
 */
class RemoteIndexStore(
    private val directory: File,
    private val documents: SignedDocuments,
    private val clock: Clock,
    private val json: Json = STRICT,
) : SignedDocumentSink {

    private val file: File get() = File(directory, IndexDocument.FILE_NAME)

    private val _status = MutableStateFlow(RemoteIndexStatus())
    val status: StateFlow<RemoteIndexStatus> = _status.asStateFlow()

    private val _document = MutableStateFlow(load())

    /** The active index. `null` until one has ever been accepted. */
    val document: StateFlow<IndexDocument?> = _document.asStateFlow()

    override fun storedAt(): Instant? = file.takeIf { it.isFile }
        ?.lastModified()
        ?.takeIf { it > 0 }
        ?.let(Instant::fromEpochMilliseconds)

    override fun accept(bytes: ByteArray): FetchAttempt {
        val now = clock.now()
        val attempt = when (val opened = documents.open(bytes)) {
            is OpenOutcome.Rejected -> FetchAttempt.Rejected(now, opened.reason)
            is OpenOutcome.Opened -> when (val document = decode(opened.document)) {
                null -> FetchAttempt.Rejected(now, ConfigRejection.MALFORMED_PAYLOAD)
                else -> when {
                    document.schemaVersion > IndexDocument.SUPPORTED_SCHEMA ->
                        FetchAttempt.Rejected(now, ConfigRejection.UNSUPPORTED_SCHEMA)
                    document.schemaVersion < 1 ->
                        FetchAttempt.Rejected(now, ConfigRejection.MALFORMED_PAYLOAD)
                    write(bytes) -> {
                        // **It applies immediately**, and this line is the whole difference from
                        // `RemoteConfigStore`. See the note at the head of the class.
                        _document.value = document
                        FetchAttempt.Accepted(now, document.schemaVersion)
                    }
                    else -> FetchAttempt.NotStored(now)
                }
            }
        }
        _status.update { it.copy(lastAttempt = attempt, entryCount = countOf(_document.value)) }
        return attempt
    }

    override fun note(attempt: FetchAttempt) {
        _status.update { it.copy(lastAttempt = attempt) }
    }

    private fun load(): IndexDocument? {
        val bytes = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null

        val opened = documents.open(bytes)
        if (opened is OpenOutcome.Rejected) {
            // This file was written by `accept`, which had already verified it. If it no longer
            // verifies, either somebody has written into `filesDir` or the key has changed: it is
            // deleted, because a cache that does not verify would never be replaced by itself — the
            // update would skip it, finding it recent. Same choice as `RemoteConfigStore`.
            file.delete()
            _status.update { it.copy(lastAttempt = FetchAttempt.Rejected(clock.now(), opened.reason)) }
            return null
        }

        val document = decode((opened as OpenOutcome.Opened).document)
        if (document == null || document.schemaVersion > IndexDocument.SUPPORTED_SCHEMA) {
            _status.update {
                it.copy(
                    lastAttempt = FetchAttempt.Rejected(
                        clock.now(),
                        if (document == null) ConfigRejection.MALFORMED_PAYLOAD else ConfigRejection.UNSUPPORTED_SCHEMA,
                    ),
                )
            }
            return null
        }
        _status.update { it.copy(entryCount = countOf(document)) }
        return document
    }

    private fun decode(text: String): IndexDocument? =
        runCatching { json.decodeFromString<IndexDocument>(text) }.getOrNull()

    private fun countOf(document: IndexDocument?): Int =
        (document?.popular?.size ?: 0) + (document?.recent?.size ?: 0)

    /** Atomic write: whoever reads at startup must not be able to find half a file. */
    private fun write(bytes: ByteArray): Boolean = runCatching {
        directory.mkdirs()
        val temporary = File(directory, "${IndexDocument.FILE_NAME}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "rename failed" }
        }
    }.isSuccess

    private companion object {
        /**
         * `ignoreUnknownKeys` **yes, here**, and the difference from `parsers.json` is deliberate.
         *
         * There an unknown key at the outermost level means a different schema, and `schemaVersion`
         * exists to declare it. Here the document is a list of entries, and an entry with an extra
         * field — which a future version of the pipeline might add before the app can read it — does
         * not change the meaning of the ones the app does know. Discarding the whole index for an
         * unknown field would mean an empty Home for whoever has not updated the app yet.
         */
        val STRICT: Json = Json { ignoreUnknownKeys = true }
    }
}

/** What the Home knows about the remote index: how much it carries, and how the last attempt went. */
data class RemoteIndexStatus(
    val entryCount: Int = 0,
    val lastAttempt: FetchAttempt? = null,
)
