package com.multistore.core.database

import android.content.Context
import com.multistore.core.common.coroutine.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Returning to the device the space the database no longer uses.
 *
 * ### The automatic policy, and what the measurement decided it does **not** cover
 *
 * Measured 26/08/2026, on a device with the F-Droid index synced:
 *
 * ```
 * store_index_entries   37,384,192   57%      4,269 rows, all f-droid
 * store_listings        12,881,920   19%      4,268 of 4,279 rows are f-droid
 * app_versions           6,352,896    9%
 * listing_screenshots    3,403,776    5%
 * expired rows:         0 of 4,279
 * ```
 *
 * The two rows that count are the last and the second. **No row had expired**, and not because
 * the cache was fresh: because what fills the database is a signed index with a seven-day TTL,
 * resynced in full. A TTL purge would have recovered the eleven rows of the other stores — a few
 * kilobytes — and would never have touched the 95%.
 *
 * Hence the shape of the policy: **an indexed store's downloaded catalogue is not cache that
 * ages**, it is a catalogue the user asked to download. The automatic purge covers expired,
 * unclaimed scraped listings (`CatalogDao.deleteExpiredListings`); the index is thrown away by a
 * button that says what redoing it costs. Deleting it unasked would mean a search that stops
 * finding things with nobody having asked for anything.
 *
 * ### Why the automatic purge does **not** compact, and the button does
 *
 * Two operations with opposite costs. `VACUUM` rewrites the whole database: doing it on every
 * launch to recover the pages of eleven deleted rows would mean rewriting 62 MB to free a few tens
 * of kilobytes, in a file the next sync refills anyway — the free pages inside the file are
 * exactly what it needs. The button exists because the user asked for **space back**, and there
 * not compacting would mean a button announcing it freed space without having done so.
 *
 * ### The two operations, and why both are needed
 *
 * - **`VACUUM`** rewrites the database, compacting it. SQLite does not return the pages freed by a
 *   deletion on its own: it keeps them for reuse, which is right during use and useless when the
 *   user is asking for space.
 * - **`wal_checkpoint(TRUNCATE)`** folds the write-ahead log back into the database and **zeroes
 *   the `-wal` file**.
 *
 * ### The order, and how it was discovered
 *
 * `VACUUM` first, checkpoint **after**. The first version did the opposite, with a confident
 * comment explaining why. Tried on device, on the real 64.8 MB database, the result was:
 *
 * ```
 * before: multistore.db 64,790,528   -wal    524,288
 * after:  multistore.db 64,434,176   -wal 64,811,752
 * ```
 *
 * In WAL mode `VACUUM` **rewrites the entire database inside the write-ahead log**: done after the
 * checkpoint, it leaves a `-wal` as large as the database. Total occupancy went from 65.3 MB to
 * 129.4 MB — the operation meant to free space doubled it, and the number reported to the user,
 * being negative and therefore clamped to zero, said "nothing to recover". The worst possible
 * defect: it does the opposite of what it promises and announces it as a success.
 *
 * A test on a small database did not see it, because it looked at totals and with a few megabytes
 * the sums still worked out. `DatabaseMaintenanceTest` now measures the `-wal` on its own.
 */
@Singleton
class DatabaseMaintenance @Inject constructor(
    private val database: MultiStoreDatabase,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /** How much the database occupied — main file plus WAL and shared index — before and after. */
    data class Reclaimed(val bytesBefore: Long, val bytesAfter: Long) {
        val freedBytes: Long get() = (bytesBefore - bytesAfter).coerceAtLeast(0L)
    }

    /**
     * How much the database occupies **right now**.
     *
     * It counts `-wal` and `-shm` too: during a sync the write-ahead log grows on its own, and it
     * is half of what [compact] frees. Looking at `multistore.db` alone would show the user a
     * smaller number than the system attributes to them.
     */
    suspend fun sizeBytes(): Long = withContext(io) { occupiedBytes() }

    suspend fun compact(): Reclaimed = withContext(io) {
        val before = occupiedBytes()
        val db = database.openHelper.writableDatabase
        db.execSQL("VACUUM")
        // A `PRAGMA` returns rows: with `execSQL` it would not actually run. The cursor is
        // closed immediately, but it has to be opened.
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        Reclaimed(bytesBefore = before, bytesAfter = occupiedBytes())
    }

    /**
     * The space **actually** occupied, counting the service files too.
     *
     * Looking at `multistore.db` alone would give a fake saving: during a sync the `-wal` grows on
     * its own, and it is half of what this operation frees.
     */
    private fun occupiedBytes(): Long =
        SIDECARS.sumOf { suffix -> File(context.getDatabasePath(MultiStoreDatabase.NAME).path + suffix).length() }

    private companion object {
        val SIDECARS = listOf("", "-wal", "-shm")
    }
}
