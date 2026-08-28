package com.multistore.store.api

import com.multistore.core.model.AntiFeature
import com.multistore.core.model.Category
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreListingDetail
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * A store that publishes a **complete index**, instead of answering one search at a time.
 *
 * Among the nine only F-Droid does, but the contract is generic because the difference is
 * structural rather than incidental: where an index exists, search can be offline, instant and
 * free of rate limit, and the results do not depend on the site being reachable at that moment.
 *
 * ### Why the adapter does not write to disk
 *
 * An index has to be kept, and keeping it means a database — which a `:store:*` module cannot
 * see. The division adopted here keeps the two apart without giving up either:
 *
 *  - the **adapter** knows how to read the store's format and how to merge an incremental update
 *    with what was there before ([mergeEntry]), but receives the "before" as an argument and
 *    returns the "after" as a value. It stays a pure function;
 *  - `:core:data` knows where to put the bytes, but not what they mean.
 *
 * The diff's format therefore stays an adapter detail — and for F-Droid it is anything but an
 * obvious one: the diff is a JSON merge patch in which `null` deletes, whether a whole package or
 * a single version.
 */
interface IndexedStoreAdapter : StoreAdapter {

    /**
     * Opens a stream over the index.
     *
     * [current] is the token returned by the last successful sync, or `null` on first launch. The
     * adapter decides for itself whether it can serve an incremental: for F-Droid the token is the
     * index's timestamp, and a diff exists **only if that timestamp matches exactly** one of the
     * 10 published keys. It is not a continuous window, so callers must never assume an
     * incremental is available.
     */
    suspend fun openIndex(current: IndexToken?): StoreResult<StoreIndexSnapshot>

    /**
     * Applies an incremental update to the stored payload.
     *
     * Returns the new payload, or `null` if the update deletes the entry. A pure function: no
     * network, no I/O.
     */
    fun mergeEntry(previous: String?, patch: String): String?

    /**
     * Interprets a stored payload and derives the listing from it.
     *
     * Needed after a [mergeEntry], and needed again when the user changes language: the projection
     * depends on the language, the payload does not. Returns `null` if the payload describes
     * something we cannot install (for F-Droid: the three OTA `.zip` entries).
     */
    fun projectEntry(payload: String): StoreListingDetail?

    /**
     * Interprets the repository metadata payload.
     *
     * Categories and anti-features arrive from the store already translated, and change when the
     * store changes them: keeping them in `strings.xml` would mean a release per new category. The
     * payload follows the same rules as the entries — kept raw and updated with [mergeEntry] —
     * because a diff's `repo` block is a merge patch too: replacing it wholesale would erase
     * whatever that diff does not name.
     */
    fun projectCatalog(payload: String): StoreCatalogInfo?
}

/** Repository metadata: the taxonomies the store publishes already localised. */
data class StoreCatalogInfo(
    val categories: List<Category>,
    val antiFeatures: List<AntiFeature>,
)

/**
 * The token identifying the index's state at the store.
 *
 * Opaque like [StoreAppRef]: for F-Droid it is the index's timestamp, for another store it might
 * be an ETag.
 */
@JvmInline
value class IndexToken(val value: String)

/** A sync in progress. It must be closed: behind it is a connection or a temporary file. */
interface StoreIndexSnapshot : AutoCloseable {

    /** The token to store **only after** the stream has been consumed in full. */
    val token: IndexToken

    val mode: IndexSyncMode

    /** How many entries to expect, where the store declares it. Only for the progress bar. */
    val expectedRecords: Int?

    /** How many bytes will be downloaded, if known: needed to confirm on a metered network. */
    val expectedBytes: Long?

    /**
     * How stale the served index is against what the store itself tolerates, where the store
     * declares it. `null` if it does not.
     */
    val staleness: IndexStaleness? get() = null

    fun records(): Flow<IndexRecord>
}

/**
 * The age of the served index, compared with what the **store** declares acceptable.
 *
 * Not a duplicate of anti-rollback, and the difference matters: rollback notices an index older
 * than the one **we** had, so it does not protect a fresh installation, which has no "before" to
 * compare against. This notices a **stalled** mirror — one serving the same authentic index to
 * everyone — which is exactly the case where security updates stop arriving with nothing failing.
 *
 * It does not block: an old but authentic index is still more useful than no index, and on a first
 * sync it is all the user has. It is recorded, and whoever shows the catalogue can say so.
 */
data class IndexStaleness(val age: Duration, val maxAge: Duration) {
    val exceeded: Boolean get() = age > maxAge
}

enum class IndexSyncMode {
    /** The index arrives whole: whatever does not appear must be deleted. */
    FULL,

    /** Only the differences arrive: whatever does not appear stays as it is. */
    INCREMENTAL,
}

/** One entry of the sync stream. */
sealed interface IndexRecord {

    /**
     * An entry's complete content.
     *
     * It carries both the opaque [payload] to store and the already-projected [detail]: the
     * adapter has just finished reading it, and making whoever saves it re-analyse it would mean
     * parsing 4,257 packages twice.
     */
    data class Full(
        val ref: StoreAppRef,
        val payload: String,
        val detail: StoreListingDetail?,
    ) : IndexRecord

    /** An update to merge with the stored payload, via [IndexedStoreAdapter.mergeEntry]. */
    data class Patch(val ref: StoreAppRef, val payload: String) : IndexRecord

    /** The entry no longer exists. */
    data class Remove(val ref: StoreAppRef) : IndexRecord

    /**
     * Repository metadata: categories and anti-features, with the names already localised **by
     * the store**.
     *
     * It arrives before the entries. These are not interface strings — they are data that changes
     * when the store changes it — so they do not live in `strings.xml`: putting them there would
     * mean a release every time F-Droid adds a category.
     */
    data class Catalog(
        val payload: String,
        val info: StoreCatalogInfo?,
    ) : IndexRecord
}
