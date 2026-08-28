package com.multistore.core.data.repository

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.data.mapper.toAppError
import com.multistore.core.data.mapper.toRows
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.database.dao.CatalogDao
import com.multistore.core.database.dao.IndexDao
import com.multistore.core.database.dao.ListingWrite
import com.multistore.core.database.entity.StoreAntiFeatureEntity
import com.multistore.core.database.entity.StoreCategoryEntity
import com.multistore.core.database.entity.StoreIndexEntryEntity
import com.multistore.core.database.entity.StoreIndexStateEntity
import com.multistore.core.model.AntiFeature
import com.multistore.core.model.Category
import com.multistore.core.model.StoreId
import com.multistore.store.api.IndexRecord
import com.multistore.store.api.IndexSyncMode
import com.multistore.store.api.IndexToken
import com.multistore.store.api.IndexedStoreAdapter
import com.multistore.store.api.StoreCatalogInfo
import com.multistore.store.api.StoreIndexSnapshot
import com.multistore.store.api.StoreResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The index sync: where the adapter's stream becomes database rows.
 *
 * ### The three decisions that make this class correct rather than merely working
 *
 * **It writes in batches, not in a single transaction.** A minute-long transaction blocks every
 * other write, and among those writes is the recording of an installation in progress. The batch is
 * [BATCH_SIZE] entries: large enough not to pay an fsync per package, small enough to let the others
 * through.
 *
 * **The token is written last, after everything else has committed.** Writing it first means,
 * after a process killed halfway, believing we have an index we do not. The opposite consequence — a
 * killed process leaves the old token and a half-finished state — is harmless for a precise reason: a
 * JSON merge patch is **idempotent**, because it describes absolute values and not differences.
 * Applying the same diff twice to an already updated base gives the same result, so restarting from
 * the old token mends the tear instead of worsening it.
 *
 * **In full mode what did not appear is deleted.** Without that, withdrawn packages would stay in the
 * list forever. The list to delete is obtained by starting from the ids already present and removing
 * those the stream names: it is the same memory as a set of "seen", but it returns the difference
 * directly.
 */
