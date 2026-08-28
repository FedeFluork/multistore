package com.multistore.store.fdroid.index

import com.multistore.core.model.StoreAppRef
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexStaleness
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.StoreIndexSnapshot
import com.multistore.store.fdroid.FdroidRefs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * An F-Droid index sync in progress.
 *
 * The file is already downloaded and **already verified** against the signed hash: here it is only
 * read. The stream is lazy, so a cancelled sync stops reading instead of reaching the end to find
 * out — with 4,257 packages the difference is noticeable.
 *
 * `records()` does blocking I/O (decompression and disk reads): whoever consumes it must apply their
 * own dispatcher with `flowOn`. We do not do it here because dispatchers are injected, and a
 * `:store:*` module has nobody to get them from.
 */
class FdroidIndexSnapshot(
    private val download: VerifiedDownload,
    override val token: IndexToken,
    override val mode: IndexSyncMode,
    override val expectedRecords: Int?,
    override val expectedBytes: Long?,
    override val staleness: IndexStaleness? = null,
    private val projection: PackageProjection,
    private val json: Json = IndexStreamReader.DEFAULT_JSON,
) : StoreIndexSnapshot {

    override fun records(): Flow<IndexRecord> = flow {
        IndexStreamReader(download.source(), json).use { reader ->
            for (event in reader.events()) {
                currentCoroutineContext().ensureActive()
                when (event) {
                    is IndexStreamReader.Event.Repo -> emit(repoRecord(event.raw))
                    is IndexStreamReader.Event.Package -> packageRecord(event)?.let { emit(it) }
                }
            }
        }
    }

    private fun repoRecord(raw: JsonObject): IndexRecord.Catalog {
        val pruned = IndexStreamReader.pruneRepo(raw)
        return IndexRecord.Catalog(
            payload = json.encodeToString(JsonObject.serializer(), pruned),
            // On an incremental sync the `repo` block is a merge patch: on its own it does not
            // describe the complete taxonomy, so projecting it here would give a truncated list.
            // Whoever saves will merge the patch with what they have and ask for the projection
            // afterwards.
            info = if (mode == IndexSyncMode.FULL) IndexStreamReader.projectCatalog(pruned) else null,
        )
    }

    private fun packageRecord(event: IndexStreamReader.Event.Package): IndexRecord? {
        val ref: StoreAppRef = FdroidRefs.appRef(event.name)
        if (event.raw is JsonNull) return IndexRecord.Remove(ref)
        val obj = event.raw as? JsonObject ?: return null
        return when (mode) {
            IndexSyncMode.FULL -> {
                val pruned = LocalePruning.prunePackage(obj, keepFallback = true)
                IndexRecord.Full(
                    ref = ref,
                    // The package name goes inside the payload before the payload leaves here:
                    // whoever saves it sees an opaque string, and the only moment the name is still
                    // known is this one. See PackagePayload.
                    payload = json.encodeToString(
                        JsonObject.serializer(),
                        PackagePayload.withPackageName(pruned, event.name),
                    ),
                    detail = projection.project(event.name, pruned),
                )
            }

            IndexSyncMode.INCREMENTAL -> IndexRecord.Patch(
                ref = ref,
                payload = json.encodeToString(
                    JsonObject.serializer(),
                    PackagePayload.withPackageName(
                        LocalePruning.prunePackage(obj, keepFallback = false),
                        event.name,
                    ),
                ),
            )
        }
    }

    override fun close() {
        download.close()
    }
}

/** A sync with nothing to say: the remote index is the one we already have. */
class EmptyIndexSnapshot(
    override val token: IndexToken,
    override val staleness: IndexStaleness? = null,
) : StoreIndexSnapshot {
    override val mode: IndexSyncMode = IndexSyncMode.INCREMENTAL
    override val expectedRecords: Int = 0
    override val expectedBytes: Long = 0
    override fun records(): Flow<IndexRecord> = flow { }
    override fun close() = Unit
}
