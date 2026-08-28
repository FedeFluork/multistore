package com.multistore.store.fdroid.index

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `entry.json`: the signed file saying where the index is and what it is worth.
 *
 * 1,924 bytes, and it is the only document in the chain whose signature we verify. Everything else —
 * the 57 MB index, the diffs — is covered **by hash**, and the hash is in here. It is why verifying
 * 57 MB costs as much as verifying 2 KB.
 */
@Serializable
data class EntryDocument(
    /** Milliseconds. It is also the sync token: it identifies *this* version of the index. */
    val timestamp: Long,
    val version: Long,
    /**
     * The maximum tolerated age of the index, in days.
     *
     * It is an anti-rollback defence, not the diffs' retention window: a mirror going on serving an
     * old index would freeze security updates, and this field lets the client notice.
     */
    val maxAge: Int = DEFAULT_MAX_AGE_DAYS,
    val index: EntryFile,
    /**
     * The available diffs, indexed by **starting timestamp**.
     *
     * There are exactly 10, spaced roughly a day and a half apart: the useful window is about 15
     * days. A diff applies **only if** the stored timestamp matches one of these keys *exactly* — it
     * is not a range. Whoever syncs less often falls back on the full pull, and the code should
     * treat that as a normal case and not as a fault.
     */
    val diffs: Map<String, EntryFile> = emptyMap(),
) {
    /** The diff leading from [fromTimestamp] to [timestamp], if published. */
    fun diffFrom(fromTimestamp: Long): EntryFile? = diffs[fromTimestamp.toString()]

    companion object {
        const val DEFAULT_MAX_AGE_DAYS: Int = 14
    }
}

@Serializable
data class EntryFile(
    /** Path relative to the repository, with the leading slash: `/index-v2.json`. */
    val name: String,
    @SerialName("sha256") val sha256: String,
    val size: Long,
    val numPackages: Int = 0,
)
