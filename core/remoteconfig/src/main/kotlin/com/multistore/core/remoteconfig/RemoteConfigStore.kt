package com.multistore.core.remoteconfig

import com.multistore.core.model.StoreId
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The accepted document, and what is known about it.
 *
 * ### It is read once, at startup, and does not change again for this process
 *
 * The configuration reaches the adapters through their constructor — that is how `:app` injects it —
 * so it holds for the whole life of the process. Making it observable would mean letting an adapter
 * change selectors halfway through a search, with the page downloaded under one configuration and
 * interpreted under another. A new document is downloaded when it arrives and becomes active on the
 * next restart, and the Settings screen says so in those words.
 *
 * The read is synchronous in the constructor, and it is a file of a few kilobytes: making it
 * asynchronous would mean the first adapter constructed may or may not find it ready, i.e. a
 * different configuration depending on who arrives first.
 */
class RemoteConfigStore(
    private val directory: File,
    private val documents: SignedDocuments,
    private val clock: Clock,
    private val json: Json = STRICT,
) : SignedDocumentSink {

    private val file: File get() = File(directory, FILE_NAME)

    private val _status = MutableStateFlow(RemoteConfigStatus())
    val status: StateFlow<RemoteConfigStatus> = _status.asStateFlow()

    /** The active document's overrides, per store. Empty when the compiled defaults apply. */
    private val overrides: Map<StoreId, JsonObject> = load()

    fun parsersFor(storeId: StoreId): JsonObject? = overrides[storeId]

    /**
     * The WebView filter's override, if the active document carries one.
     *
     * It is not per store — the list of advertising hosts belongs to none of the nine — so it does
     * not go through [overrides]. It lives in the same variable because it comes from the same
     * document and the same read: two reads would be two moments in which the file can differ.
     */
    private var webFilterPatch: JsonObject? = null

    fun webFilter(): JsonObject? = webFilterPatch

    /** When the cached document was written, or `null` if there is none. */
    override fun storedAt(): Instant? = file.takeIf { it.isFile }
        ?.lastModified()
        ?.takeIf { it > 0 }
        ?.let(Instant::fromEpochMilliseconds)

    /**
     * Verifies a freshly downloaded document and, if it holds, puts it in cache.
     *
     * **It does not touch the active configuration**: see the note at the head of the class. It
     * returns the outcome rather than a boolean because "rejected" has a reason, and that reason is
     * what the user reads when the configuration does not update.
     */
    override fun accept(bytes: ByteArray): FetchAttempt {
        val now = clock.now()
        val attempt = when (val opened = documents.open(bytes)) {
            is OpenOutcome.Rejected -> FetchAttempt.Rejected(now, opened.reason)
            is OpenOutcome.Opened -> {
                val document = runCatching { json.decodeFromString<ParsersDocument>(opened.document) }
                    .getOrNull()
                when {
                    document == null ->
                        FetchAttempt.Rejected(now, ConfigRejection.MALFORMED_PAYLOAD)
                    document.schemaVersion > ParsersDocument.SUPPORTED_SCHEMA ->
                        FetchAttempt.Rejected(now, ConfigRejection.UNSUPPORTED_SCHEMA)
                    document.schemaVersion < 1 ->
                        FetchAttempt.Rejected(now, ConfigRejection.MALFORMED_PAYLOAD)
                    write(bytes) -> FetchAttempt.Accepted(now, document.schemaVersion)
                    else -> FetchAttempt.NotStored(now)
                }
            }
        }
        _status.update { it.copy(lastAttempt = attempt) }
        return attempt
    }

    /** Records an outcome that produced no document: network down, 404, 304. */
    override fun note(attempt: FetchAttempt) {
        _status.update { it.copy(lastAttempt = attempt) }
    }

    internal fun noteIgnoredKeys(paths: List<String>) {
        if (paths.isEmpty()) return
        _status.update { it.copy(ignoredKeys = (it.ignoredKeys + paths).distinct().sorted()) }
    }

    internal fun noteRejectedStore(storeId: StoreId) {
        _status.update { it.copy(rejectedStores = (it.rejectedStores + storeId).distinct()) }
    }

    // --- reading -------------------------------------------------------------------------------

    /**
     * Reads the cached document. Every exit that is not success **says why**: a configuration that
     * does not apply and does not explain itself is worse than a missing one, because it resembles a
     * broken adapter.
     */
    private fun load(): Map<StoreId, JsonObject> {
        val bytes = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
            ?: return emptyMap()

        val opened = documents.open(bytes)
        if (opened is OpenOutcome.Rejected) {
            // This file was written by `accept`, which had already verified it: if it no longer
            // verifies, either somebody has written into `filesDir` or the key has changed. We fall
            // back to the compiled defaults **and delete it**, because a cache that does not verify
            // would never be replaced by itself: the update would skip it, finding it recent.
            file.delete()
            reject(opened.reason)
            return emptyMap()
        }

        val text = (opened as OpenOutcome.Opened).document
        val document = runCatching { json.decodeFromString<ParsersDocument>(text) }.getOrNull()
        if (document == null) {
            reject(ConfigRejection.MALFORMED_PAYLOAD)
            return emptyMap()
        }
        if (document.schemaVersion > ParsersDocument.SUPPORTED_SCHEMA) {
            reject(ConfigRejection.UNSUPPORTED_SCHEMA)
            return emptyMap()
        }

        webFilterPatch = document.webFilter

        val known = mutableMapOf<StoreId, JsonObject>()
        val unknown = mutableListOf<String>()
        document.stores.forEach { (wireName, patch) ->
            val storeId = StoreId.fromWireNameOrNull(wireName)
            if (storeId == null) unknown += wireName else known[storeId] = patch
        }

        _status.update {
            it.copy(
                active = ActiveConfig.Applied(
                    schemaVersion = document.schemaVersion,
                    generatedAt = document.generatedAt?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() },
                    storedAt = storedAt(),
                    stores = known.keys.toSet(),
                ),
                unknownStores = unknown.sorted(),
            )
        }
        return known
    }

    private fun reject(reason: ConfigRejection) {
        _status.update { it.copy(lastAttempt = FetchAttempt.Rejected(clock.now(), reason)) }
    }

    /** Atomic write: whoever reads at startup must not be able to find half a file. */
    private fun write(bytes: ByteArray): Boolean = runCatching {
        directory.mkdirs()
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            // `renameTo` fails if the destination exists on some filesystems: it is removed and
            // retried once, then we give up leaving the previous one in cache.
            file.delete()
            check(temporary.renameTo(file)) { "rename failed" }
        }
    }.isSuccess

    companion object {
        const val FILE_NAME: String = "parsers.json"

        /**
         * No `ignoreUnknownKeys`, and that is not an oversight.
         *
         * The document is produced by our pipeline and verified with our key: a key this version
         * does not know, at the outermost level, means a different schema — and that is what
         * `schemaVersion` exists to declare. The tolerance lives one level down, inside the
         * per-store overrides, where [JsonOverride] discards and **counts** unknown keys instead of
         * ignoring them silently.
         */
        val STRICT: Json = Json
    }
}