@Singleton
internal class StoreIndexRepositoryImpl @Inject constructor(
    private val registry: StoreRegistry,
    private val indexDao: IndexDao,
    private val catalogDao: CatalogDao,
    private val health: StoreHealthRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StoreIndexRepository {

    override fun observeState(storeId: StoreId): Flow<IndexState?> =
        indexDao.observeState(storeId).map { it?.toState() }

    override suspend fun state(storeId: StoreId): IndexState? =
        withContext(io) { indexDao.state(storeId)?.toState() }

    override fun observeTaxonomy(storeId: StoreId): Flow<StoreTaxonomy> =
        indexDao.observeCategories(storeId).map { categories ->
            StoreTaxonomy(
                categories = categories.map { Category(it.categoryId, it.name, it.appCount) },
                antiFeatures = indexDao.antiFeatures(storeId).map {
                    AntiFeature(id = it.antiFeatureId, name = it.name, description = it.description)
                },
            )
        }

    override suspend fun taxonomy(storeId: StoreId): StoreTaxonomy = withContext(io) {
        StoreTaxonomy(
            categories = indexDao.categories(storeId).map { Category(it.categoryId, it.name, it.appCount) },
            antiFeatures = indexDao.antiFeatures(storeId).map {
                AntiFeature(id = it.antiFeatureId, name = it.name, description = it.description)
            },
        )
    }

    override suspend fun sync(
        storeId: StoreId,
        force: Boolean,
        onProgress: (IndexSyncProgress) -> Unit,
    ): Outcome<IndexSyncReport> = withContext(io) {
        val adapter = registry.indexed(storeId)
            ?: return@withContext Outcome.Failure(StoreIndexRepository.NOT_INDEXED)

        val profile = StoreIndexRepository.currentPruningProfile()
        val stored = indexDao.state(storeId)
        // The token is offered to the store only if what we have is still interpretable: after a
        // change in the app's languages the stored payload is pruned by another criterion, and a diff
        // applied to that base would leave the new language empty forever.
        val usableToken = stored
            ?.takeIf { !force && !it.toState().needsFullResync(profile) }
            ?.let { IndexToken(it.indexToken) }

        val snapshot = when (val opened = adapter.openIndex(usableToken)) {
            is StoreResult.Success -> opened.value
            is StoreResult.Failure -> {
                // A diagnostic event, **not** `recordFailure`. The difference is which consequence is
                // wanted: `recordFailure` feeds the circuit breaker, and the breaker on a local-index
                // store would govern something different from what broke — the fallback search, which
                // on F-Droid talks to `search.f-droid.org`, a host **separate** from the mirror's.
                // Five mirror failures would switch off a search that may well work.
                health.recordEvent(
                    storeId = storeId,
                    kind = SYNC_FAILED_EVENT,
                    detail = opened.error::class.simpleName,
                )
                return@withContext Outcome.Failure(opened.error.toAppError())
            }

            StoreResult.Unsupported -> return@withContext Outcome.Failure(StoreIndexRepository.NOT_INDEXED)
        }

        // Staleness is recorded **before** consuming: if the stream breaks halfway, the fact that the
        // mirror is stalled stays written anyway, and it is precisely in the broken case that it needs
        // knowing.
        snapshot.staleness?.takeIf { it.exceeded }?.let {
            health.recordEvent(
                storeId = storeId,
                kind = STALE_INDEX_EVENT,
                selector = "eta=${it.age.inWholeDays}g maxAge=${it.maxAge.inWholeDays}g",
            )
        }

        try {
            snapshot.use { consume(adapter, storeId, it, profile, stored?.catalogPayload, onProgress) }
                // A successful sync used to leave no trace in the health log, and the defect stayed
                // invisible until there was a report showing it: on a device with 4,269 apps in the
                // catalogue, F-Droid came out as `lastSuccess=never`. For a local-index store the sync
                // **is** the only request made: without this line its health counter never moves.
                .also { if (it is Outcome.Success) health.recordSuccess(storeId) }
        } catch (cancellation: CancellationException) {
            // A cancelled sync is not a fault, and it matters that it does not write the token: what
            // has already committed stays valid, the rest will arrive next time.
            throw cancellation
        } catch (failure: Exception) {
            Outcome.Failure(AppError.Unexpected(failure))
        }
    }

    private suspend fun consume(
        adapter: IndexedStoreAdapter,
        storeId: StoreId,
        snapshot: StoreIndexSnapshot,
        profile: String,
        storedCatalog: String?,
        onProgress: (IndexSyncProgress) -> Unit,
    ): Outcome<IndexSyncReport> {
        val now = clock.now()
        val mode = snapshot.mode
        val ttl = adapter.capabilities.listingTtl

        // In full mode this set starts from what is there and empties as the stream confirms the
        // entries: at the end it contains exactly what has to be deleted.
        val unseen: MutableSet<String> =
            if (mode == IndexSyncMode.FULL) indexDao.entryIds(storeId).toMutableSet() else mutableSetOf()

        var catalogPayload: String? = storedCatalog
        var catalogInfo: StoreCatalogInfo? = null
        var written = 0
        var removed = 0
        var processed = 0

        val pendingEntries = mutableListOf<StoreIndexEntryEntity>()
        val pendingListings = mutableListOf<ListingWrite>()
        val pendingRemovals = mutableListOf<String>()
        // A patch's "before": the batch's entries are re-read in bulk, not one by one.
        val pendingPatches = mutableListOf<Pair<String, String>>()

        suspend fun flush() {
            if (pendingPatches.isNotEmpty()) {
                val previous = indexDao.entries(storeId, pendingPatches.map { it.first })
                    .associate { it.entryId to it.payload }
                for ((entryId, patch) in pendingPatches) {
                    val merged = adapter.mergeEntry(previous[entryId], patch)
                    if (merged == null) {
                        // A merge patch reducing the entry to nothing is a deletion: it is half of
                        // what a diff does, and treating it as an "empty payload" would leave an app
                        // that no longer exists in the list.
                        pendingRemovals += entryId
                        continue
                    }
                    pendingEntries += StoreIndexEntryEntity(storeId, entryId, merged, now)
                    adapter.projectEntry(merged)?.let { pendingListings += it.toRows(now, ttl) }
                }
                pendingPatches.clear()
            }
            if (pendingEntries.isNotEmpty()) {
                indexDao.upsertEntries(pendingEntries)
                written += pendingEntries.size
                pendingEntries.clear()
            }
            if (pendingListings.isNotEmpty()) {
                catalogDao.saveListings(pendingListings)
                pendingListings.clear()
            }
            if (pendingRemovals.isNotEmpty()) {
                indexDao.deleteEntries(storeId, pendingRemovals)
                catalogDao.deleteListings(storeId, pendingRemovals)
                removed += pendingRemovals.size
                pendingRemovals.clear()
            }
        }

        snapshot.records().collect { record ->
            when (record) {
                is IndexRecord.Full -> {
                    unseen.remove(record.ref.value)
                    pendingEntries += StoreIndexEntryEntity(storeId, record.ref.value, record.payload, now)
                    record.detail?.let { pendingListings += it.toRows(now, ttl) }
                }

                is IndexRecord.Patch -> {
                    unseen.remove(record.ref.value)
                    pendingPatches += record.ref.value to record.payload
                }

                is IndexRecord.Remove -> {
                    unseen.remove(record.ref.value)
                    pendingRemovals += record.ref.value
                }

                is IndexRecord.Catalog -> {
                    // In full mode the block arrives whole and replaces; in incremental it is a merge
                    // patch, and replacing it would delete every category that diff does not name.
                    // Hence going through `mergeEntry`, which is the same function used for the
                    // entries because it is the same format.
                    catalogPayload = when (mode) {
                        IndexSyncMode.FULL -> record.payload
                        IndexSyncMode.INCREMENTAL -> adapter.mergeEntry(catalogPayload, record.payload)
                    }
                    catalogInfo = record.info
                        ?: catalogPayload?.let(adapter::projectCatalog)
                }
            }
            processed++
            if (pendingEntries.size + pendingPatches.size + pendingRemovals.size >= BATCH_SIZE) {
                flush()
                onProgress(IndexSyncProgress(mode, processed, snapshot.expectedRecords))
            }
        }
        flush()

        if (mode == IndexSyncMode.FULL && unseen.isNotEmpty()) {
            unseen.chunked(DELETE_CHUNK).forEach { chunk ->
                indexDao.deleteEntries(storeId, chunk)
                catalogDao.deleteListings(storeId, chunk)
            }
            removed += unseen.size
        }
        if (removed > 0) catalogDao.deleteOrphanApps()

        catalogInfo?.let { info -> saveTaxonomy(storeId, info, replace = mode == IndexSyncMode.FULL) }

        // --- Only now the token ---------------------------------------------------------------
        // Everything preceding has already committed. If the process dies before this line, the token
        // stays the old one and the next sync repeats work already done: costly, not wrong. The
        // opposite order would be wrong and silent.
        val entryCount = indexDao.entryCount(storeId)
        indexDao.upsertState(
            StoreIndexStateEntity(
                storeId = storeId,
                indexToken = snapshot.token.value,
                syncedAt = now,
                pruningProfile = profile,
                entryCount = entryCount,
                catalogPayload = catalogPayload,
            ),
        )
        onProgress(IndexSyncProgress(mode, processed, snapshot.expectedRecords))

        return Outcome.Success(
            IndexSyncReport(
                storeId = storeId,
                mode = mode,
                written = written,
                removed = removed,
                token = snapshot.token.value,
                upToDate = processed == 0,
                staleIndex = snapshot.staleness?.exceeded == true,
            ),
        )
    }

    /**
     * Writes the taxonomies, **counting** how many apps each category has.
     *
     * The count does not come from the store: F-Droid publishes 108 categories and declares for none
     * of them how many apps it contains. Without counting them, the only possible order is
     * alphabetical, which puts "AI Chat" and "Alarm Clock" ahead of "System" and "Internet" — i.e.
     * makes any truncated list useless.
     *
     * It has to be done after the listings have been written, which is why this call sits at the end
     * of the sync and not where the catalogue record arrives.
     */
    private suspend fun saveTaxonomy(storeId: StoreId, info: StoreCatalogInfo, replace: Boolean) {
        if (replace) {
            indexDao.clearCategories(storeId)
            indexDao.clearAntiFeatures(storeId)
        }
        if (info.categories.isNotEmpty()) {
            indexDao.upsertCategories(
                info.categories.map { category ->
                    StoreCategoryEntity(
                        storeId = storeId,
                        categoryId = category.id,
                        name = category.name,
                        // The count is done **here** and not on every opening of the Home: it is 108
                        // scans over 4,269 rows, measured at about a second on the device. Once per
                        // sync, inside an operation lasting forty, it goes unnoticed; on every opening
                        // of the Home it does not.
                        appCount = catalogDao.categoryCount(storeId, category.id),
                    )
                },
            )
        }
        if (info.antiFeatures.isNotEmpty()) {
            indexDao.upsertAntiFeatures(
                info.antiFeatures.map {
                    StoreAntiFeatureEntity(storeId, it.id, it.name, it.description)
                },
            )
        }
    }

    private fun StoreIndexStateEntity.toState() = IndexState(
        storeId = storeId,
        token = indexToken,
        syncedAt = syncedAt,
        entryCount = entryCount,
        pruningProfile = pruningProfile,
    )

    private companion object {
        /**
         * How many entries per transaction.
         *
         * 500 packages are about 6 MB of pruned payload: a transaction lasting tens of milliseconds,
         * not a minute. On a 4,257-entry index that is nine batches, i.e. nine windows in which
         * another write can get through.
         */
        const val BATCH_SIZE = 500

        /** SQLite has a cap on a query's parameters: deletions are split up. */
        const val DELETE_CHUNK = 400

        /**
         * The `kind` staleness ends up under in `health_events`.
         *
         * Diagnostics is local and exportable by the user: this line is what makes it possible to
         * answer "how long has this store given me nothing new?" without asking a server.
         */
        const val STALE_INDEX_EVENT = "index_stale"

        /** The mirror did not answer. Diagnostic: it does not feed the breaker, see above. */
        const val SYNC_FAILED_EVENT = "index_sync_failed"
    }
}
