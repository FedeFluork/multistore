package com.multistore.core.data.repository

import com.multistore.core.common.result.AppError
import com.multistore.core.common.result.Outcome
import com.multistore.core.model.AntiFeature
import com.multistore.core.model.Category
import com.multistore.core.model.LocalizedText
import com.multistore.core.model.StoreId
import com.multistore.store.api.IndexSyncMode
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * A store's local index state, as the rest of the app sees it.
 *
 * [pruningProfile] is not an internal detail: it records **which languages** the index was pruned
 * with. If one day the app gains a sixth language, the stored profile no longer matches the current
 * one and the next sync has to start from scratch — otherwise that language would stay empty forever
 * on every app already downloaded, and nobody would know why.
 */
data class IndexState(
    val storeId: StoreId,
    val token: String,
    val syncedAt: Instant,
    val entryCount: Int,
    val pruningProfile: String,
) {
    /** `true` if the index was pruned with a different set of languages from the current one. */
    fun needsFullResync(currentProfile: String): Boolean = pruningProfile != currentProfile
}

/** A sync's progress, for the bar and for the log. */
data class IndexSyncProgress(
    val mode: IndexSyncMode,
    val processed: Int,
    /** `null` where the store does not declare how many entries it is sending. */
    val expected: Int?,
)

/** What happened in a successful sync. */
data class IndexSyncReport(
    val storeId: StoreId,
    val mode: IndexSyncMode,
    val written: Int,
    val removed: Int,
    val token: String,
    /** `true` when the store answered "you already have everything": no writes, no error. */
    val upToDate: Boolean,
    /**
     * The served index is older than the **store itself** declares acceptable.
     *
     * It is not a sync error — the data is authentic and has been written — but it is the only signal
     * distinguishing "the catalogue is up to date" from "this mirror is stalled". Anti-rollback does
     * not see it: that compares against what we had, and on a first sync there is no before.
     */
    val staleIndex: Boolean = false,
)

/** The taxonomies the store publishes already localised, read from the local index. */
data class StoreTaxonomy(
    val categories: List<Category> = emptyList(),
    val antiFeatures: List<AntiFeature> = emptyList(),
) {
    fun antiFeature(id: String): AntiFeature? = antiFeatures.firstOrNull { it.id == id }

    fun categoryName(id: String, preferredTags: List<String>): String =
        categories.firstOrNull { it.id == id }?.displayName(preferredTags) ?: id
}

/**
 * A store's local index: how it is synced and what is known about it.
 *
 * It only concerns the stores publishing a complete index — among the nine, today, F-Droid. For the
 * others there is nothing to sync and [sync] answers that the store is not indexed.
 */
interface StoreIndexRepository {

    fun observeState(storeId: StoreId): Flow<IndexState?>

    suspend fun state(storeId: StoreId): IndexState?

    /** The taxonomies the store publishes, as an observable: they change only with a sync. */
    fun observeTaxonomy(storeId: StoreId): Flow<StoreTaxonomy>

    suspend fun taxonomy(storeId: StoreId): StoreTaxonomy

    /**
     * Syncs the index, incrementally if the store can and fully otherwise.
     *
     * @param force ignore the stored token and repeat a full sync. It is needed after a pruning
     * profile change and as a manual remedy for a suspect index.
     */
    suspend fun sync(
        storeId: StoreId,
        force: Boolean = false,
        onProgress: (IndexSyncProgress) -> Unit = {},
    ): Outcome<IndexSyncReport>

    /** The error returned to whoever asks to sync a store with no index. */
    companion object {
        val NOT_INDEXED: AppError = AppError.Parse(
            what = "searchSource",
            detail = "the store does not publish a complete index",
        )

        /**
         * The current pruning profile: the showable languages, sorted.
         *
         * Sorted because it has to be comparable by equality: a `Set` iterated in a different order
         * would produce two different profiles for the same configuration, and every launch would
         * trigger a full 18 MB resync.
         */
        fun currentPruningProfile(): String =
            LocalizedText.DISPLAYABLE_TAGS.sorted().joinToString(separator = ",")
    }
}
